package com.nexus.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.nexus.assistant.ui.SmokeActivity

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
            Button(this).apply {
                text = "UDS Smoke (G1)"
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, SmokeActivity::class.java))
                }
            },
        )
        setContentView(root)
    }
}
