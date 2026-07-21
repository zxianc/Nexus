package com.nexus.assistant.telecom

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telecom.Call
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.nexus.assistant.service.BypassCommands

class InCallActivity : Activity() {
    private val callCallback =
        object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
                    finish()
                }
            }
        }

    private var watched: Call? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallActivities.bindInCall(this)
        CallActivities.finishIncoming()

        val call = CallStore.current
        if (call == null ||
            call.state == Call.STATE_DISCONNECTED ||
            call.state == Call.STATE_DISCONNECTING
        ) {
            finish()
            return
        }
        watched = call
        call.registerCallback(callCallback)

        val status =
            TextView(this).apply {
                text = modeLabel()
                textSize = 18f
            }
        val hangup =
            Button(this).apply {
                text = "挂断"
                setOnClickListener {
                    CallStore.current?.disconnect()
                    finish()
                }
            }
        val toggle =
            Button(this).apply {
                text = "切换 AI / 人工"
                setOnClickListener {
                    CallStore.aiMode = !CallStore.aiMode
                    if (CallStore.aiMode) {
                        CallStore.wasAiMode = true
                        BypassCommands.startSession(this@InCallActivity)
                    } else {
                        BypassCommands.setHumanMode(this@InCallActivity)
                    }
                    status.text = modeLabel()
                }
            }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
                addView(status)
                addView(toggle)
                addView(hangup)
            },
        )
    }

    override fun onResume() {
        super.onResume()
        val call = CallStore.current
        if (call == null ||
            call.state == Call.STATE_DISCONNECTED ||
            call.state == Call.STATE_DISCONNECTING
        ) {
            finish()
        }
    }

    override fun onDestroy() {
        watched?.unregisterCallback(callCallback)
        watched = null
        CallActivities.unbindInCall(this)
        super.onDestroy()
    }

    private fun modeLabel(): String =
        if (CallStore.aiMode) "通话中 · AI 代接" else "通话中 · 人工"

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, InCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
