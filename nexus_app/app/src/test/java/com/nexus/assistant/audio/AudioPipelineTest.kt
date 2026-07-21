package com.nexus.assistant.audio

import com.nexus.assistant.protocol.ApcmHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioPipelineTest {
    @Test
    fun offerDl_countsBytesAndFrames() {
        val pipe = AudioPipeline()
        pipe.configure(ApcmHeader(48000, 2, 16, 1))
        pipe.offerDl(ByteArray(100))
        pipe.offerDl(ByteArray(40))
        assertEquals(2, pipe.totalDlFrames)
        assertEquals(140L, pipe.totalDlBytes)
    }

    @Test
    fun offerDl_resamplesAndSegmentsSpeech() {
        val utterances = ArrayList<Utterance>()
        val pipe =
            AudioPipeline(
                vadConfig =
                    EnergyVad.Config(
                        frameMs = 20,
                        speechRms = 100.0,
                        silenceRms = 50.0,
                        minSpeechMs = 40,
                        silenceEndMs = 40,
                        maxSpeechMs = 5000,
                        preRollMs = 20,
                    ),
                onUtterance = { utterances += it },
            )
        pipe.configure(ApcmHeader(48000, 2, 16, 1))

        // Each 20ms @16k mono = 320 samples = 960 stereo frames @48k = 3840 bytes
        fun stereoTone(frames: Int, amp: Short): ByteArray {
            val buf = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) {
                buf.putShort(amp)
                buf.putShort(amp)
            }
            return buf.array()
        }
        // 3 speech frames + 3 silence frames @16k after downsample
        pipe.offerDl(stereoTone(960 * 3, 500))
        pipe.offerDl(stereoTone(960 * 3, 0))
        assertEquals(1, utterances.size)
        assertTrue(utterances[0].pcm16k.size >= 320)
        assertEquals(1, pipe.totalUtterances)
    }

    @Test
    fun reset_clearsCounters() {
        val pipe = AudioPipeline()
        pipe.offerDl(ByteArray(10))
        pipe.reset()
        assertEquals(0, pipe.totalDlFrames)
        assertEquals(0L, pipe.totalDlBytes)
        assertEquals(0, pipe.totalUtterances)
    }
}
