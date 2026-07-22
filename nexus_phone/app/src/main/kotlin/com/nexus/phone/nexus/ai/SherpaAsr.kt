package com.nexus.phone.nexus.ai

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.nexus.phone.nexus.audio.PcmFormat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline SenseVoice via sherpa-onnx 1.13.4 AAR.
 */
class SherpaAsr(
    private val layout: ModelLayout,
    private val lang: String = "auto",
    private val threads: Int = 2,
) : AutoCloseable {
    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    private val ready = AtomicBoolean(false)

    fun ensureLoaded(): Boolean {
        if (ready.get()) return true
        synchronized(lock) {
            if (ready.get()) return true
            if (!layout.asrReady()) {
                Log.e(TAG, "ASR model missing: ${layout.missing()}")
                return false
            }
            return try {
                val modelConfig =
                    OfflineModelConfig().apply {
                        senseVoice =
                            OfflineSenseVoiceModelConfig().apply {
                                model = layout.asrModel.absolutePath
                                language = lang
                                useInverseTextNormalization = true
                            }
                        tokens = layout.asrTokens.absolutePath
                        numThreads = threads
                        debug = false
                        provider = "cpu"
                        modelType = "sense_voice"
                    }
                val config =
                    OfflineRecognizerConfig().apply {
                        featConfig = FeatureConfig(16000, 80, 0.0f)
                        this.modelConfig = modelConfig
                        decodingMethod = "greedy_search"
                    }
                recognizer = OfflineRecognizer(null, config)
                ready.set(true)
                Log.i(TAG, "ASR loaded from ${layout.asrModel}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "ASR load failed", e)
                false
            }
        }
    }

    fun transcribe(pcm16kMono: ShortArray): String {
        if (!ensureLoaded()) return ""
        val rec = recognizer ?: return ""
        synchronized(lock) {
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(PcmFormat.shortsToFloat(pcm16kMono), 16000)
                rec.decode(stream)
                return rec.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            try {
                recognizer?.release()
            } catch (_: Exception) {
            }
            recognizer = null
            ready.set(false)
        }
    }

    companion object {
        private const val TAG = "SherpaAsr"
    }
}
