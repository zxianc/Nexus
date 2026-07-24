package com.nexus.phone.nexus.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class EnergyVadTest {
    private fun toneFrame(samples: Int = 320, amp: Int = 5000): ShortArray {
        val out = ShortArray(samples)
        for (i in out.indices) {
            out[i] = (sin(i * 0.5) * amp).toInt().toShort()
        }
        return out
    }

    private fun silenceFrame(samples: Int = 320): ShortArray = ShortArray(samples)

    @Test
    fun defaultSilenceEndMs_is300() {
        assertEquals(300, EnergyVad.Config().silenceEndMs)
    }

    @Test
    fun silenceEnd_300ms_endsUtterance() {
        val vad =
            EnergyVad(
                EnergyVad.Config(
                    speechRms = 400.0,
                    silenceRms = 250.0,
                    minSpeechMs = 100,
                    silenceEndMs = 300,
                    maxSpeechMs = 8000,
                    preRollMs = 0,
                ),
            )
        // ~200ms speech
        repeat(10) { vad.push(toneFrame()) }
        // 200ms silence — should NOT end yet
        var utts = emptyList<Utterance>()
        repeat(10) { utts = utts + vad.push(silenceFrame()) }
        assertTrue(utts.isEmpty())
        // +100ms silence → total 300ms → end
        repeat(5) { utts = utts + vad.push(silenceFrame()) }
        assertEquals(1, utts.size)
    }
}
