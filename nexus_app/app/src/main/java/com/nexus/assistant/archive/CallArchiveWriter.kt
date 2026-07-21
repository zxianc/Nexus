package com.nexus.assistant.archive

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class CallMeta(
    val slot: Int,
    val number: String,
    val policy: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val aiMode: Boolean,
)

data class CallArchiveResult(
    val callDir: File,
    val metaFile: File,
    val transcriptFile: File,
    val notifyJob: File?,
)

/**
 * Writes one-call-per-dir archives under `{root}/calls/...` plus optional notify_queue jobs.
 */
class CallArchiveWriter(
    private val root: File,
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun write(
        meta: CallMeta,
        transcriptLines: List<String>,
        enqueueNotify: Boolean,
    ): CallArchiveResult {
        val calls = File(root, "calls").apply { mkdirs() }
        File(root, "notify_queue").mkdirs()

        val dirName = formatCallDirName(meta.startedAtMs, meta.slot)
        val callDir = File(calls, dirName).apply { mkdirs() }
        File(callDir, "audio").mkdirs()

        val metaFile = File(callDir, "meta.json")
        metaFile.writeText(gson.toJson(meta.toMap()) + "\n")

        val transcriptFile = File(callDir, "transcript.txt")
        val body =
            if (transcriptLines.isEmpty()) {
                ""
            } else {
                transcriptLines.joinToString("\n") + "\n"
            }
        transcriptFile.writeText(body)

        val summaryFile = File(callDir, "summary.txt")
        summaryFile.writeText(buildSummary(meta, transcriptLines))

        var notifyJob: File? = null
        if (enqueueNotify) {
            val job =
                File(File(root, "notify_queue"), "$dirName.json")
            val text = buildNotifyText(meta, transcriptLines, dirName)
            job.writeText(
                gson.toJson(
                    mapOf(
                        "type" to "call",
                        "call_dir" to dirName,
                        "text" to text,
                        "created_at_ms" to clock(),
                    ),
                ) + "\n",
            )
            notifyJob = job
        }

        return CallArchiveResult(callDir, metaFile, transcriptFile, notifyJob)
    }

    companion object {
        fun formatCallDirName(startedAtMs: Long, slot: Int): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
            fmt.timeZone = TimeZone.getDefault()
            return "${fmt.format(Date(startedAtMs))}_slot$slot"
        }

        fun buildSummary(meta: CallMeta, lines: List<String>): String {
            val durSec = ((meta.endedAtMs - meta.startedAtMs).coerceAtLeast(0)) / 1000
            return buildString {
                appendLine("卡槽: ${meta.slot}")
                appendLine("号码: ${meta.number}")
                appendLine("策略: ${meta.policy}")
                appendLine("AI: ${meta.aiMode}")
                appendLine("时长秒: $durSec")
                appendLine("句数: ${lines.size}")
            }
        }

        fun buildNotifyText(meta: CallMeta, lines: List<String>, dirName: String): String {
            val preview =
                lines.takeLast(12).joinToString("\n").ifBlank { "（无转写）" }
            return buildString {
                appendLine("【Nexus 通话存档】")
                appendLine("目录: $dirName")
                appendLine("卡${meta.slot + 1} ${meta.number} 策略=${meta.policy}")
                appendLine("---")
                append(preview)
            }
        }
    }

    private fun CallMeta.toMap(): Map<String, Any?> =
        mapOf(
            "slot" to slot,
            "number" to number,
            "policy" to policy,
            "started_at_ms" to startedAtMs,
            "ended_at_ms" to endedAtMs,
            "ai_mode" to aiMode,
        )
}
