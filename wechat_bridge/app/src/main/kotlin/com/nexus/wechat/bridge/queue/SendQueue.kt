package com.nexus.wechat.bridge.queue

import com.nexus.wechat.bridge.uds.HookSession
import com.nexus.wechat.bridge.uds.SendResult
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SendQueue(
    private val session: HookSession,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private data class Job(
        val chatId: String,
        val text: String,
        val ats: List<String>,
        val result: java.util.concurrent.CompletableFuture<SendResult>,
    )

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
                val r = session.requestSendText(job.chatId, job.text, job.ats)
                job.result.complete(r)
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    fun enqueueText(chatId: String, text: String, ats: List<String>): java.util.concurrent.CompletableFuture<SendResult> {
        val fut = java.util.concurrent.CompletableFuture<SendResult>()
        queue.offer(Job(chatId, text, ats, fut))
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
