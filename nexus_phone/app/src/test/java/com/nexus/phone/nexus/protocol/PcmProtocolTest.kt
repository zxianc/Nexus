package com.nexus.phone.nexus.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmProtocolTest {
    @Test
    fun parseApcmHeader_matchesHalLayout() {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x4D435041)
        buf.putInt(48000)
        buf.putShort(2)
        buf.putShort(16)
        buf.putShort(1) // DL
        buf.putShort(0)
        val h = PcmProtocol.parseApcmHeader(buf.array())
        assertEquals(48000, h.rate)
        assertEquals(2, h.channels)
        assertEquals(16, h.bits)
        assertEquals(1, h.kind)
    }

    @Test
    fun encodeDecode_ctrlMute_roundTrip() {
        val raw = PcmProtocol.encodeFrame(PcmProtocol.TYPE_CTRL_MUTE, byteArrayOf(1))
        val frames = FrameReader().feed(raw)
        assertEquals(1, frames.size)
        val m = frames[0] as PcmFrame.CtrlMute
        assertTrue(m.on)
    }

    @Test
    fun feed_splitsAcrossChunks() {
        val payload = ByteArray(100) { it.toByte() }
        val full = PcmProtocol.encodeFrame(PcmProtocol.TYPE_PCM_DL, payload)
        val r = FrameReader()
        assertTrue(r.feed(full.copyOfRange(0, 3)).isEmpty())
        val rest = r.feed(full.copyOfRange(3, full.size))
        assertEquals(1, rest.size)
        assertArrayEquals(payload, (rest[0] as PcmFrame.PcmDl).pcm)
    }

    @Test
    fun lengthOverCap_throws() {
        try {
            PcmProtocol.encodeFrame(1, ByteArray(70_000))
            fail("expected ProtocolException")
        } catch (_: ProtocolException) {
            // ok
        }
    }
}
