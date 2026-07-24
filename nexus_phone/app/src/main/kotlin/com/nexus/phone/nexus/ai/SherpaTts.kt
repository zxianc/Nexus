package com.nexus.phone.nexus.ai

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.nexus.phone.nexus.config.ConfigRepository
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class TtsAudio(
    val samples: FloatArray,
    val sampleRate: Int,
)

/**
 * Offline VITS TTS via sherpa-onnx 1.13.4 AAR.
 *
 * [speed]: user-facing rate (1.0 = normal, higher = faster). Applied as
 * `lengthScale = 1/speed` (matches sherpa fanchen examples using length-scale &lt; 1).
 */
class SherpaTts(
    private val layout: ModelLayout,
    val speakerId: Int = 0,
    val speed: Float = 1.0f,
    private val threads: Int = 2,
) : AutoCloseable {
    private val lock = Any()
    private var tts: OfflineTts? = null
    private val ready = AtomicBoolean(false)
    private val clampedSpeed =
        speed.coerceIn(ConfigRepository.TTS_SPEED_MIN, ConfigRepository.TTS_SPEED_MAX)

    val modelPath: String
        get() = layout.ttsModel.absolutePath

    fun ensureLoaded(): Boolean {
        if (ready.get()) return true
        synchronized(lock) {
            if (ready.get()) return true
            if (!layout.ttsReady()) {
                Log.e(TAG, "TTS model missing: ${layout.missing()}")
                return false
            }
            return try {
                val fsts =
                    listOf("phone.fst", "date.fst", "number.fst")
                        .map { File(layout.ttsSidecarDir, it) }
                        .filter { it.isFile }
                        .joinToString(",") { it.absolutePath }
                val lengthScale = (1.0f / clampedSpeed).coerceIn(0.5f, 2.0f)
                val modelConfig =
                    OfflineTtsModelConfig().apply {
                        vits =
                            OfflineTtsVitsModelConfig().apply {
                                model = layout.ttsModel.absolutePath
                                lexicon = layout.ttsLexicon.absolutePath
                                tokens = layout.ttsTokens.absolutePath
                                this.lengthScale = lengthScale
                            }
                        numThreads = threads
                        debug = false
                        provider = "cpu"
                    }
                val config =
                    OfflineTtsConfig().apply {
                        model = modelConfig
                        ruleFsts = fsts
                        maxNumSentences = 1
                    }
                tts = OfflineTts(null, config)
                ready.set(true)
                Log.i(
                    TAG,
                    "TTS loaded from ${layout.ttsModel} sampleRate=${tts!!.sampleRate()} " +
                        "speakers=${tts!!.numSpeakers()} speed=$clampedSpeed lengthScale=$lengthScale",
                )
                true
            } catch (e: Throwable) {
                Log.e(TAG, "TTS load failed", e)
                false
            }
        }
    }

    fun synthesize(text: String, sid: Int = speakerId): TtsAudio? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (!ensureLoaded()) return null
        val engine = tts ?: return null
        synchronized(lock) {
            return try {
                // lengthScale already encodes rate; keep generate speed at 1.0
                Log.i(
                    TAG,
                    "synthesize sid=$sid speed=$clampedSpeed model=${layout.ttsModel.name} " +
                        "chars=${trimmed.length}",
                )
                val audio = engine.generate(trimmed, sid, 1.0f)
                TtsAudio(audio.samples, audio.sampleRate)
            } catch (e: Exception) {
                Log.e(TAG, "TTS synthesize failed", e)
                null
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            try {
                tts?.release()
            } catch (_: Exception) {
            }
            tts = null
            ready.set(false)
        }
    }

    companion object {
        private const val TAG = "SherpaTts"
    }
}
