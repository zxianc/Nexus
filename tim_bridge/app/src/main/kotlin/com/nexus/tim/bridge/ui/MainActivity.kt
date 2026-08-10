package com.nexus.tim.bridge.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.nexus.tim.bridge.TimApp
import com.nexus.tim.bridge.service.TimForegroundService

class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView

    private val refresh = object : Runnable {
        override fun run() {
            val s = TimApp.instance.bridgeState
            status.text = buildString {
                appendLine("TIM Bridge")
                appendLine("HTTP :8788 · Hook IPC 127.0.0.1:18788")
                appendLine("Hook: ${if (s.hookConnected) "connected" else "disconnected"}")
                appendLine("Logged in: ${if (s.loggedIn) "yes" else "no"}")
                appendLine("Me: ${s.me.userId.ifEmpty { "—" }} (${s.me.nick})")
                appendLine("TIM: ${s.timVersion ?: "—"} / pin ${s.supportedVersion}")
            }
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        status = TextView(this)
        val start = Button(this).apply {
            text = "Start service"
            setOnClickListener {
                startForegroundService(Intent(this@MainActivity, TimForegroundService::class.java))
            }
        }
        val stop = Button(this).apply {
            text = "Stop service"
            setOnClickListener {
                stopService(Intent(this@MainActivity, TimForegroundService::class.java))
            }
        }
        root.addView(status)
        root.addView(start)
        root.addView(stop)
        setContentView(root)

        if (intent?.getBooleanExtra("auto_start", false) == true) {
            startForegroundService(Intent(this, TimForegroundService::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}
