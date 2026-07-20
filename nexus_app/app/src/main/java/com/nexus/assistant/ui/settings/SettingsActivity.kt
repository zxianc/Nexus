package com.nexus.assistant.ui.settings

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.nexus.assistant.config.ConfigRepository
import com.nexus.assistant.config.SimPolicy

class SettingsActivity : Activity() {
    private lateinit var repo: ConfigRepository
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ConfigRepository(this)
        status = TextView(this)
        refreshStatus()
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
            }
        root.addView(status)
        root.addView(
            Button(this).apply {
                text = "设为默认电话应用"
                setOnClickListener { requestDefaultDialer() }
            },
        )
        root.addView(
            Button(this).apply {
                text = "卡1 策略 → AI"
                setOnClickListener { setPolicy(0, SimPolicy.AI) }
            },
        )
        root.addView(
            Button(this).apply {
                text = "卡1 策略 → 人工"
                setOnClickListener { setPolicy(0, SimPolicy.HUMAN) }
            },
        )
        root.addView(
            Button(this).apply {
                text = "卡2 策略 → AI"
                setOnClickListener { setPolicy(1, SimPolicy.AI) }
            },
        )
        root.addView(
            Button(this).apply {
                text = "卡2 策略 → 人工"
                setOnClickListener { setPolicy(1, SimPolicy.HUMAN) }
            },
        )
        setContentView(root)
    }

    private fun setPolicy(slot: Int, policy: SimPolicy) {
        val cfg = repo.load()
        val sims = cfg.sims.map { if (it.slot == slot) it.copy(policy = policy) else it }
        repo.save(cfg.copy(sims = sims))
        refreshStatus()
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
    }

    private fun refreshStatus() {
        val cfg = repo.load()
        val dialer =
            if (isDefaultDialer()) "已是默认电话" else "未设为默认电话（AI 接听不可用）"
        status.text =
            buildString {
                appendLine(dialer)
                cfg.sims.forEach {
                    appendLine("卡${it.slot + 1}: ${it.policy.toWire()}")
                }
            }
    }

    private fun isDefaultDialer(): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            return rm?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        }
        return false
    }

    private fun requestDefaultDialer() {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), 1001)
                return
            }
        }
        startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        refreshStatus()
    }
}
