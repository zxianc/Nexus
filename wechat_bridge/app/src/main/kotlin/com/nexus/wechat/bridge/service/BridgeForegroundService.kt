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

class BridgeForegroundService : Service() {
    private var httpServer: BridgeHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification())
        startHttp()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopHttp()
        super.onDestroy()
    }

    private fun startHttp() {
        val app = BridgeApp.instance
        val router = BridgeHttpRouter(app.bridgeState, app.eventStore)
        val server = BridgeHttpServer(router)
        try {
            server.start()
            httpServer = server
        } catch (e: Exception) {
            Log.e(TAG, "HTTP start failed", e)
        }
    }

    private fun stopHttp() {
        try {
            httpServer?.stop()
        } catch (_: Exception) {
        }
        httpServer = null
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
