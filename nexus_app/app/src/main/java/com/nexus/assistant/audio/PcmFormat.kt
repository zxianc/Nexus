package com.nexus.assistant.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object PcmFormat {
    fun shortsToFloat(samples: ShortArray): FloatArray {
        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            out[i] = samples[i] / 32768.0f
        }
        return out
    }

    fun floatMonoToS16le(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v =
                max(
                    Short.MIN_VALUE.toInt(),
                    min(Short.MAX_VALUE.toInt(), (samples[i] * 32767.0f).roundToInt()),
                )
            out[i * 2] = (v and 0xff).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    /**
     * Resample mono float → mono s16le at [outRate].
     * HAL incall-music TX is mono 48k (same as Go tx_inject); do NOT pack stereo.
     */
    fun floatMonoToMonoS16(
        samples: FloatArray,
        inRate: Int,
        outRate: Int,
        gain: Float = 1.0f,
    ): ByteArray {
        if (samples.isEmpty() || inRate <= 0 || outRate <= 0) {
            return ByteArray(0)
        }
        val outLen =
            if (inRate == outRate) {
                samples.size
            } else {
                max(1, (samples.size.toLong() * outRate / inRate).toInt())
            }
        val out = ByteArray(outLen * 2)
        val g = gain.coerceAtLeast(0f)
        for (i in 0 until outLen) {
            val f =
                if (inRate == outRate) {
                    samples[i] * g
                } else {
                    val src = i.toDouble() * inRate / outRate
                    val i0 = src.toInt().coerceIn(0, samples.lastIndex)
                    val i1 = (i0 + 1).coerceAtMost(samples.lastIndex)
                    val frac = (src - i0).toFloat()
                    (samples[i0] * (1f - frac) + samples[i1] * frac) * g
                }
            val v =
                max(
                    Short.MIN_VALUE.toInt(),
                    min(Short.MAX_VALUE.toInt(), (f * 32767.0f).roundToInt()),
                )
            out[i * 2] = (v and 0xff).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }
}
