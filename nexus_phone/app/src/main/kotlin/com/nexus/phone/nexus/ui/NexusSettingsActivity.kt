package com.nexus.phone.nexus.ui

import android.app.Activity
import android.text.InputType
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.nexus.phone.nexus.archive.CallFinalizer
import com.nexus.phone.nexus.config.ConfigRepository
import com.nexus.phone.nexus.config.DEFAULT_GREETING_TEXT
import com.nexus.phone.nexus.config.DEFAULT_SYSTEM_PROMPT
import com.nexus.phone.nexus.config.LlmConfig
import com.nexus.phone.nexus.config.NexusConfig
import com.nexus.phone.nexus.config.NotifyConfig
import com.nexus.phone.nexus.config.SimCatalog
import com.nexus.phone.nexus.config.SimPolicy
import com.nexus.phone.nexus.notify.SmsWatcher
import com.nexus.phone.nexus.telecom.DialerTakeover

/** Nexus AI / notify / SIM settings (M4 — functional parity with former SettingsActivity). */
class NexusSettingsActivity : Activity() {
    private lateinit var repo: ConfigRepository
    private lateinit var status: TextView
    private lateinit var simsContainer: LinearLayout
    private lateinit var archivePathText: TextView
    private lateinit var greetingEnabled: CheckBox
    private lateinit var greetingTextEdit: EditText
    private lateinit var llmEnabled: CheckBox
    private lateinit var llmModelEdit: EditText
    private lateinit var llmApiKeyEdit: EditText
    private lateinit var llmBaseUrlEdit: EditText
    private lateinit var llmMaxMsgsEdit: EditText
    private lateinit var llmPromptEdit: EditText
    private lateinit var ttsSpeakerEdit: EditText
    private lateinit var notifyEnabled: CheckBox
    private lateinit var notifyWebhookEdit: EditText
    private lateinit var notifySmsEnabled: CheckBox
    private lateinit var notifyCallEnabled: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ConfigRepository(this)
        SmsWatcher.ensureRegistered(this)

