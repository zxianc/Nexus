package com.nexus.assistant.telecom

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

class DialerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number =
            EditText(this).apply {
                hint = "电话号码"
                setText(intent?.data?.schemeSpecificPart.orEmpty())
                inputType = android.text.InputType.TYPE_CLASS_PHONE
            }
        val callBtn =
            Button(this).apply {
                text = "呼叫"
                setOnClickListener { placeCall(number.text.toString()) }
            }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
                addView(number)
                addView(callBtn)
            },
        )
        if (intent?.action == Intent.ACTION_CALL) {
            placeCall(intent.data?.schemeSpecificPart.orEmpty())
        }
    }

    private fun placeCall(raw: String) {
        val digits = raw.trim()
        if (digits.isEmpty()) {
            Toast.makeText(this, "请输入号码", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Uri.fromParts("tel", digits, null)
        val tm = getSystemService(TELECOM_SERVICE) as TelecomManager
        try {
            tm.placeCall(uri, null)
        } catch (e: SecurityException) {
            startActivity(Intent(Intent.ACTION_DIAL, uri))
        }
    }
}
