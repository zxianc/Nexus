package com.nexus.wechat.bridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nexus.wechat.bridge.BridgeApp
import com.nexus.wechat.bridge.R
import com.nexus.wechat.bridge.http.BridgeHttpRouter
import com.nexus.wechat.bridge.http.BridgeHttpServer
import com.nexus.wechat.bridge.store.SharedStaging
import com.nexus.wechat.bridge.ui.MainActivity
import com.nexus.wechat.bridge.uds.HookUdsServer

class BridgeForegroundService : Service() {
    private var httpServer: BridgeHttpServer? = null
    private var udsServer: HookUdsServer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastNotifText: String? = null

    private val statusListener: () -> Unit = {
        mainHandler.post { refreshNotification() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        BridgeApp.instance.hookSession.onStatusChanged = statusListener
        startForeground(NOTIF_ID, buildNotification())
        startServers()
        refreshNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        val session = BridgeApp.instance.hookSession
        if (session.onStatusChanged === statusListener) {
            session.onStatusChanged = null
        }
        stopServers()
        super.onDestroy()
    }

    private fun startServers() {
        val app = BridgeApp.instance
        SharedStaging.ensureDirs()
        udsServer = HookUdsServer(app.hookSession).also {
            try {
                it.start()
            } catch (e: Exception) {
                Log.e(TAG, "UDS start failed", e)
                app.outbound.alert("uds_start_failed", e.message ?: "UDS start failed")
            }
        }
        val router = BridgeHttpRouter(
            state = app.bridgeState,
            eventStore = app.eventStore,
            mediaStore = app.mediaStore,
            sendText = { chatId, text, ats -> app.sendTextHttp(chatId, text, ats) },
            sendMedia = { chatId, kind, path, name, mediaId, dataB64, original ->
                app.sendMediaHttp(chatId, kind, path, name, mediaId, dataB64, original)
            },
            authEnabled = { app.currentConfig().apiAuthEnabled },
            authToken = { app.currentConfig().apiToken },
        )
        val server = BridgeHttpServer(router)
        try {
            server.start()
            httpServer = server
        } catch (e: Exception) {
            Log.e(TAG, "HTTP start failed", e)
            app.outbound.alert("http_start_failed", e.message ?: "HTTP start failed")
        }
    }

    private fun stopServers() {
        try {
            httpServer?.stop()
        } catch (_: Exception) {
        }
        httpServer = null
        try {
            udsServer?.stop()
        } catch (_: Exception) {
        }
        udsServer = null
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun refreshNotification() {
        val text = statusText()
        if (text == lastNotifText) return
        lastNotifText = text
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun statusText(): String {
        val state = BridgeApp.instance.bridgeState
        val hook = if (state.hookConnected) "connected" else "disconnected"
        val login = if (state.loggedIn) "yes" else "no"
        val me = state.me.userId.ifEmpty { "—" }
        return "Hook: $hook · logged in: $login · $me"
    }

    private fun buildNotification(text: String = statusText()): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "WeChatBridgeSvc"
        private const val CHANNEL_ID = "wechat_bridge"
        private const val NOTIF_ID = 8787
    }
}
