package com.nexus.assistant.ui.settings

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.nexus.assistant.ai.LlmKeySync
import com.nexus.assistant.ai.ModelPaths
import com.nexus.assistant.ai.ModelSync
import com.nexus.assistant.config.ConfigRepository
import com.nexus.assistant.config.SimCatalog
import com.nexus.assistant.config.SimConfig
import com.nexus.assistant.config.SimPolicy
import com.nexus.assistant.archive.CallFinalizer
import com.nexus.assistant.notify.SmsWatcher
import com.nexus.assistant.telecom.DialerTakeover
import kotlin.concurrent.thread

class SettingsActivity : Activity() {
    private lateinit var repo: ConfigRepository
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var takeoverStatus: TextView
    private lateinit var modelStatus: TextView
    private lateinit var llmStatus: TextView
    private var pendingTakeoverEnable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ConfigRepository(this)
        ensurePhonePermissions()
        SmsWatcher.ensureRegistered(this)
        status = TextView(this).apply { textSize = 15f }
        takeoverStatus = TextView(this).apply { textSize = 14f }
        modelStatus = TextView(this).apply { textSize = 14f }
        llmStatus = TextView(this).apply { textSize = 14f }
        root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
            }
        setContentView(root)
        rebuildUi()
    }

    override fun onResume() {
        super.onResume()
        rebuildUi()
    }

    private fun rebuildUi() {
        val cfg = repo.refreshSimMetadata()
        root.removeAllViews()
        root.addView(status)
        refreshStatus(cfg)

        root.addView(takeoverStatus)
        refreshTakeoverStatus()
        val takeoverOn = cfg.dialerTakeover
        root.addView(
            Button(this).apply {
                text = if (takeoverOn) "关闭 Nexus 接管 → 交回系统电话" else "开启 Nexus 接管"
                setOnClickListener { toggleTakeover(!takeoverOn) }
            },
        )
        root.addView(
            Button(this).apply {
                text = "刷新卡信息"
                setOnClickListener {
                    rebuildUi()
                    Toast.makeText(this@SettingsActivity, "已刷新", Toast.LENGTH_SHORT).show()
                }
            },
        )
        root.addView(modelStatus)
        refreshModelStatus()
        root.addView(
            Button(this).apply {
                text = "同步 Magisk 模型"
                setOnClickListener { syncModels() }
            },
        )
        root.addView(llmStatus)
        refreshLlmStatus()
        root.addView(
            Button(this).apply {
                text = "同步 Magisk API Key"
                setOnClickListener { syncLlmKey() }
            },
        )

        cfg.sims.sortedBy { it.slot }.forEach { sim ->
            root.addView(simHeader(sim))
            root.addView(
                Button(this).apply {
                    text = "卡${sim.slot + 1} 策略 → AI"
                    setOnClickListener { setPolicy(sim.slot, SimPolicy.AI) }
                },
            )
            root.addView(
                Button(this).apply {
                    text = "卡${sim.slot + 1} 策略 → 人工"
                    setOnClickListener { setPolicy(sim.slot, SimPolicy.HUMAN) }
                },
            )
            root.addView(
                Button(this).apply {
                    text = "卡${sim.slot + 1} 策略 → 拒接"
                    setOnClickListener { setPolicy(sim.slot, SimPolicy.REJECT) }
                },
            )
        }
    }

    private fun simHeader(sim: SimConfig): TextView {
        val carrier = sim.carrier.ifBlank { "未知运营商" }
        val number = sim.number.ifBlank { "号码未知" }
        return TextView(this).apply {
            textSize = 16f
            setPadding(0, 36, 0, 12)
            text =
                buildString {
                    appendLine("卡${sim.slot + 1}")
                    appendLine("运营商：$carrier")
                    appendLine("号码：$number")
                    append("当前策略：${SimCatalog.policyLabel(sim.policy)}")
                }
        }
    }

    private fun setPolicy(slot: Int, policy: SimPolicy) {
        val cfg = repo.load()
        val sims = cfg.sims.map { if (it.slot == slot) it.copy(policy = policy) else it }
        repo.save(cfg.copy(sims = sims))
        rebuildUi()
        Toast.makeText(this, "卡${slot + 1} 已设为 ${SimCatalog.policyLabel(policy)}", Toast.LENGTH_SHORT).show()
    }

    private fun refreshStatus(cfg: com.nexus.assistant.config.NexusConfig) {
        val dialer =
            if (isDefaultDialer()) {
                "已是默认电话"
            } else {
                "未设为默认电话（双卡策略不会生效）"
            }
        status.text =
            buildString {
                appendLine(dialer)
                appendLine("已识别 ${cfg.sims.size} 张卡")
            }
    }

    private fun refreshTakeoverStatus() {
        takeoverStatus.text = DialerTakeover.probe(this).message
    }

    private fun toggleTakeover(enable: Boolean) {
        if (!enable) {
            pendingTakeoverEnable = false
            applyTakeover(false, tip = "正在交回系统电话…")
            return
        }
        // ON：先等用户确认默认电话，确认后再切组件；取消则回滚，避免半残。
        if (isDefaultDialer()) {
            applyTakeover(true, tip = "正在开启 Nexus 接管…")
            return
        }
        pendingTakeoverEnable = true
        Toast.makeText(this, "正在准备默认电话确认…", Toast.LENGTH_SHORT).show()
        // 交回时会 disable Nexus InCall；不先 enable 的话系统不弹 ROLE 确认窗。
        thread(name = "DialerTakeoverPrepare") {
            val prep = DialerTakeover.prepareRoleRequest(this)
            runOnUiThread {
                if (prep.isFailure) {
                    pendingTakeoverEnable = false
                    Toast.makeText(
                        this,
                        "无法申请默认电话：${prep.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                    rollbackTakeover()
                    return@runOnUiThread
                }
                Toast.makeText(this, "请确认将 Nexus 设为默认电话…", Toast.LENGTH_SHORT).show()
                requestDefaultDialer()
            }
        }
    }

    private fun applyTakeover(enable: Boolean, tip: String) {
        Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
        thread(name = "DialerTakeover") {
            val result = DialerTakeover.setEnabled(this, enable)
            runOnUiThread {
                refreshTakeoverStatus()
                rebuildUi()
                Toast.makeText(
                    this,
                    result.fold(
                        onSuccess = { it },
                        onFailure = { "切换失败：${it.message}" },
                    ),
                    Toast.LENGTH_LONG,
                ).show()
                // Role 弹窗有时会顺带拉起拨号盘；开启成功后拉回 Settings。
                if (enable && result.isSuccess) {
                    bringSettingsToFront()
                }
            }
        }
    }

    private fun bringSettingsToFront() {
        startActivity(
            Intent(this, SettingsActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
    }

    /** 用户未确认默认电话 → 强制交回系统电话，避免 Dialer ICS 被禁用却无 UI。 */
    private fun rollbackTakeover() {
        pendingTakeoverEnable = false
        Toast.makeText(this, "未选择 Nexus，正在恢复系统电话…", Toast.LENGTH_SHORT).show()
        thread(name = "DialerTakeoverRollback") {
            val result = DialerTakeover.setEnabled(this, false)
            runOnUiThread {
                refreshTakeoverStatus()
                rebuildUi()
                Toast.makeText(
                    this,
                    result.fold(
                        onSuccess = { "已取消接管，已恢复系统电话" },
                        onFailure = { "回滚失败：${it.message}，请再点「交回系统电话」" },
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun refreshModelStatus() {
        val layout = ModelPaths.resolve(this)
        modelStatus.text =
            buildString {
                appendLine("模型 ASR：${if (layout.asrReady()) "就绪" else "缺失"}")
                appendLine("模型 TTS：${if (layout.ttsReady()) "就绪" else "缺失"}")
                appendLine("路径：${layout.senseVoiceDir.parentFile?.absolutePath ?: "?"}")
                val miss = layout.missing()
                if (miss.isNotEmpty()) {
                    append("缺失文件：${miss.size} 个（需 root 同步）")
                }
            }
    }

    private fun syncModels() {
        Toast.makeText(this, "正在同步模型…", Toast.LENGTH_SHORT).show()
        thread(name = "ModelSync") {
            val result = ModelSync.syncFromMagisk(this)
            runOnUiThread {
                refreshModelStatus()
                Toast.makeText(
                    this,
                    result.fold(
                        onSuccess = { it },
                        onFailure = { "同步失败：${it.message}" },
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun refreshLlmStatus() {
        val cfg = repo.load()
        val llm = cfg.llm
        val n = cfg.notify
        llmStatus.text =
            buildString {
                appendLine("LLM：${if (llm.enabled) "启用" else "关闭"} / ${llm.model}")
                appendLine(
                    if (llm.apiKey.isBlank()) {
                        "API Key：未配置"
                    } else {
                        "API Key：已配置（…${llm.apiKey.takeLast(4)}）"
                    },
                )
                appendLine(
                    "企微：${if (n.enabled && n.callEnabled) "通话通知开" else "通话通知关"} / " +
                        "短信${if (n.enabled && n.smsEnabled) "开" else "关"}",
                )
                appendLine(
                    if (n.webhookUrl.isBlank()) {
                        "Webhook：未配置"
                    } else {
                        "Webhook：已配置"
                    },
                )
                append("存档：${CallFinalizer.archiveRoot(this@SettingsActivity).absolutePath}")
            }
    }

    private fun syncLlmKey() {
        Toast.makeText(this, "正在同步 API Key…", Toast.LENGTH_SHORT).show()
        thread(name = "LlmKeySync") {
            val result = LlmKeySync.syncFromMagisk(this)
            runOnUiThread {
                refreshLlmStatus()
                Toast.makeText(
                    this,
                    result.fold(
                        onSuccess = { it },
                        onFailure = { "同步失败：${it.message}" },
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun ensurePhonePermissions() {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.READ_SMS)
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), REQ_PHONE)
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
                try {
                    startActivityForResult(
                        rm.createRequestRoleIntent(RoleManager.ROLE_DIALER),
                        REQ_ROLE_DIALER,
                    )
                    return
                } catch (e: Exception) {
                    // fall through to legacy dialer change intent
                }
            }
        }
        // 兼容：部分系统 Role 弹窗不出现时，用旧版「更改默认电话」Intent。
        try {
            val legacy =
                Intent(android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(
                    android.telecom.TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                    packageName,
                )
            startActivityForResult(legacy, REQ_ROLE_DIALER)
            return
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PHONE) {
            rebuildUi()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_ROLE_DIALER) {
            rebuildUi()
            return
        }
        val wantedEnable = pendingTakeoverEnable
        pendingTakeoverEnable = false
        if (!wantedEnable) {
            rebuildUi()
            return
        }
        if (resultCode == RESULT_OK || isDefaultDialer()) {
            applyTakeover(true, tip = "已确认默认电话，正在开启 Nexus 接管…")
            return
        }
        // 部分机型角色已写入但先回传 CANCELED；短暂等待后再决定是否回滚。
        takeoverStatus.postDelayed({
            if (isDefaultDialer()) {
                applyTakeover(true, tip = "已确认默认电话，正在开启 Nexus 接管…")
            } else {
                rollbackTakeover()
            }
        }, 400)
    }

    companion object {
        private const val REQ_PHONE = 2001
        private const val REQ_ROLE_DIALER = 1001
    }
}
