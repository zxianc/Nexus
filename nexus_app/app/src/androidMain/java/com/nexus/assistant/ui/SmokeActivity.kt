package com.nexus.assistant.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.nexus.assistant.uds.PcmSocketClient
import kotlin.concurrent.thread

/**
 * Debug smoke: connect → wait APCM (during a call) → mute + silent UL frame.
 * androidMain only; wire into Manifest when switching to AGP.
 */
class SmokeActivity : Activity() {
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logView = TextView(this)
        val btn = Button(this).apply { text = "Connect + silent UL" }
        btn.setOnClickListener { runSmoke() }
        root.addView(btn)
        root.addView(ScrollView(this).apply { addView(logView) })
        setContentView(root)
    }

    private fun append(msg: String) {
        runOnUiThread { logView.append(msg + "\n") }
    }

    private fun runSmoke() {
        thread {
            val client = PcmSocketClient()
            try {
                append("connecting...")
                client.connect()
                append("connected via ${client.connectedVia}")
                append("waiting APCM (start an AI/test call if needed)...")
                val hdr = client.readApcmHeader(60_000)
                append("APCM rate=${hdr.rate} ch=${hdr.channels} bits=${hdr.bits} kind=${hdr.kind}")
                client.sendSession(true)
                client.sendMute(true)
                val bytesPerMs = hdr.rate * hdr.channels * 2 / 1000
                val silence = ByteArray(bytesPerMs * 20) // 20ms
                client.sendPcmUl(silence)
                client.sendMute(false)
                client.sendSession(false)
                append("ok: sent mute+silent UL+unmute")
            } catch (e: Exception) {
                append("error: ${e.message}")
            } finally {
                client.close()
            }
        }
    }
}
