package com.nexus.wechat.bridge.queue

import com.nexus.wechat.bridge.uds.HookSession
import com.nexus.wechat.bridge.uds.SendResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SendQueue(
    private val session: HookSession,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private sealed class Job {
        abstract val result: CompletableFuture<SendResult>

        data class Text(
            val chatId: String,
            val text: String,
            val ats: List<String>,
            override val result: CompletableFuture<SendResult>,
        ) : Job()

        data class Media(
            val chatId: String,
            val kind: String,
            val path: String,
            val name: String,
            val mediaId: String,
            val dataB64: String,
            val original: Boolean,
            override val result: CompletableFuture<SendResult>,
        ) : Job()
    }

    private val queue = LinkedBlockingQueue<Job>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wechat-send-queue").apply { isDaemon = true }
    }

    init {
        executor.execute {
            while (!Thread.currentThread().isInterrupted) {
                val job = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    break
                }
                val r = when (job) {
                    is Job.Text -> session.requestSendText(job.chatId, job.text, job.ats)
                    is Job.Media -> session.requestSendMedia(
                        job.chatId, job.kind, job.path, job.name, job.mediaId, job.dataB64, job.original,
                    )
                }
                job.result.complete(r)
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    fun enqueueText(chatId: String, text: String, ats: List<String>): CompletableFuture<SendResult> {
        val fut = CompletableFuture<SendResult>()
        queue.offer(Job.Text(chatId, text, ats, fut))
        return fut
    }

    fun enqueueMedia(
        chatId: String,
        kind: String,
        path: String,
        name: String,
        mediaId: String = "",
        dataB64: String = "",
        original: Boolean = true,
    ): CompletableFuture<SendResult> {
        val fut = CompletableFuture<SendResult>()
        queue.offer(Job.Media(chatId, kind, path, name, mediaId, dataB64, original, fut))
        return fut
    }

    fun shutdown() {
        executor.shutdownNow()
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 800L
    }
}
