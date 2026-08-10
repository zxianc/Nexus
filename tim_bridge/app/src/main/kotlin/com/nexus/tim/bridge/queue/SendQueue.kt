package com.nexus.tim.bridge.queue

import com.nexus.tim.bridge.uds.HookSession
import com.nexus.tim.bridge.uds.SendResult
import java.util.concurrent.CompletableFuture
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
        val result: CompletableFuture<SendResult>,
    )

    private val queue = LinkedBlockingQueue<Job>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tim-send-queue").apply { isDaemon = true }
    }

    init {
        executor.execute {
            while (!Thread.currentThread().isInterrupted) {
                val job = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    break
                }
                job.result.complete(session.requestSendText(job.chatId, job.text))
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    fun enqueueText(chatId: String, text: String): CompletableFuture<SendResult> {
        val fut = CompletableFuture<SendResult>()
        queue.offer(Job(chatId, text, fut))
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
