package com.nexus.wechat.bridge.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.nexus.wechat.bridge.BuildConfig
import com.nexus.wechat.bridge.fake.FakeHookClient
import com.nexus.wechat.bridge.service.BridgeForegroundService

/** Minimal launcher without XML layouts. */
class MainActivity : Activity() {
    private var fakeHook: FakeHookClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        val status = TextView(this).apply {
            text = "WeChat Bridge\nStart service to listen on :8787"
        }
        fun startBridge() {
            startForegroundService(Intent(this@MainActivity, BridgeForegroundService::class.java))
            status.text = "Service starting… HTTP :8787 + UDS @nexus_wechat"
        }
        fun startFake() {
            if (!BuildConfig.DEBUG) return
            if (fakeHook == null) {
                fakeHook = FakeHookClient().also { it.start() }
                status.text = "FakeHook connecting…"
            }
        }
        val start = Button(this).apply {
            text = "Start bridge"
            setOnClickListener { startBridge() }
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
        if (BuildConfig.DEBUG) {
            val fake = Button(this).apply {
                text = "Start FakeHook"
                setOnClickListener { startFake() }
            }
            root.addView(fake)
        }
        setContentView(root)
        // adb: am start -n …/.ui.MainActivity --ez auto_start true --ez start_fake true
        if (intent?.getBooleanExtra("auto_start", false) == true) {
            startBridge()
            if (intent.getBooleanExtra("start_fake", false)) {
                root.postDelayed({ startFake() }, 800)
            }
        }
    }

    override fun onDestroy() {
        fakeHook?.stop()
        fakeHook = null
        super.onDestroy()
    }
}
