package com.nexus.assistant.ui.settings

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.nexus.assistant.ai.ModelFileImport
import com.nexus.assistant.ai.ModelPaths
import com.nexus.assistant.archive.CallFinalizer
import com.nexus.assistant.config.ConfigRepository
import com.nexus.assistant.config.DEFAULT_GREETING_TEXT
import com.nexus.assistant.config.DEFAULT_SYSTEM_PROMPT
import com.nexus.assistant.config.LlmConfig
import com.nexus.assistant.config.NexusConfig
import com.nexus.assistant.config.NotifyConfig
import com.nexus.assistant.config.SimCatalog
import com.nexus.assistant.config.SimConfig
import com.nexus.assistant.config.SimPolicy
import com.nexus.assistant.notify.SmsWatcher
import com.nexus.assistant.telecom.DialerTakeover
import kotlin.concurrent.thread

class SettingsActivity : Activity() {
    private lateinit var repo: ConfigRepository
    private lateinit var scroll: ScrollView
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var takeoverStatus: TextView
    private lateinit var takeoverButton: Button
    private lateinit var modelStatus: TextView
    private lateinit var simsContainer: LinearLayout

    private lateinit var ttsSpeakerEdit: EditText
    private lateinit var greetingEnabled: CheckBox
    private lateinit var greetingTextEdit: EditText
    private lateinit var llmEnabled: CheckBox
    private lateinit var llmModelEdit: EditText
    private lateinit var llmApiKeyEdit: EditText
    private lateinit var llmBaseUrlEdit: EditText
    private lateinit var llmMaxMsgsEdit: EditText
    private lateinit var llmPromptEdit: EditText
    private lateinit var notifyEnabled: CheckBox
    private lateinit var notifyWebhookEdit: EditText
    private lateinit var notifySmsEnabled: CheckBox
    private lateinit var notifyCallEnabled: CheckBox
    private lateinit var archivePathText: TextView

    private var pendingTakeoverEnable = false
    private var uiReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ConfigRepository(this)
        ensurePhonePermissions()
        SmsWatcher.ensureRegistered(this)

        status = TextView(this).apply { textSize = 15f }
        takeoverStatus = TextView(this).apply { textSize = 14f }
        modelStatus = TextView(this).apply { textSize = 14f }
        simsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        archivePathText = TextView(this).apply { textSize = 13f }

