package com.nexus.assistant.archive

import android.content.Context
import android.util.Log
import com.nexus.assistant.config.ConfigRepository
import com.nexus.assistant.notify.WeComNotifier
import com.nexus.assistant.telecom.CallStore
import java.io.File
import kotlin.concurrent.thread

/**
 * Persist call archive + enqueue/drain WeCom notify after hangup.
 */
object CallFinalizer {
    fun archiveRoot(context: Context): File {
        val external = context.getExternalFilesDir("nexus_calls")
        return external ?: File(context.filesDir, "nexus_calls").also { it.mkdirs() }
    }

    fun finalizeCall(context: Context, transcriptLines: List<String>) {
        if (!CallStore.archivePending) {
            Log.i(TAG, "skip finalize (no pending call)")
            return
        }
        CallStore.archivePending = false
        val app = context.applicationContext
        val ended = System.currentTimeMillis()
        val started = CallStore.startedAtMs.takeIf { it > 0 } ?: ended
        val meta =
            CallMeta(
                slot = CallStore.slot,
                number = CallStore.peerNumber,
                policy = CallStore.policy,
                startedAtMs = started,
                endedAtMs = ended,
                aiMode = CallStore.wasAiMode || CallStore.aiMode,
            )
        val cfg = ConfigRepository(app).load()
        val enqueue =
            cfg.notify.enabled && cfg.notify.callEnabled && cfg.notify.webhookUrl.isNotBlank()
        try {
            val writer = CallArchiveWriter(archiveRoot(app))
            val result = writer.write(meta, transcriptLines, enqueueNotify = enqueue)
            Log.i(TAG, "archived ${result.callDir.absolutePath} lines=${transcriptLines.size}")
            if (enqueue) {
                thread(name = "WeComNotify") {
                    val n = WeComNotifier.drainQueue(archiveRoot(app), cfg.notify.webhookUrl)
                    Log.i(TAG, "wecom drained=$n")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "finalizeCall", e)
        } finally {
            CallStore.clearCallMeta()
        }
    }

    private const val TAG = "CallFinalizer"
}
