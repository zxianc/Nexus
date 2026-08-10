package com.nexus.tim.bridge.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nexus.tim.bridge.TimApp
import com.nexus.tim.bridge.config.BridgeConfig
import com.nexus.tim.bridge.service.TimForegroundService

class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var apiAuth: CheckBox
    private lateinit var apiToken: EditText
    private lateinit var redisEnabled: CheckBox
    private lateinit var redisHost: EditText
    private lateinit var redisPort: EditText
    private lateinit var redisPassword: EditText
    private lateinit var redisStream: EditText
    private lateinit var webhookEnabled: CheckBox
    private lateinit var webhookUrl: EditText

    private val refresh = object : Runnable {
        override fun run() {
            val s = TimApp.instance.bridgeState
            val cfg = TimApp.instance.currentConfig()
            val hub = TimApp.instance.outbound
            val redisLine = when {
                !cfg.redisReady -> "Redis: off"
                hub.redisLastError != null -> "Redis error: ${hub.redisLastError}"
                hub.redisLastOkAtMs > 0L ->
                    "Redis OK · last push ${((System.currentTimeMillis() - hub.redisLastOkAtMs) / 1000)}s ago"
                else -> "Redis: ready"
            }
            status.text = buildString {
                appendLine("TIM Bridge")
                appendLine("HTTP :8788 · Hook IPC 127.0.0.1:18788")
                appendLine("Hook: ${if (s.hookConnected) "connected" else "disconnected"}")
                appendLine("Recv hook: ${if (s.recvHook) "yes" else "no"}")
                appendLine("Logged in: ${if (s.loggedIn) "yes" else "no"}")
                appendLine("Me: ${s.me.userId.ifEmpty { "—" }} (${s.me.nick})")
                appendLine("TIM: ${s.timVersion ?: "—"} / pin ${s.supportedVersion}")
                appendLine("API auth: ${if (cfg.apiAuthReady) "on" else "off"}")
                appendLine(redisLine)
                appendLine("Webhook: ${if (cfg.webhookReady) "on" else "off"}")
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

        apiAuth = CheckBox(this).apply { text = "Enable API auth" }
        apiToken = edit("API token")
        redisEnabled = CheckBox(this).apply { text = "Enable Redis Stream" }
        redisHost = edit("Redis host")
        redisPort = edit("Redis port", InputType.TYPE_CLASS_NUMBER)
        redisPassword = edit("Redis password (optional)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        redisStream = edit("Stream key")
        webhookEnabled = CheckBox(this).apply { text = "Enable webhook alerts" }
        webhookUrl = edit("Webhook URL")

        val save = Button(this).apply {
            text = "Save settings"
            setOnClickListener { saveSettings() }
        }

        root.addView(status)
        root.addView(start)
        root.addView(stop)
        root.addView(label("— Settings —"))
        root.addView(apiAuth)
        root.addView(apiToken)
        root.addView(redisEnabled)
        root.addView(redisHost)
        root.addView(redisPort)
        root.addView(redisPassword)
        root.addView(redisStream)
        root.addView(webhookEnabled)
        root.addView(webhookUrl)
        root.addView(save)

        setContentView(
            ScrollView(this).apply { addView(root) },
        )
        loadSettings()

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

    private fun loadSettings() {
        val c = TimApp.instance.currentConfig()
        apiAuth.isChecked = c.apiAuthEnabled
        apiToken.setText(c.apiToken)
        redisEnabled.isChecked = c.redisEnabled
        redisHost.setText(c.redisHost)
        redisPort.setText(c.redisPort.toString())
        redisPassword.setText(c.redisPassword)
        redisStream.setText(c.redisStreamKey)
        webhookEnabled.isChecked = c.webhookEnabled
        webhookUrl.setText(c.webhookUrl)
    }

    private fun saveSettings() {
        val port = redisPort.text.toString().toIntOrNull() ?: BridgeConfig.DEFAULT_REDIS_PORT
        TimApp.instance.saveConfig(
            BridgeConfig(
                redisHost = redisHost.text.toString(),
                redisPort = port,
                redisPassword = redisPassword.text.toString(),
                redisStreamKey = redisStream.text.toString()
                    .ifBlank { BridgeConfig.DEFAULT_STREAM_KEY },
                redisEnabled = redisEnabled.isChecked,
                webhookUrl = webhookUrl.text.toString(),
                webhookEnabled = webhookEnabled.isChecked,
                apiAuthEnabled = apiAuth.isChecked,
                apiToken = apiToken.text.toString(),
            ),
        )
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    private fun edit(hint: String, inputType: Int = InputType.TYPE_CLASS_TEXT): EditText =
        EditText(this).apply {
            this.hint = hint
            this.inputType = inputType
            setSingleLine()
        }

    private fun label(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setPadding(0, 32, 0, 8)
        }
}
