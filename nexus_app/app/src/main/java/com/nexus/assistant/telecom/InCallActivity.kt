package com.nexus.assistant.telecom

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.nexus.assistant.service.NexusBypassService

class InCallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        NexusBypassService.startSession(this@InCallActivity)
                    } else {
                        NexusBypassService.setHumanMode(this@InCallActivity)
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

    private fun modeLabel(): String =
        if (CallStore.aiMode) "通话中 · AI 代接" else "通话中 · 人工"

    companion object {
        fun intent(context: Context): Intent = Intent(context, InCallActivity::class.java)
    }
}
