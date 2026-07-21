package com.nexus.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.nexus.assistant.ai.CallSessionController
import com.nexus.assistant.ai.DeepSeekClient
import com.nexus.assistant.ai.ModelPaths
import com.nexus.assistant.ai.SherpaAsr
import com.nexus.assistant.ai.SherpaTts
import com.nexus.assistant.archive.CallFinalizer
import com.nexus.assistant.audio.AudioPipeline
import com.nexus.assistant.audio.PcmFormat
import com.nexus.assistant.audio.Utterance
import com.nexus.assistant.config.ConfigRepository
import com.nexus.assistant.uds.PcmSocketClient
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * FGS session: UDS PCM + VAD → ASR → DeepSeek → TTS → PCM_UL.
 */
class NexusBypassService : Service() {
    @Volatile
    private var sessionWanted = false

    @Volatile
    private var client: PcmSocketClient? = null

    @Volatile
    private var bridgeRunning = false

    @Volatile
    private var injectRate = 48000

    private var asr: SherpaAsr? = null
    private var tts: SherpaTts? = null
    private var callLlm: CallSessionController? = null
    private val aiBusy = AtomicBoolean(false)
    /** While TTS is on UL (and shortly after), ignore DL utterances — echo would re-trigger LLM. */
    @Volatile
    private var listenSuppressedUntilMs: Long = 0L
    private val ttsDepth = AtomicInteger(0)
    private val aiExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NexusAiWorker").apply { isDaemon = true }
    }

    private lateinit var bridge: SessionBridge

    override fun onCreate() {
        super.onCreate()
        bridge =
            SessionBridge(
                pipeline =
                    AudioPipeline { u ->
                        if (isListenSuppressed()) {
                            Log.i(
                                TAG,
                                "VAD drop (TTS echo guard) samples=${u.pcm16k.size} " +
                                    "peakRms=${"%.0f".format(u.peakRms)}",
                            )
                            return@AudioPipeline
                        }
                        Log.i(
                            TAG,
                            "VAD utterance samples=${u.pcm16k.size} peakRms=${"%.0f".format(u.peakRms)} " +
                                "durMs=${u.pcm16k.size * 1000 / 16000}",
                        )
                        enqueueUtterance(u)
                    },
            )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                sessionWanted = true
                startAsForeground()
                ensureBridge()
            }
            ACTION_HUMAN -> {
                thread(name = "NexusUdsCtrl") {
                    try {
                        client?.sendMute(false)
                        client?.sendFlushUl()
                    } catch (e: Exception) {
                        Log.e(TAG, "human mode ctrl", e)
                    }
                }
            }
            ACTION_END -> {
                sessionWanted = false
                val lines = callLlm?.transcriptLines().orEmpty()
                teardown()
                CallFinalizer.finalizeCall(this, lines)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        sessionWanted = false
        releaseAi()
        aiExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val channelId = "nexus_call"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Nexus Call", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification =
            Notification.Builder(this, channelId)
                .setContentTitle("Nexus AI 通话")
                .setContentText("音频旁路运行中")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureBridge() {
        synchronized(this) {
            if (bridgeRunning) {
                return
            }
            bridgeRunning = true
        }
        thread(name = "NexusUdsBridge") {
            try {
                while (sessionWanted) {
                    runOneConnection()
                }
            } finally {
                bridge.onIdle()
                releaseAi()
                synchronized(this) {
                    bridgeRunning = false
                }
            }
        }
    }

    private fun runOneConnection() {
        bridge.onConnecting()
        val c = PcmSocketClient()
        try {
            c.connect()
            client = c
            Log.i(TAG, "UDS connected via ${c.connectedVia}, waiting APCM...")
            val hdr = c.readApcmHeader(120_000)
            injectRate = if (hdr.rate > 0) hdr.rate else 48000
            Log.i(TAG, "APCM rate=${hdr.rate} ch=${hdr.channels} kind=${hdr.kind}")
            c.sendSession(true)
            c.sendMute(true)
            // Do NOT use AudioManager.isMicrophoneMute — on this device it also kills
            // Incall_Music TX, so remote hears neither mic nor TTS.
            c.setReadTimeoutMs(200)
            ensureAiLoaded()
            bridge.onStreaming(hdr)
            playGreeting(c)

            var lastLogAt = 0L
            while (sessionWanted && client === c) {
                val frames = c.pollFrames()
                if (frames.isNotEmpty()) {
                    bridge.onFrames(frames)
                    val now = System.currentTimeMillis()
                    if (now - lastLogAt >= 1000) {
                        lastLogAt = now
                        Log.i(
                            TAG,
                            "PCM_DL frames=${bridge.pipeline.totalDlFrames} " +
                                "bytes=${bridge.pipeline.totalDlBytes} " +
                                "mono=${bridge.pipeline.totalMonoSamples} " +
                                "utts=${bridge.pipeline.totalUtterances} state=${bridge.state}",
                        )
                    }
                }
            }

            try {
                c.sendMute(false)
                c.sendFlushUl()
                c.sendSession(false)
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            Log.e(TAG, "UDS bridge error, retry in 2s", e)
            if (sessionWanted) {
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    sessionWanted = false
                }
            }
        } finally {
            try {
                c.close()
            } catch (_: Exception) {
            }
            if (client === c) {
                client = null
            }
            if (!sessionWanted) {
                bridge.onIdle()
            }
        }
    }

    private fun ensureAiLoaded() {
        val layout = ModelPaths.resolve(this)
        Log.i(
            TAG,
            "models asr=${layout.asrReady()} tts=${layout.ttsReady()} " +
                "stt=${layout.asrModel} tts=${layout.ttsModel}",
        )
        if (asr == null) {
            asr = SherpaAsr(layout)
        }
        val cfg = ConfigRepository(this).load()
        val sid = cfg.ttsSpeakerId.coerceAtLeast(0)
        if (tts == null) {
            tts = SherpaTts(layout, speakerId = sid)
        }
        if (callLlm == null) {
            val llmCfg = cfg.llm
            val ds = DeepSeekClient.fromConfig(llmCfg)
            callLlm =
                CallSessionController(llmCfg, ds) { sentence ->
                    val c = client ?: return@CallSessionController
                    try {
                        val audio =
                            tts?.synthesize(sentence, sid = currentTtsSpeakerId())
                                ?: return@CallSessionController
                        injectTts(c, audio.samples, audio.sampleRate)
                    } catch (e: Exception) {
                        Log.e(TAG, "sentence TTS", e)
                    }
                }
            Log.i(TAG, "LLM ready=${callLlm?.ready()} model=${llmCfg.model}")
        }
        callLlm?.reset()
        aiExecutor.execute {
            asr?.ensureLoaded()
            tts?.ensureLoaded()
        }
    }

    private fun currentTtsSpeakerId(): Int =
        ConfigRepository(this).load().ttsSpeakerId.coerceAtLeast(0)

    private fun playGreeting(c: PcmSocketClient) {
        aiExecutor.execute {
            try {
                val audio =
                    tts?.synthesize("你好，我是机主助理，请讲。", sid = currentTtsSpeakerId())
                        ?: return@execute
                injectTts(c, audio.samples, audio.sampleRate)
            } catch (e: Exception) {
                Log.e(TAG, "greeting TTS", e)
            }
        }
    }

    private fun enqueueUtterance(u: Utterance) {
        if (!sessionWanted) return
        if (isListenSuppressed()) {
            Log.i(TAG, "drop utterance (TTS echo guard)")
            return
        }
        if (!aiBusy.compareAndSet(false, true)) {
            Log.w(TAG, "AI busy, drop utterance")
            return
        }
        aiExecutor.execute {
            try {
                if (isListenSuppressed() || !sessionWanted) return@execute
                val text = asr?.transcribe(u.pcm16k).orEmpty()
                Log.i(TAG, "ASR text='$text'")
                if (text.isBlank() || !sessionWanted || isListenSuppressed()) return@execute
                val llm = callLlm
                if (llm != null && llm.ready()) {
                    val full = llm.onUserUtterance(text)
                    if (full.isBlank()) {
                        Log.w(TAG, "LLM empty reply, fallback echo")
                        fallbackEcho(text)
                    }
                } else {
                    fallbackEcho(text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "utterance AI", e)
            } finally {
                aiBusy.set(false)
            }
        }
    }

    private fun fallbackEcho(text: String) {
        val c = client ?: return
        val audio = tts?.synthesize("收到，$text", sid = currentTtsSpeakerId()) ?: return
        injectTts(c, audio.samples, audio.sampleRate)
    }

    private fun isListenSuppressed(): Boolean =
        System.currentTimeMillis() < listenSuppressedUntilMs

    private fun beginTtsGuard() {
        if (ttsDepth.getAndIncrement() == 0) {
            // Hold until last nested TTS ends; large value avoids wall-clock races mid-inject.
            listenSuppressedUntilMs = Long.MAX_VALUE / 4
        }
    }

    private fun endTtsGuard() {
        if (ttsDepth.decrementAndGet() > 0) return
        ttsDepth.set(0)
        // Far-end / line echo of our TTS often lands after UL drain finishes.
        listenSuppressedUntilMs = System.currentTimeMillis() + TTS_ECHO_COOLDOWN_MS
        try {
            bridge.pipeline.resetVad()
        } catch (_: Exception) {
        }
    }

    private fun injectTts(c: PcmSocketClient, samples: FloatArray, sampleRate: Int) {
        // HAL pcmC0D23p incall-music is mono s16 @48k (matches Go tx_inject).
        val pcm =
            PcmFormat.floatMonoToMonoS16(
                samples,
                sampleRate,
                injectRate,
                gain = 4.0f, // same boost as daemon gainS16Mono
            )
        if (pcm.isEmpty()) {
            Log.i(TAG, "TTS injected bytes=0 rateIn=$sampleRate rateOut=$injectRate ch=1")
            return
        }
        beginTtsGuard()
        try {
            // Send in ~20ms chunks; HAL drains at realtime.
            val bytesPerMs = injectRate * 1 /*mono*/ * 2 /*s16*/ / 1000
            val chunk = (bytesPerMs * 20).coerceAtLeast(2)
            var off = 0
            while (off < pcm.size && sessionWanted && client === c) {
                val end = (off + chunk).coerceAtMost(pcm.size)
                val evenEnd = end - ((end - off) % 2)
                if (evenEnd <= off) break
                c.sendPcmUl(pcm.copyOfRange(off, evenEnd))
                off = evenEnd
            }
            Log.i(
                TAG,
                "TTS injected bytes=$off rateIn=$sampleRate rateOut=$injectRate ch=1",
            )
        } finally {
            endTtsGuard()
        }
    }

    private fun releaseAi() {
        try {
            asr?.close()
        } catch (_: Exception) {
        }
        try {
            tts?.close()
        } catch (_: Exception) {
        }
        asr = null
        tts = null
        callLlm?.reset()
        callLlm = null
        aiBusy.set(false)
    }

    private fun teardown() {
        val c = client
        client = null
        try {
            c?.sendMute(false)
            c?.sendFlushUl()
            c?.sendSession(false)
            c?.close()
        } catch (_: Exception) {
        }
        bridge.onIdle()
        releaseAi()
    }

    companion object {
        private const val TAG = "NexusBypass"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_START = "com.nexus.assistant.action.START_SESSION"
        private const val ACTION_END = "com.nexus.assistant.action.END_SESSION"
        private const val ACTION_HUMAN = "com.nexus.assistant.action.HUMAN_MODE"
        /** Ignore DL VAD after each TTS burst (matches Go speaking gate + echo tail). */
        private const val TTS_ECHO_COOLDOWN_MS = 1500L

        fun startSession(context: Context) {
            val i = Intent(context, NexusBypassService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }

        fun endSession(context: Context) {
            context.startService(
                Intent(context, NexusBypassService::class.java).setAction(ACTION_END),
            )
        }

        fun setHumanMode(context: Context) {
            context.startService(
                Intent(context, NexusBypassService::class.java).setAction(ACTION_HUMAN),
            )
        }
    }
}
