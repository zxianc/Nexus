package com.nexus.wechat.bridge.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.nexus.wechat.bridge.service.BridgeForegroundService

/** Minimal launcher without XML layouts. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        val status = TextView(this).apply {
            text = "WeChat Bridge\nStart service to listen on :8787"
        }
        val start = Button(this).apply {
            text = "Start bridge"
            setOnClickListener {
                startForegroundService(Intent(this@MainActivity, BridgeForegroundService::class.java))
                status.text = "Service starting… HTTP :8787"
            }
        }
        val stop = Button(this).apply {
            text = "Stop bridge"
            setOnClickListener {
                stopService(Intent(this@MainActivity, BridgeForegroundService::class.java))
                status.text = "Service stopped"
            }
        }
        root.addView(status)
        root.addView(start)
        root.addView(stop)
        setContentView(root)
    }
}
