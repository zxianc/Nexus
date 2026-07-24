package com.nexus.phone.nexus.service

import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * FIFO TTS speak queue: LLM SSE offers text; a single consumer synthesizes/injects.
 */
class TtsSpeakQueue(
    private val speak: (text: String) -> Unit,
    private val onError: (Throwable) -> Unit = {},
) {
    private val queue = LinkedBlockingQueue<String>()
    private val started = AtomicBoolean(false)
    private val shutdown = AtomicBoolean(false)
    private val inFlight = AtomicInteger(0)
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val idleLock = Object()
    private var thread: Thread? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        shutdown.set(false)
        thread =
            Thread(
                {
                    while (!shutdown.get()) {
                        val text =
                            try {
                                queue.poll(100, TimeUnit.MILLISECONDS)
                            } catch (_: InterruptedException) {
                                break
                            } ?: continue
                        inFlight.incrementAndGet()
                        try {
                            speak(text)
                        } catch (t: Throwable) {
                            onError(t)
                        } finally {
                            inFlight.decrementAndGet()
                            synchronized(idleLock) { idleLock.notifyAll() }
                        }
                    }
                },
                "NexusTtsSpeak",
            ).also {
                it.isDaemon = true
                it.start()
            }
    }

    fun offer(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (!started.get() || shutdown.get()) {
            Log.w(TAG, "offer dropped (not started): ${t.take(24)}")
            return
        }
        queue.offer(t)
        synchronized(idleLock) { idleLock.notifyAll() }
    }

    fun clear() {
        queue.clear()
        synchronized(idleLock) { idleLock.notifyAll() }
    }

    fun awaitIdle(timeoutMs: Long = 120_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(idleLock) {
            while (true) {
                if (queue.isEmpty() && inFlight.get() == 0) return true
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return false
                idleLock.wait(left.coerceAtMost(100))
            }
        }
    }

    fun shutdown() {
        shutdown.set(true)
        clear()
        thread?.interrupt()
        try {
            thread?.join(1_000)
        } catch (_: InterruptedException) {
        }
        thread = null
        started.set(false)
    }

    companion object {
        private const val TAG = "TtsSpeakQueue"
    }
}
