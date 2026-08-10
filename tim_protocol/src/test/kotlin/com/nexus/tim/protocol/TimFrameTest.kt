package com.nexus.tim.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TimFrameTest {
    @Test
    fun encodeDecode_roundTrip() {
        val payload = """{"request_id":"r1"}""".toByteArray()
        val frame = TimFrame.encode(TimFrameTypes.SEND_TEXT, payload)
        val (frames, rest) = TimFrame.decodeAll(frame)
        assertEquals(0, rest.size)
        assertEquals(1, frames.size)
        assertEquals(TimFrameTypes.SEND_TEXT, frames[0].type)
        assertArrayEquals(payload, frames[0].payload)
    }

    @Test
    fun decodeAll_partialTrailing_keptInRest() {
        val full = TimFrame.encode(TimFrameTypes.PING, ByteArray(0))
        val partial = full + byteArrayOf(TimFrameTypes.HELLO.toByte(), 0)
        val (frames, rest) = TimFrame.decodeAll(partial)
        assertEquals(1, frames.size)
        assertEquals(2, rest.size)
    }
}
