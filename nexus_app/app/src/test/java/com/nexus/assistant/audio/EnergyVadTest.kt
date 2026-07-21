package com.nexus.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyVadTest {
    @Test
    fun speechThenSilence_emitsOneUtterance() {
        val vad =
            EnergyVad(
                EnergyVad.Config(
                    frameMs = 20,
                    speechRms = 100.0,
                    silenceRms = 50.0,
                    minSpeechMs = 40,
                    silenceEndMs = 40,
                    maxSpeechMs = 5000,
                    preRollMs = 20,
                ),
            )
        val speech = ShortArray(320) { 500 }
        val silence = ShortArray(320) { 0 }
        val got = ArrayList<Utterance>()
        repeat(3) { got += vad.push(speech) }
        repeat(3) { got += vad.push(silence) }
        assertEquals(1, got.size)
        assertTrue(got[0].pcm16k.size >= 320)
    }
}
