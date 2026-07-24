package com.nexus.phone.nexus.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TtsSpeakQueueTest {
    @Test
    fun offer_speaksInOrder_thenAwaitIdle() {
        val heard = CopyOnWriteArrayList<String>()
        val q =
            TtsSpeakQueue(
                speak = { text ->
                    Thread.sleep(20)
                    heard.add(text)
                },
            )
        q.start()
        q.offer("一")
        q.offer("二")
        assertTrue(q.awaitIdle(5_000))
        assertEquals(listOf("一", "二"), heard.toList())
        q.shutdown()
    }

    @Test
    fun clear_dropsPending_butKeepsInFlight() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val heard = CopyOnWriteArrayList<String>()
        val q =
            TtsSpeakQueue(
                speak = { text ->
                    started.countDown()
                    release.await(2, TimeUnit.SECONDS)
                    heard.add(text)
                },
            )
        q.start()
        q.offer("A")
        assertTrue(started.await(2, TimeUnit.SECONDS))
        q.offer("B")
        q.clear()
        release.countDown()
        assertTrue(q.awaitIdle(5_000))
        assertEquals(listOf("A"), heard.toList())
        q.shutdown()
    }
}
