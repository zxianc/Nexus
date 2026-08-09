package com.nexus.wechat.bridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nexus.wechat.bridge.BridgeApp
import com.nexus.wechat.bridge.R
import com.nexus.wechat.bridge.http.BridgeHttpRouter
import com.nexus.wechat.bridge.http.BridgeHttpServer
import com.nexus.wechat.bridge.store.SharedStaging
import com.nexus.wechat.bridge.uds.HookUdsServer

class BridgeForegroundService : Service() {
    private var httpServer: BridgeHttpServer? = null
    private var udsServer: HookUdsServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification())
        startServers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
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
        )
        val server = BridgeHttpServer(router)
        try {
            server.start()
            httpServer = server
        } catch (e: Exception) {
            Log.e(TAG, "HTTP start failed", e)
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

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "WeChatBridgeSvc"
        private const val CHANNEL_ID = "wechat_bridge"
        private const val NOTIF_ID = 8787
    }
}
