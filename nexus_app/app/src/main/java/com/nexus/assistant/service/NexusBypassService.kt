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
import com.nexus.assistant.uds.PcmSocketClient
import kotlin.concurrent.thread

/**
 * Task 8 skeleton: session-scoped FGS + UDS. Full read/write loops land in Task 8.
 */
class NexusBypassService : Service() {
    @Volatile
    private var sessionWanted = false

    @Volatile
    private var client: PcmSocketClient? = null

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
                teardown()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
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
        thread(name = "NexusUdsBridge") {
            while (sessionWanted) {
                try {
                    val c = PcmSocketClient()
                    c.connect()
                    client = c
                    Log.i(TAG, "UDS connected, waiting APCM...")
                    val hdr = c.readApcmHeader(120_000)
                    Log.i(TAG, "APCM rate=${hdr.rate} ch=${hdr.channels} kind=${hdr.kind}")
                    c.sendSession(true)
                    c.sendMute(true)
                    // Keep connection; Task 8/9 will consume PCM_DL.
                    while (sessionWanted && client === c) {
                        Thread.sleep(200)
                    }
                    try {
                        c.sendMute(false)
                        c.sendFlushUl()
                        c.sendSession(false)
                    } catch (_: Exception) {
                    }
                    c.close()
                    if (client === c) {
                        client = null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "UDS bridge error, retry in 2s", e)
                    try {
                        Thread.sleep(2000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
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
    }

    companion object {
        private const val TAG = "NexusBypass"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_START = "com.nexus.assistant.action.START_SESSION"
        private const val ACTION_END = "com.nexus.assistant.action.END_SESSION"
        private const val ACTION_HUMAN = "com.nexus.assistant.action.HUMAN_MODE"

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
