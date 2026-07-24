package com.nexus.phone.nexus.audio

import kotlin.math.sqrt

data class Utterance(
    val pcm16k: ShortArray,
    val peakRms: Double,
)

/**
 * Energy VAD for 16 kHz mono PCM.
 */
class EnergyVad(
    private val cfg: Config = Config(),
) {
    data class Config(
        val frameMs: Int = 20,
        val speechRms: Double = 400.0,
        val silenceRms: Double = 250.0,
        val minSpeechMs: Int = 500,
        val silenceEndMs: Int = 300,
        val maxSpeechMs: Int = 8000,
        val preRollMs: Int = 200,
    )

    private val frameSamples: Int
    private val preRollSamples: Int
    private val pending = ArrayList<Short>()
    private val preRoll = ArrayList<Short>()
    private val utt = ArrayList<Short>()
    private var inSpeech = false
    private var silenceMs = 0
    private var speechMs = 0
    private var peak = 0.0

    init {
        val frameMs = if (cfg.frameMs <= 0) 20 else cfg.frameMs
        frameSamples = 16000 * frameMs / 1000
        preRollSamples = 16000 * cfg.preRollMs / 1000
    }

    fun push(mono16k: ShortArray): List<Utterance> {
        for (s in mono16k) pending.add(s)
        val out = ArrayList<Utterance>()
        while (pending.size >= frameSamples) {
            val frame = ShortArray(frameSamples) { pending.removeAt(0) }
            feedFrame(frame)?.let { out.add(it) }
        }
        return out
    }

    fun flush(): List<Utterance> {
        val out = ArrayList<Utterance>()
        if (inSpeech && speechMs >= cfg.minSpeechMs && utt.isNotEmpty()) {
            out.add(Utterance(utt.toShortArray(), peak))
        }
        pending.clear()
        preRoll.clear()
        resetUtt()
        return out
    }

    fun reset() {
        pending.clear()
        preRoll.clear()
        resetUtt()
    }

    private fun resetUtt() {
        utt.clear()
        inSpeech = false
        silenceMs = 0
        speechMs = 0
        peak = 0.0
    }

    private fun feedFrame(frame: ShortArray): Utterance? {
        val r = rms(frame)
        if (!inSpeech) {
            for (s in frame) preRoll.add(s)
            if (preRollSamples > 0 && preRoll.size > preRollSamples) {
                val drop = preRoll.size - preRollSamples
                repeat(drop) { preRoll.removeAt(0) }
            }
            if (r >= cfg.speechRms) {
                inSpeech = true
                utt.clear()
                utt.addAll(preRoll)
                for (s in frame) utt.add(s)
                speechMs = cfg.frameMs + cfg.preRollMs
                silenceMs = 0
                peak = r
                preRoll.clear()
            }
            return null
        }

        for (s in frame) utt.add(s)
        speechMs += cfg.frameMs
        if (r > peak) peak = r
        if (r < cfg.silenceRms) {
            silenceMs += cfg.frameMs
        } else {
            silenceMs = 0
        }

        val end = silenceMs >= cfg.silenceEndMs || speechMs >= cfg.maxSpeechMs
        if (!end) return null
        if (speechMs < cfg.minSpeechMs) {
            resetUtt()
            return null
        }
        val u = Utterance(utt.toShortArray(), peak)
        resetUtt()
        return u
    }

    companion object {
        fun rms(mono: ShortArray): Double {
            if (mono.isEmpty()) return 0.0
            var sum = 0.0
            for (s in mono) {
                val v = s.toDouble()
                sum += v * v
            }
            return sqrt(sum / mono.size)
        }
    }
}
