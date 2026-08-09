package com.nexus.wechat.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WechatFrameTest {
    @Test
    fun encodeDecode_roundTrip() {
        val payload = """{"request_id":"r1"}""".toByteArray()
        val frame = WechatFrame.encode(WechatFrameTypes.SEND_TEXT, payload)
        val (frames, rest) = WechatFrame.decodeAll(frame)
        assertEquals(0, rest.size)
        assertEquals(1, frames.size)
        assertEquals(WechatFrameTypes.SEND_TEXT, frames[0].type)
        assertArrayEquals(payload, frames[0].payload)
    }

    @Test
    fun decodeAll_partialTrailing_keptInRest() {
        val full = WechatFrame.encode(WechatFrameTypes.PING, ByteArray(0))
        val partial = full + byteArrayOf(WechatFrameTypes.HELLO.toByte(), 0)
        val (frames, rest) = WechatFrame.decodeAll(partial)
        assertEquals(1, frames.size)
        assertEquals(2, rest.size)
    }
}