        ttsSpeakerEdit =
            editField(singleLine = true).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
            }
        greetingEnabled = CheckBox(this).apply { text = "启用开场白 TTS" }
        greetingTextEdit =
            editField(singleLine = false).apply {
                minLines = 2
                maxLines = 4
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
            }
        llmEnabled = CheckBox(this).apply { text = "启用 LLM" }
        llmModelEdit = editField(singleLine = true)
        llmApiKeyEdit =
            editField(singleLine = true).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        llmBaseUrlEdit = editField(singleLine = true)
        llmMaxMsgsEdit =
            editField(singleLine = true).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
            }
        llmPromptEdit =
            editField(singleLine = false).apply {
                minLines = 6
                maxLines = 16
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
            }
        notifyEnabled = CheckBox(this).apply { text = "启用 Webhook 通知总开关" }
        notifyWebhookEdit = editField(singleLine = true)
        notifySmsEnabled = CheckBox(this).apply { text = "短信 Webhook 通知" }
        notifyCallEnabled = CheckBox(this).apply { text = "通话 Webhook 通知" }

        root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 96)
            }
        scroll =
            ScrollView(this).apply {
                addView(
                    root,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        setContentView(scroll)
        buildStaticUi()
        bindFromConfig(repo.refreshSimMetadata())
        uiReady = true
    }

    override fun onResume() {
        super.onResume()
        if (!uiReady) return
        // 不重建整页，避免编辑中被冲掉；只刷新状态与卡列表。
        val cfg = repo.refreshSimMetadata()
        refreshStatus(cfg)
        refreshTakeoverStatus()
        refreshModelStatus()
        rebuildSims(cfg)
        archivePathText.text = "存档目录：${CallFinalizer.archiveRoot(this).absolutePath}"
    }

    private fun buildStaticUi() {
        root.removeAllViews()
        root.addView(status)
        root.addView(takeoverStatus)
        takeoverButton =
            Button(this).apply {
                text = "切换 Nexus 接管"
                setOnClickListener {
                    val on = repo.load().dialerTakeover
                    toggleTakeover(!on)
                }
            }
        root.addView(takeoverButton)
        root.addView(
            Button(this).apply {
                text = "刷新卡信息"
                setOnClickListener {
                    val cfg = repo.refreshSimMetadata()
                    refreshStatus(cfg)
                    rebuildSims(cfg)
                    Toast.makeText(this@SettingsActivity, "已刷新", Toast.LENGTH_SHORT).show()
                }
            },
        )

        root.addView(sectionTitle("开场白"))
        root.addView(hint("开启后，AI 接听接通时先播报下方文案；默认关闭。改完请点底部「保存配置」。"))
        root.addView(greetingEnabled)
        root.addView(label("开场白文案"))
        root.addView(greetingTextEdit)
        root.addView(
            Button(this).apply {
                text = "恢复默认开场白文案"
                setOnClickListener { greetingTextEdit.setText(DEFAULT_GREETING_TEXT) }
            },
        )

        root.addView(sectionTitle("双卡策略"))
        root.addView(simsContainer)

        root.addView(sectionTitle("STT / TTS 模型"))
        root.addView(
            hint(
                "分别选择主模型 .onnx；同目录需有 tokens.txt（TTS 另需 lexicon.txt）。文件夹名任意。",
            ),
        )
        root.addView(modelStatus)
        root.addView(
            Button(this).apply {
                text = "选择 STT 模型 (.onnx)"
                setOnClickListener { pickModelFile(REQ_PICK_STT) }
            },
        )
        root.addView(
            Button(this).apply {
                text = "恢复默认 STT"
                setOnClickListener {
                    val cfg = repo.load()
                    repo.save(cfg.copy(sttModelPath = null))
                    refreshModelStatus()
                    Toast.makeText(this@SettingsActivity, "STT 已恢复默认", Toast.LENGTH_SHORT).show()
                }
            },
        )
        root.addView(
            Button(this).apply {
                text = "选择 TTS 模型 (.onnx)"
                setOnClickListener { pickModelFile(REQ_PICK_TTS) }
            },
        )
        root.addView(
            Button(this).apply {
                text = "恢复默认 TTS"
                setOnClickListener {
                    val cfg = repo.load()
                    repo.save(cfg.copy(ttsModelPath = null))
                    refreshModelStatus()
                    Toast.makeText(this@SettingsActivity, "TTS 已恢复默认", Toast.LENGTH_SHORT).show()
                }
            },
        )
        root.addView(label("TTS Speaker ID（vits-zh-ll 一般为 0～4）"))
        root.addView(ttsSpeakerEdit)

        root.addView(sectionTitle("LLM"))
        root.addView(llmEnabled)
        root.addView(label("模型名"))
        root.addView(llmModelEdit)
        root.addView(label("API Key"))
        root.addView(llmApiKeyEdit)
        root.addView(label("Base URL"))
        root.addView(llmBaseUrlEdit)
        root.addView(label("上下文最大消息数 max_msgs"))
        root.addView(llmMaxMsgsEdit)
        root.addView(label("系统提示词（可用 {{NOW}}）"))
        root.addView(llmPromptEdit)
        root.addView(
            Button(this).apply {
                text = "恢复默认系统提示词"
                setOnClickListener { llmPromptEdit.setText(DEFAULT_SYSTEM_PROMPT) }
            },
        )

        root.addView(sectionTitle("Webhook 通知"))
        root.addView(hint("挂断后内存发送，失败重试数次；结果写入 call.json，无文件队列。"))
        root.addView(notifyEnabled)
        root.addView(label("Webhook URL（https://）"))
        root.addView(notifyWebhookEdit)
        root.addView(notifySmsEnabled)
        root.addView(notifyCallEnabled)
        root.addView(archivePathText)

        root.addView(
            Button(this).apply {
                text = "保存配置"
                setOnClickListener { saveEditableConfig() }
            },
        )
    }

    private fun bindFromConfig(cfg: NexusConfig) {
        ttsSpeakerEdit.setText(cfg.ttsSpeakerId.toString())
        greetingEnabled.isChecked = cfg.greetingEnabled
        greetingTextEdit.setText(cfg.greetingText.ifBlank { DEFAULT_GREETING_TEXT })
        llmEnabled.isChecked = cfg.llm.enabled
        llmModelEdit.setText(cfg.llm.model)
        llmApiKeyEdit.setText(cfg.llm.apiKey)
        llmBaseUrlEdit.setText(cfg.llm.baseUrl)
        llmMaxMsgsEdit.setText(cfg.llm.maxMsgs.toString())
        llmPromptEdit.setText(cfg.llm.systemPrompt)
        notifyEnabled.isChecked = cfg.notify.enabled
        notifyWebhookEdit.setText(cfg.notify.webhookUrl)
        notifySmsEnabled.isChecked = cfg.notify.smsEnabled
        notifyCallEnabled.isChecked = cfg.notify.callEnabled
        refreshStatus(cfg)
        refreshTakeoverStatus()
        refreshModelStatus()
        rebuildSims(cfg)
        archivePathText.text = "存档目录：${CallFinalizer.archiveRoot(this).absolutePath}"
        // 接管按钮文案在 onResume 里不重建；这里更新第一个相关按钮较麻烦，用 Toast/状态行即可
        updateTakeoverButtonLabel(cfg.dialerTakeover)
    }

    private fun updateTakeoverButtonLabel(takeoverOn: Boolean) {
        if (!::takeoverButton.isInitialized) return
        takeoverButton.text =
            if (takeoverOn) "关闭 Nexus 接管 → 交回系统电话" else "开启 Nexus 接管"
    }

    private fun saveEditableConfig() {
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
        if (speakerId < 0) {
            Toast.makeText(this, "Speaker ID 不能为负", Toast.LENGTH_SHORT).show()
            return
        }
        val cur = repo.load()
        val next =
            cur.copy(
                ttsSpeakerId = speakerId,
                greetingEnabled = greetingEnabled.isChecked,
                greetingText =
                    greetingTextEdit.text.toString().trim().ifBlank { DEFAULT_GREETING_TEXT },
                llm =
                    LlmConfig(
                        enabled = llmEnabled.isChecked,
                        model = llmModelEdit.text.toString().trim().ifEmpty { LlmConfig().model },
                        apiKey = llmApiKeyEdit.text.toString().trim(),
                        baseUrl =
                            llmBaseUrlEdit.text.toString().trim().ifEmpty { LlmConfig().baseUrl },
                        maxMsgs = maxMsgs,
                        systemPrompt =
                            llmPromptEdit.text.toString().ifBlank { DEFAULT_SYSTEM_PROMPT },
                    ),
                notify =
                    NotifyConfig(
                        enabled = notifyEnabled.isChecked,
                        webhookUrl = notifyWebhookEdit.text.toString().trim(),
                        smsEnabled = notifySmsEnabled.isChecked,
                        callEnabled = notifyCallEnabled.isChecked,
                    ),
            )
        repo.save(next)
        SmsWatcher.ensureRegistered(this)
        refreshModelStatus()
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun rebuildSims(cfg: NexusConfig) {
        simsContainer.removeAllViews()
        cfg.sims.sortedBy { it.slot }.forEach { sim ->
            simsContainer.addView(simHeader(sim))
            simsContainer.addView(
                Button(this).apply {
                    text = "卡${sim.slot + 1} 策略 → AI"
                    setOnClickListener { setPolicy(sim.slot, SimPolicy.AI) }
                },
            )
            simsContainer.addView(
                Button(this).apply {
                    text = "卡${sim.slot + 1} 策略 → 人工"
                    setOnClickListener { setPolicy(sim.slot, SimPolicy.HUMAN) }
                },
            )
            simsContainer.addView(
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
            setPadding(0, 24, 0, 8)
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
        rebuildSims(repo.load())
        Toast.makeText(this, "卡${slot + 1} 已设为 ${SimCatalog.policyLabel(policy)}", Toast.LENGTH_SHORT).show()
    }

    private fun refreshStatus(cfg: NexusConfig) {
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
        updateTakeoverButtonLabel(cfg.dialerTakeover)
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
        if (isDefaultDialer()) {
            applyTakeover(true, tip = "正在开启 Nexus 接管…")
            return
        }
        pendingTakeoverEnable = true
        Toast.makeText(this, "正在准备默认电话确认…", Toast.LENGTH_SHORT).show()
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
                refreshStatus(repo.load())
                Toast.makeText(
                    this,
                    result.fold(
                        onSuccess = { it },
                        onFailure = { "切换失败：${it.message}" },
                    ),
                    Toast.LENGTH_LONG,
                ).show()
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

    private fun rollbackTakeover() {
        pendingTakeoverEnable = false
        Toast.makeText(this, "未选择 Nexus，正在恢复系统电话…", Toast.LENGTH_SHORT).show()
        thread(name = "DialerTakeoverRollback") {
            val result = DialerTakeover.setEnabled(this, false)
            runOnUiThread {
                refreshTakeoverStatus()
                refreshStatus(repo.load())
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
                appendLine("STT：${if (layout.asrReady()) "就绪" else "缺失"}")
                appendLine(layout.asrModel.absolutePath)
                appendLine("TTS：${if (layout.ttsReady()) "就绪" else "缺失"}")
                appendLine(layout.ttsModel.absolutePath)
                appendLine("Speaker ID：${repo.load().ttsSpeakerId}")
                val miss = layout.missing()
                if (miss.isNotEmpty()) {
                    append("缺失 ${miss.size} 个文件")
                }
            }
    }

    private fun pickModelFile(requestCode: Int) {
        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
            }
        startActivityForResult(intent, requestCode)
    }

    private fun onModelPicked(requestCode: Int, uri: Uri) {
        Toast.makeText(this, "正在导入模型…", Toast.LENGTH_SHORT).show()
        thread(name = "ModelImport") {
            val result =
                when (requestCode) {
                    REQ_PICK_STT -> ModelFileImport.importStt(this, uri)
                    REQ_PICK_TTS -> ModelFileImport.importTts(this, uri)
                    else -> Result.failure(IllegalArgumentException("unknown request"))
                }
            runOnUiThread {
                result.fold(
                    onSuccess = { imported ->
                        val cfg = repo.load()
                        when (requestCode) {
                            REQ_PICK_STT ->
                                repo.save(cfg.copy(sttModelPath = imported.modelFile.absolutePath))
                            REQ_PICK_TTS ->
                                repo.save(cfg.copy(ttsModelPath = imported.modelFile.absolutePath))
                        }
                        refreshModelStatus()
                        val extra =
                            if (imported.copiedSidecars.isEmpty()) {
                                ""
                            } else {
                                "；已带：${imported.copiedSidecars.joinToString()}"
                            }
                        Toast.makeText(this, "导入成功$extra", Toast.LENGTH_LONG).show()
                    },
                    onFailure = {
                        Toast.makeText(this, "导入失败：${it.message}", Toast.LENGTH_LONG).show()
                        refreshModelStatus()
                    },
                )
            }
        }
    }

    private fun sectionTitle(title: String): TextView =
        TextView(this).apply {
            textSize = 18f
            setPadding(0, 40, 0, 12)
            text = title
        }

    private fun label(text: String): TextView =
        TextView(this).apply {
            textSize = 13f
            setPadding(0, 16, 0, 4)
            this.text = text
        }

    private fun hint(text: String): TextView =
        TextView(this).apply {
            textSize = 12f
            this.text = text
        }

    private fun editField(singleLine: Boolean): EditText =
        EditText(this).apply {
            this.isSingleLine = singleLine
            textSize = 14f
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
                } catch (_: Exception) {
                    // fall through
                }
            }
        }
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
            SmsWatcher.ensureRegistered(this)
            refreshStatus(repo.refreshSimMetadata())
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_STT || requestCode == REQ_PICK_TTS) {
            if (resultCode == RESULT_OK) {
                val uri = data?.data
                if (uri != null) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    } catch (_: Exception) {
                    }
                    onModelPicked(requestCode, uri)
                }
            }
            return
        }
        if (requestCode != REQ_ROLE_DIALER) {
            refreshStatus(repo.load())
            refreshTakeoverStatus()
            return
        }
        val wantedEnable = pendingTakeoverEnable
        pendingTakeoverEnable = false
        if (!wantedEnable) {
            refreshStatus(repo.load())
            refreshTakeoverStatus()
            return
        }
        if (resultCode == RESULT_OK || isDefaultDialer()) {
            applyTakeover(true, tip = "已确认默认电话，正在开启 Nexus 接管…")
            return
        }
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
        private const val REQ_PICK_STT = 3001
        private const val REQ_PICK_TTS = 3002
    }
}
