package com.nexus.phone.nexus.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.nexus.phone.nexus.audio.PcmFormat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * One-shot TTS preview for settings (not the call PCM bypass path).
 */
class TtsPreviewPlayer {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var generation = 0
    private val playing = AtomicBoolean(false)

    fun isPlaying(): Boolean = playing.get()

    fun stop() {
        synchronized(lock) {
            generation++
            stopLocked()
        }
    }

    /**
     * Synthesize [text] with [speakerId] on a background thread, then play via AudioTrack.
     * [onResult] runs on a worker thread: null = started; non-null = error/cancel code.
     */
    fun play(
        context: Context,
        speakerId: Int,
        text: String,
        speed: Float = 1.0f,
        onResult: (error: String?) -> Unit,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            onResult("empty")
            return
        }
        val gen: Int
        synchronized(lock) {
            stopLocked()
            generation++
            gen = generation
        }
        thread(name = "TtsPreview") {
            var engine: SherpaTts? = null
            try {
                val layout = ModelPaths.resolve(context.applicationContext)
                if (!layout.ttsReady()) {
                    onResult("missing")
                    return@thread
                }
                engine = SherpaTts(layout, speakerId = speakerId, speed = speed)
                if (!engine.ensureLoaded()) {
                    onResult("load")
                    return@thread
                }
                val audio = engine.synthesize(trimmed, sid = speakerId)
                if (audio == null || audio.samples.isEmpty()) {
                    onResult("synth")
                    return@thread
                }
                val pcm = PcmFormat.floatMonoToS16le(audio.samples)
                synchronized(lock) {
                    if (gen != generation) {
                        onResult("cancelled")
                        return@thread
                    }
                    startTrackLocked(pcm, audio.sampleRate)
                }
                onResult(null)
            } catch (e: Exception) {
                Log.e(TAG, "preview failed", e)
                onResult(e.message ?: "error")
            } finally {
                try {
                    engine?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun stopLocked() {
        playing.set(false)
        try {
            track?.stop()
        } catch (_: Exception) {
        }
        try {
            track?.release()
        } catch (_: Exception) {
        }
        track = null
    }

    private fun startTrackLocked(pcm: ByteArray, sampleRate: Int) {
        stopLocked()
        if (pcm.isEmpty() || sampleRate <= 0) return
        val minBuf =
            AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        val created =
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(pcm.size.coerceAtLeast(minBuf))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        val written = created.write(pcm, 0, pcm.size)
        if (written < 0) {
            created.release()
            return
        }
        created.setNotificationMarkerPosition(pcm.size / 2)
        created.setPlaybackPositionUpdateListener(
            object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack?) {
                    synchronized(lock) {
                        if (track === t) stopLocked()
                    }
                }

                override fun onPeriodicNotification(t: AudioTrack?) = Unit
            },
        )
        track = created
        playing.set(true)
        created.play()
    }

    companion object {
        private const val TAG = "TtsPreviewPlayer"
    }
}
