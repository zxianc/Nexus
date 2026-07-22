package com.nexus.phone.nexus.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Downsample stereo/mono s16 PCM by integer factor (e.g. 48k→16k).
 */
object AudioResampler {
    fun stereoS16ToMono16k(pcm: ByteArray, channels: Int, rate: Int): ShortArray {
        if (channels < 1 || rate < 16000 || pcm.size < channels * 2) {
            return ShortArray(0)
        }
        var factor = rate / 16000
        if (factor < 1 || rate % 16000 != 0) {
            factor = 1
        }
        val frameBytes = channels * 2
        val nFrames = pcm.size / frameBytes
        val outFrames = nFrames / factor
        if (outFrames <= 0) {
            return ShortArray(0)
        }
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(outFrames)
        for (i in 0 until outFrames) {
            var sum = 0
            for (j in 0 until factor) {
                val off = (i * factor + j) * frameBytes
                sum += bb.getShort(off).toInt()
            }
            val v = sum / factor
            out[i] = max(Short.MIN_VALUE.toInt(), min(Short.MAX_VALUE.toInt(), v)).toShort()
        }
        return out
    }

    fun shortsToS16le(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            bb.putShort(s)
        }
        return out
    }
}