        status = TextView(this).apply { textSize = 15f }
        simsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        archivePathText = TextView(this).apply { textSize = 13f }
        greetingEnabled = CheckBox(this).apply { text = "启用开场白 TTS" }
        greetingTextEdit = multiEdit(2)
        llmEnabled = CheckBox(this).apply { text = "启用 LLM" }
        llmModelEdit = singleEdit()
        llmApiKeyEdit =
            singleEdit().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        llmBaseUrlEdit = singleEdit()
        llmMaxMsgsEdit = singleEdit().apply { inputType = InputType.TYPE_CLASS_NUMBER }
        llmPromptEdit = multiEdit(6)
        ttsSpeakerEdit = singleEdit().apply { inputType = InputType.TYPE_CLASS_NUMBER }
        notifyEnabled = CheckBox(this).apply { text = "启用 Webhook 通知总开关" }
        notifyWebhookEdit = singleEdit()
        notifySmsEnabled = CheckBox(this).apply { text = "短信 Webhook 通知" }
        notifyCallEnabled = CheckBox(this).apply { text = "通话 Webhook 通知" }

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 96)
                addView(status)
                addView(btn("开启 Nexus 策略") { toggleTakeover(true) })
                addView(btn("关闭 Nexus 策略") { toggleTakeover(false) })
                addView(
                    btn("申请默认电话") {
                        DialerTakeover.requestRoleUi(this@NexusSettingsActivity)?.let { startActivity(it) }
                            ?: Toast.makeText(this@NexusSettingsActivity, "无法打开", Toast.LENGTH_SHORT).show()
                    },
                )
                addView(
                    btn("刷新卡信息") {
                        rebuildSims(repo.refreshSimMetadata())
                        Toast.makeText(this@NexusSettingsActivity, "已刷新", Toast.LENGTH_SHORT).show()
                    },
                )
                addView(section("双卡策略"))
                addView(simsContainer)
                addView(section("开场白"))
                addView(greetingEnabled)
                addView(label("开场白文案"))
                addView(greetingTextEdit)
                addView(section("LLM"))
                addView(llmEnabled)
                addView(label("模型名"))
                addView(llmModelEdit)
                addView(label("API Key"))
                addView(llmApiKeyEdit)
                addView(label("Base URL"))
                addView(llmBaseUrlEdit)
                addView(label("max_msgs"))
                addView(llmMaxMsgsEdit)
                addView(label("系统提示词"))
                addView(llmPromptEdit)
                addView(label("TTS Speaker ID"))
                addView(ttsSpeakerEdit)
                addView(section("Webhook"))
                addView(notifyEnabled)
                addView(label("Webhook URL"))
                addView(notifyWebhookEdit)
                addView(notifySmsEnabled)
                addView(notifyCallEnabled)
                addView(archivePathText)
                addView(btn("保存配置") { saveEditable() })
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
        bind(repo.refreshSimMetadata())
    }

    override fun onResume() {
        super.onResume()
        if (::repo.isInitialized) {
            status.text = DialerTakeover.probe(this).message
            rebuildSims(repo.load())
        }
    }

    private fun bind(cfg: NexusConfig) {
        status.text = DialerTakeover.probe(this).message + "\n已识别 ${cfg.sims.size} 张卡"
        greetingEnabled.isChecked = cfg.greetingEnabled
        greetingTextEdit.setText(cfg.greetingText.ifBlank { DEFAULT_GREETING_TEXT })
        llmEnabled.isChecked = cfg.llm.enabled
        llmModelEdit.setText(cfg.llm.model)
        llmApiKeyEdit.setText(cfg.llm.apiKey)
        llmBaseUrlEdit.setText(cfg.llm.baseUrl)
        llmMaxMsgsEdit.setText(cfg.llm.maxMsgs.toString())
        llmPromptEdit.setText(cfg.llm.systemPrompt)
        ttsSpeakerEdit.setText(cfg.ttsSpeakerId.toString())
        notifyEnabled.isChecked = cfg.notify.enabled
        notifyWebhookEdit.setText(cfg.notify.webhookUrl)
        notifySmsEnabled.isChecked = cfg.notify.smsEnabled
        notifyCallEnabled.isChecked = cfg.notify.callEnabled
        archivePathText.text = "存档目录：${CallFinalizer.archiveRoot(this).absolutePath}"
        rebuildSims(cfg)
    }

    private fun saveEditable() {
        val maxMsgs =
            llmMaxMsgsEdit.text.toString().trim().toIntOrNull()
                ?: run {
                    Toast.makeText(this, "max_msgs 必须是数字", Toast.LENGTH_SHORT).show()
                    return
                }
        if (maxMsgs < 2) {
            Toast.makeText(this, "max_msgs 至少为 2", Toast.LENGTH_SHORT).show()
            return
        }
        val speakerId =
            ttsSpeakerEdit.text.toString().trim().toIntOrNull()
                ?: run {
                    Toast.makeText(this, "Speaker ID 必须是数字", Toast.LENGTH_SHORT).show()
                    return
                }
        val cur = repo.load()
        repo.save(
            cur.copy(
                ttsSpeakerId = speakerId.coerceAtLeast(0),
                greetingEnabled = greetingEnabled.isChecked,
                greetingText = greetingTextEdit.text.toString().trim().ifBlank { DEFAULT_GREETING_TEXT },
                llm =
                    LlmConfig(
                        enabled = llmEnabled.isChecked,
                        model = llmModelEdit.text.toString().trim().ifEmpty { LlmConfig().model },
                        apiKey = llmApiKeyEdit.text.toString().trim(),
                        baseUrl = llmBaseUrlEdit.text.toString().trim().ifEmpty { LlmConfig().baseUrl },
                        maxMsgs = maxMsgs,
                        systemPrompt = llmPromptEdit.text.toString().ifBlank { DEFAULT_SYSTEM_PROMPT },
                    ),
                notify =
                    NotifyConfig(
                        enabled = notifyEnabled.isChecked,
                        webhookUrl = notifyWebhookEdit.text.toString().trim(),
                        smsEnabled = notifySmsEnabled.isChecked,
                        callEnabled = notifyCallEnabled.isChecked,
                    ),
            ),
        )
        SmsWatcher.ensureRegistered(this)
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun toggleTakeover(enable: Boolean) {
        DialerTakeover.setEnabled(this, enable).fold(
            onSuccess = { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() },
            onFailure = {
                Toast.makeText(this, it.message ?: "失败", Toast.LENGTH_LONG).show()
                if (enable) DialerTakeover.requestRoleUi(this)?.let { startActivity(it) }
            },
        )
        status.text = DialerTakeover.probe(this).message
    }

    private fun rebuildSims(cfg: NexusConfig) {
        simsContainer.removeAllViews()
        cfg.sims.sortedBy { it.slot }.forEach { sim ->
            simsContainer.addView(
                TextView(this).apply {
                    textSize = 15f
                    setPadding(0, 24, 0, 8)
                    text =
                        "卡${sim.slot + 1}\n运营商：${sim.carrier.ifBlank { "未知" }}\n" +
                            "号码：${sim.number.ifBlank { "号码未知" }}\n" +
                            "当前策略：${SimCatalog.policyLabel(sim.policy)}"
                },
            )
            for (policy in listOf(SimPolicy.AI, SimPolicy.HUMAN, SimPolicy.REJECT)) {
                simsContainer.addView(
                    btn("卡${sim.slot + 1} → ${SimCatalog.policyLabel(policy)}") {
                        val next =
                            repo.load().let { c ->
                                c.copy(
                                    sims =
                                        c.sims.map { s ->
                                            if (s.slot == sim.slot) s.copy(policy = policy) else s
                                        },
                                )
                            }
                        repo.save(next)
                        rebuildSims(repo.load())
                        Toast.makeText(
                            this@NexusSettingsActivity,
                            "卡${sim.slot + 1} → ${SimCatalog.policyLabel(policy)}",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
        }
    }

    private fun btn(title: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = title
            setOnClickListener { onClick() }
        }

    private fun section(t: String): TextView =
        TextView(this).apply {
            text = t
            textSize = 18f
            setPadding(0, 40, 0, 12)
        }

    private fun label(t: String): TextView =
        TextView(this).apply {
            text = t
            textSize = 13f
            setPadding(0, 16, 0, 4)
        }

    private fun singleEdit(): EditText =
        EditText(this).apply {
            isSingleLine = true
            textSize = 14f
        }

    private fun multiEdit(lines: Int): EditText =
        EditText(this).apply {
            isSingleLine = false
            minLines = lines
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            textSize = 14f
        }
}
