package com.nexus.assistant.telecom

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telecom.Call
import android.telecom.VideoProfile
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class IncomingCallActivity : Activity() {
    private val callCallback =
        object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                if (state != Call.STATE_RINGING) {
                    finish()
                }
            }
        }

    private var watched: Call? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallActivities.bindIncoming(this)
        val call = CallStore.current
        if (call == null || call.state != Call.STATE_RINGING) {
            finish()
            return
        }
        watched = call
        call.registerCallback(callCallback)

        val number = call.details?.handle?.schemeSpecificPart ?: "未知来电"
        val info =
            TextView(this).apply {
                text = "来电：$number\n（人工接听，请点接听或拒接）"
                textSize = 20f
            }
        val answer =
            Button(this).apply {
                text = "接听"
                setOnClickListener {
                    call.answer(VideoProfile.STATE_AUDIO_ONLY)
                    finish()
                }
            }
        val reject =
            Button(this).apply {
                text = "拒接"
                setOnClickListener {
                    call.reject(false, null)
                    finish()
                }
            }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
                addView(info)
                addView(answer)
                addView(reject)
            },
        )
    }

    override fun onResume() {
        super.onResume()
        val call = CallStore.current
        if (call == null || call.state != Call.STATE_RINGING) {
            finish()
        }
    }

    override fun onDestroy() {
        watched?.unregisterCallback(callCallback)
        watched = null
        CallActivities.unbindIncoming(this)
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context, call: Call): Intent =
            Intent(context, IncomingCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
