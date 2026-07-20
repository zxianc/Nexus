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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val call = CallStore.current
        val number = call?.details?.handle?.schemeSpecificPart ?: "未知来电"
        val info = TextView(this).apply { text = "来电：$number"; textSize = 22f }
        val answer =
            Button(this).apply {
                text = "接听"
                setOnClickListener {
                    call?.answer(VideoProfile.STATE_AUDIO_ONLY)
                    finish()
                }
            }
        val reject =
            Button(this).apply {
                text = "拒接"
                setOnClickListener {
                    call?.reject(false, null)
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

    companion object {
        fun intent(context: Context, call: Call): Intent =
            Intent(context, IncomingCallActivity::class.java)
    }
}
