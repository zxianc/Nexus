package com.nexus.phone.nexus.ui

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.nexus.phone.nexus.config.ConfigRepository
import com.nexus.phone.nexus.config.SimCatalog
import com.nexus.phone.nexus.config.SimPolicy
import com.nexus.phone.nexus.telecom.DialerTakeover

/**
 * Minimal Nexus AI settings (M1). Full settings port is Task 10.
 */
class NexusSettingsActivity : Activity() {
    private lateinit var repo: ConfigRepository
    private lateinit var status: TextView
    private lateinit var simsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ConfigRepository(this)

        status = TextView(this).apply { textSize = 15f }
        simsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 96)
                addView(status)
                addView(
                    Button(this@NexusSettingsActivity).apply {
                        text = "开启 Nexus 策略"
                        setOnClickListener { toggleTakeover(true) }
                    },
                )
                addView(
                    Button(this@NexusSettingsActivity).apply {
                        text = "关闭 Nexus 策略"
                        setOnClickListener { toggleTakeover(false) }
                    },
                )
                addView(
                    Button(this@NexusSettingsActivity).apply {
                        text = "申请默认电话"
                        setOnClickListener {
                            val intent = DialerTakeover.requestRoleUi(this@NexusSettingsActivity)
                            if (intent != null) {
                                startActivity(intent)
                            } else {
                                Toast.makeText(
                                    this@NexusSettingsActivity,
                                    "无法打开默认电话设置",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                )
                addView(
                    Button(this@NexusSettingsActivity).apply {
                        text = "刷新卡信息"
                        setOnClickListener {
                            rebuild(repo.refreshSimMetadata())
                            Toast.makeText(this@NexusSettingsActivity, "已刷新", Toast.LENGTH_SHORT)
                                .show()
                        }
                    },
                )
                addView(
                    TextView(this@NexusSettingsActivity).apply {
                        text = "双卡策略"
                        textSize = 18f
                        setPadding(0, 40, 0, 12)
                    },
                )
                addView(simsContainer)
            }

        setContentView(
            ScrollView(this).apply {
                addView(
                    root,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )
        rebuild(repo.refreshSimMetadata())
    }

    override fun onResume() {
        super.onResume()
        if (::repo.isInitialized) {
            rebuild(repo.load())
        }
    }

    private fun toggleTakeover(enable: Boolean) {
        val result = DialerTakeover.setEnabled(this, enable)
        result.fold(
            onSuccess = {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
            },
            onFailure = {
                Toast.makeText(this, it.message ?: "失败", Toast.LENGTH_LONG).show()
                if (enable) {
                    DialerTakeover.requestRoleUi(this)?.let { startActivity(it) }
                }
            },
        )
        rebuild(repo.load())
    }

    private fun rebuild(cfg: com.nexus.phone.nexus.config.NexusConfig) {
        status.text = DialerTakeover.probe(this).message + "\n已识别 ${cfg.sims.size} 张卡"
        simsContainer.removeAllViews()
        cfg.sims.sortedBy { it.slot }.forEach { sim ->
            simsContainer.addView(
                TextView(this).apply {
                    textSize = 15f
                    setPadding(0, 24, 0, 8)
                    text =
                        buildString {
                            appendLine("卡${sim.slot + 1}")
                            appendLine("运营商：${sim.carrier.ifBlank { "未知" }}")
                            appendLine("号码：${sim.number.ifBlank { "号码未知" }}")
                            append("当前策略：${SimCatalog.policyLabel(sim.policy)}")
                        }
                },
            )
            for (policy in listOf(SimPolicy.AI, SimPolicy.HUMAN, SimPolicy.REJECT)) {
                simsContainer.addView(
                    Button(this).apply {
                        text = "卡${sim.slot + 1} → ${SimCatalog.policyLabel(policy)}"
                        setOnClickListener { setPolicy(sim.slot, policy) }
                    },
                )
            }
        }
    }

    private fun setPolicy(slot: Int, policy: SimPolicy) {
        val cfg = repo.load()
        val sims = cfg.sims.map { if (it.slot == slot) it.copy(policy = policy) else it }
        repo.save(cfg.copy(sims = sims))
        rebuild(repo.load())
        Toast.makeText(
            this,
            "卡${slot + 1} 已设为 ${SimCatalog.policyLabel(policy)}",
            Toast.LENGTH_SHORT,
        ).show()
    }
}
