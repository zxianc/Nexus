package com.nexus.phone.nexus.archive

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.nexus.phone.nexus.ai.ChatMessage
import com.nexus.phone.nexus.notify.WebhookDeliveryResult
import com.nexus.phone.nexus.notify.WebhookDeliveryStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class CallMeta(
    val slot: Int,
    /** Remote party (caller / peer). */
    val peerNumber: String,
    /** This device's receiving line. */
    val localNumber: String = "",
    val localLabel: String = "",
    val localCarrier: String = "",
    val policy: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val aiMode: Boolean,
) {
    fun localDisplay(): String {
        val card = "卡${slot + 1}"
        val carrierPart = localCarrier.ifBlank { localLabel }
        val num = localNumber.ifBlank { "号码未知" }
        return buildString {
            append(card)
            if (carrierPart.isNotBlank()) {
                append(" ")
                append(carrierPart)
            }
            append(" ")
            append(num)
        }
    }
}

data class CallArchiveResult(
    val callDir: File,
    val callJson: File,
    val transcriptFile: File,
)

/**
 * Structured one-call archive under `{root}/calls/<id>/call.json`.
 * Webhook delivery is done in memory before write; status is recorded in call.json.
 */
class CallArchiveWriter(
    private val root: File,
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create(),
) {
    fun write(
        meta: CallMeta,
        turns: List<ChatMessage>,
        notify: WebhookDeliveryResult,
    ): CallArchiveResult {
        val calls = File(root, "calls").apply { mkdirs() }
        val dirName = formatCallDirName(meta.startedAtMs, meta.slot)
        val callDir = File(calls, dirName).apply { mkdirs() }
        File(callDir, "audio").mkdirs()

        val transcriptLines = turnsToLines(turns)
        val record =
            mapOf(
                "id" to dirName,
                "slot" to meta.slot,
                "peer_number" to meta.peerNumber,
                "local_number" to meta.localNumber,
                "local_label" to meta.localLabel,
                "local_carrier" to meta.localCarrier,
                "local_display" to meta.localDisplay(),
                "policy" to meta.policy,
                "started_at_ms" to meta.startedAtMs,
                "ended_at_ms" to meta.endedAtMs,
                "duration_sec" to ((meta.endedAtMs - meta.startedAtMs).coerceAtLeast(0) / 1000),
                "ai_mode" to meta.aiMode,
                "turns" to
                    turns.map { m ->
                        mapOf("role" to m.role, "content" to m.content)
                    },
                "notify" to
                    mapOf(
                        "status" to notify.status.name.lowercase(),
                        "attempts" to notify.attempts,
                        "error" to notify.error,
                    ),
            )

        val callJson = File(callDir, "call.json")
        callJson.writeText(gson.toJson(record) + "\n")

        // Keep a short meta.json for older tooling / quick glance.
        File(callDir, "meta.json").writeText(
            gson.toJson(
                mapOf(
                    "slot" to meta.slot,
                    "peer_number" to meta.peerNumber,
                    "local_number" to meta.localNumber,
                    "local_display" to meta.localDisplay(),
                    "policy" to meta.policy,
                    "started_at_ms" to meta.startedAtMs,
                    "ended_at_ms" to meta.endedAtMs,
                    "ai_mode" to meta.aiMode,
                    "notify_status" to notify.status.name.lowercase(),
                ),
            ) + "\n",
        )

        val transcriptFile = File(callDir, "transcript.txt")
        transcriptFile.writeText(
            if (transcriptLines.isEmpty()) "" else transcriptLines.joinToString("\n") + "\n",
        )

        File(callDir, "summary.txt").writeText(buildSummary(meta, transcriptLines, notify))

        return CallArchiveResult(callDir, callJson, transcriptFile)
    }

    companion object {
        fun formatCallDirName(startedAtMs: Long, slot: Int): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
            fmt.timeZone = TimeZone.getDefault()
            return "${fmt.format(Date(startedAtMs))}_slot$slot"
        }

        fun turnsToLines(turns: List<ChatMessage>): List<String> =
            turns.map { m ->
                val who =
                    when (m.role) {
                        "user" -> "用户"
                        "assistant" -> "助理"
                        else -> m.role
                    }
                "$who: ${m.content}"
            }

        fun buildSummary(
            meta: CallMeta,
            lines: List<String>,
            notify: WebhookDeliveryResult,
        ): String {
            val durSec = ((meta.endedAtMs - meta.startedAtMs).coerceAtLeast(0)) / 1000
            return buildString {
                appendLine("卡槽: ${meta.slot}")
                appendLine("对方: ${meta.peerNumber.ifBlank { "未知" }}")
                appendLine("本机: ${meta.localDisplay()}")
                appendLine("策略: ${meta.policy}")
                appendLine("AI: ${meta.aiMode}")
                appendLine("时长秒: $durSec")
                appendLine("句数: ${lines.size}")
                appendLine("Webhook: ${notify.status.name.lowercase()} attempts=${notify.attempts}")
                if (!notify.error.isNullOrBlank() && notify.status == WebhookDeliveryStatus.FAILED) {
                    appendLine("Webhook错误: ${notify.error}")
                }
            }
        }

        fun buildNotifyText(meta: CallMeta, turns: List<ChatMessage>, dirName: String): String {
            val lines = turnsToLines(turns)
            val preview = lines.takeLast(12).joinToString("\n").ifBlank { "（无转写）" }
            return buildString {
                appendLine("【Nexus 通话】")
                appendLine("id: $dirName")
                appendLine("对方: ${meta.peerNumber.ifBlank { "未知" }}")
                appendLine("本机: ${meta.localDisplay()}")
                appendLine("策略: ${meta.policy}")
                appendLine("---")
                append(preview)
            }
        }
    }
}
