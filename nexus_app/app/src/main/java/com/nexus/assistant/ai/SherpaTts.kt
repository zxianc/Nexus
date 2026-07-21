package com.nexus.assistant.ai

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class TtsAudio(
    val samples: FloatArray,
    val sampleRate: Int,
)

/**
 * Offline VITS TTS via sherpa-onnx 1.13.4 AAR.
 */
class SherpaTts(
    private val layout: ModelLayout,
    private val speakerId: Int = 0, // match Go ai_call default -tts-sid
    private val speed: Float = 1.0f,
    private val threads: Int = 2,
) : AutoCloseable {
    private val lock = Any()
    private var tts: OfflineTts? = null
    private val ready = AtomicBoolean(false)

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
                val modelConfig =
                    OfflineTtsModelConfig().apply {
                        vits =
                            OfflineTtsVitsModelConfig().apply {
                                model = layout.ttsModel.absolutePath
                                lexicon = layout.ttsLexicon.absolutePath
                                tokens = layout.ttsTokens.absolutePath
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
                    "TTS loaded from ${layout.ttsModel} sampleRate=${tts!!.sampleRate()} speakers=${tts!!.numSpeakers()}",
                )
                true
            } catch (e: Exception) {
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
                val audio = engine.generate(trimmed, sid, speed)
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
