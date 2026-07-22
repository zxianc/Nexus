package com.nexus.phone.nexus.archive

import android.content.Context
import android.util.Log
import com.nexus.phone.nexus.ai.ChatMessage
import com.nexus.phone.nexus.config.ConfigRepository
import com.nexus.phone.nexus.config.LocalLineResolver
import com.nexus.phone.nexus.notify.WebhookDeliveryResult
import com.nexus.phone.nexus.notify.WebhookDeliveryStatus
import com.nexus.phone.nexus.notify.WebhookNotifier
import com.nexus.phone.nexus.telecom.CallStore
import java.io.File
import kotlin.concurrent.thread

/**
 * Hangup path: memory webhook (retry then mark) → structured call.json on disk.
 * No file-based notify queue.
 */
object CallFinalizer {
    fun archiveRoot(context: Context): File {
        val external = context.getExternalFilesDir("nexus_calls")
        return external ?: File(context.filesDir, "nexus_calls").also { it.mkdirs() }
    }

    fun finalizeCall(context: Context, turns: List<ChatMessage>) {
        if (!CallStore.archivePending) {
            Log.i(TAG, "skip finalize (no pending call)")
            return
        }
        CallStore.archivePending = false
        val app = context.applicationContext
        val ended = System.currentTimeMillis()
        val started = CallStore.startedAtMs.takeIf { it > 0 } ?: ended
        val slot = CallStore.slot
        val local = LocalLineResolver.forSlot(app, slot)
        val meta =
            CallMeta(
                slot = slot,
                peerNumber = CallStore.peerNumber,
                localNumber = local.number,
                localLabel = local.label,
                localCarrier = local.carrier,
                policy = CallStore.policy,
                startedAtMs = started,
                endedAtMs = ended,
                aiMode = CallStore.wasAiMode || CallStore.aiMode,
            )
        val cfg = ConfigRepository(app).load()
        val dirName = CallArchiveWriter.formatCallDirName(meta.startedAtMs, meta.slot)
        val wantNotify =
            cfg.notify.enabled && cfg.notify.callEnabled && cfg.notify.webhookUrl.isNotBlank()

        // Snapshot for background work; clear CallStore promptly.
        val turnsCopy = turns.toList()
        CallStore.clearCallMeta()

        thread(name = "CallFinalize") {
            try {
                val notifyResult =
                    if (!wantNotify) {
                        WebhookDeliveryResult(WebhookDeliveryStatus.SKIPPED, 0, null)
                    } else {
                        val text = CallArchiveWriter.buildNotifyText(meta, turnsCopy, dirName)
                        WebhookNotifier.sendWithRetry(cfg.notify.webhookUrl, text)
                    }
                Log.i(
                    TAG,
                    "webhook status=${notifyResult.status} attempts=${notifyResult.attempts} err=${notifyResult.error}",
                )
                val result =
                    CallArchiveWriter(archiveRoot(app)).write(meta, turnsCopy, notifyResult)
                Log.i(TAG, "archived ${result.callDir.absolutePath} turns=${turnsCopy.size}")
            } catch (e: Exception) {
                Log.e(TAG, "finalizeCall", e)
            }
        }
    }

    private const val TAG = "CallFinalizer"
}
