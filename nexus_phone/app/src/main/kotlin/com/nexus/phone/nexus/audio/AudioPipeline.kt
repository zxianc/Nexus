package com.nexus.phone.nexus.audio

import com.nexus.phone.nexus.protocol.ApcmHeader

/**
 * DL sink: count frames → resample to 16k mono → energy VAD utterances.
 */
class AudioPipeline(
    vadConfig: EnergyVad.Config = EnergyVad.Config(),
    private val onUtterance: (Utterance) -> Unit = {},
) {
    private val lock = Any()
    private val vad = EnergyVad(vadConfig)
    private var rate: Int = 48000
    private var channels: Int = 2
    private var carry = ByteArray(0)

    @Volatile
    var totalDlFrames: Int = 0
        private set

    @Volatile
    var totalDlBytes: Long = 0L
        private set

    @Volatile
    var totalUtterances: Int = 0
        private set

    @Volatile
    var totalMonoSamples: Long = 0L
        private set

    fun configure(hdr: ApcmHeader) {
        synchronized(lock) {
            rate = if (hdr.rate > 0) hdr.rate else 48000
            channels = if (hdr.channels > 0) hdr.channels else 2
            resetLocked(full = true)
        }
    }

    fun offerDl(pcm: ByteArray) {
        val utterances =
            synchronized(lock) {
                totalDlFrames += 1
                totalDlBytes += pcm.size.toLong()
                if (pcm.isEmpty()) return
                processDlLocked(pcm)
            }
        for (u in utterances) {
            totalUtterances += 1
            onUtterance(u)
        }
    }

    fun flush(): List<Utterance> {
        val left =
            synchronized(lock) {
                val u = vad.flush()
                carry = ByteArray(0)
                u
            }
        totalUtterances += left.size
        for (u in left) onUtterance(u)
        return left
    }

    /** Drop in-flight VAD speech (e.g. after TTS to avoid echo → ASR loop). */
    fun resetVad() {
        synchronized(lock) {
            resetLocked(full = false)
        }
    }

    fun reset() {
        synchronized(lock) {
            resetLocked(full = true)
        }
    }

    private fun resetLocked(full: Boolean) {
        if (full) {
            totalDlFrames = 0
            totalDlBytes = 0L
            totalUtterances = 0
            totalMonoSamples = 0L
        }
        carry = ByteArray(0)
        vad.reset()
    }

    private fun processDlLocked(pcm: ByteArray): List<Utterance> {
        val joined =
            if (carry.isEmpty()) {
                pcm
            } else {
                ByteArray(carry.size + pcm.size).also {
                    System.arraycopy(carry, 0, it, 0, carry.size)
                    System.arraycopy(pcm, 0, it, carry.size, pcm.size)
                }
            }

        val frameBytes = channels * 2
        if (frameBytes <= 0 || joined.size < frameBytes) {
            carry = joined
            return emptyList()
        }
        var factor = rate / 16000
        if (factor < 1 || rate % 16000 != 0) {
            factor = 1
        }
        val nFrames = joined.size / frameBytes
        val usableFrames = (nFrames / factor) * factor
        val usableBytes = usableFrames * frameBytes
        if (usableBytes <= 0) {
            carry = joined
            return emptyList()
        }

        val chunk =
            if (usableBytes == joined.size) {
                joined
            } else {
                joined.copyOf(usableBytes)
            }
        carry =
            if (usableBytes < joined.size) {
                joined.copyOfRange(usableBytes, joined.size)
            } else {
                ByteArray(0)
            }

        val mono = AudioResampler.stereoS16ToMono16k(chunk, channels, rate)
        totalMonoSamples += mono.size.toLong()
        return vad.push(mono)
    }
}
