package com.nexus.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmFormatTest {
    @Test
    fun shortsToFloat_normalizes() {
        val f = PcmFormat.shortsToFloat(shortArrayOf(0, 16384, -32768))
        assertEquals(0f, f[0], 1e-5f)
        assertEquals(0.5f, f[1], 1e-3f)
        assertEquals(-1f, f[2], 1e-5f)
    }

    @Test
    fun floatMonoToMonoS16_sameRate() {
        val samples = floatArrayOf(0.5f, -0.5f)
        val pcm = PcmFormat.floatMonoToMonoS16(samples, 16000, 16000)
        assertEquals(4, pcm.size)
        // 0.5f * 32767 → 16384 = 0x4000 little-endian
        assertEquals(0x00.toByte(), pcm[0])
        assertEquals(0x40.toByte(), pcm[1])
    }

    @Test
    fun floatMonoToMonoS16_upsampleLength() {
        val samples = FloatArray(160) { 0.1f } // 10ms @16k
        val pcm = PcmFormat.floatMonoToMonoS16(samples, 16000, 48000)
        // 480 mono samples * 2 bytes
        assertEquals(480 * 2, pcm.size)
        assertTrue(pcm.any { it != 0.toByte() })
    }

    @Test
    fun floatMonoToMonoS16_gainClamps() {
        val pcm = PcmFormat.floatMonoToMonoS16(floatArrayOf(0.4f), 16000, 16000, gain = 4.0f)
        // 0.4 * 4 * 32767 → clamp to 32767 = 0x7FFF
        assertEquals(0xFF.toByte(), pcm[0])
        assertEquals(0x7F.toByte(), pcm[1])
    }
}
