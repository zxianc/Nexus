package com.nexus.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.nexus.assistant.telecom.DialerActivity
import com.nexus.assistant.ui.SmokeActivity
import com.nexus.assistant.ui.settings.SettingsActivity

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
            }
        root.addView(
            TextView(this).apply {
                text = "Nexus Assistant MVP"
                textSize = 20f
            },
        )
        root.addView(
            button("设置 / 默认电话 / 双卡策略") {
                startActivity(Intent(this, SettingsActivity::class.java))
            },
        )
        root.addView(
            button("拨号") {
                startActivity(Intent(this, DialerActivity::class.java))
            },
        )
        root.addView(
            button("UDS Smoke (G1)") {
                startActivity(Intent(this, SmokeActivity::class.java))
            },
        )
        setContentView(root)
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }
}
