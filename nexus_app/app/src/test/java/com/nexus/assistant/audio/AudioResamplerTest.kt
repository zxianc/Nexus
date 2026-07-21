package com.nexus.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioResamplerTest {
    @Test
    fun stereo48k_toMono16k_lengthIsOneThird() {
        // 30 stereo frames @48k → 10 mono samples @16k
        val frames = 30
        val inBuf = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            inBuf.putShort(300)
            inBuf.putShort(300)
        }
        val out = AudioResampler.stereoS16ToMono16k(inBuf.array(), channels = 2, rate = 48000)
        assertEquals(10, out.size)
        assertEquals(300.toShort(), out[0])
    }

    @Test
    fun mono16k_passthroughFactor1() {
        val samples = 8
        val inBuf = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(samples) { inBuf.putShort(100) }
        val out = AudioResampler.stereoS16ToMono16k(inBuf.array(), channels = 1, rate = 16000)
        assertEquals(8, out.size)
    }
}
