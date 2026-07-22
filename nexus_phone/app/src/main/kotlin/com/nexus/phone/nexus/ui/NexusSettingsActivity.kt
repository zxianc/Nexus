package com.nexus.phone.nexus.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import com.nexus.phone.R
import com.nexus.phone.activities.SimpleActivity
import com.nexus.phone.databinding.ActivityNexusSettingsBinding
import com.nexus.phone.nexus.ai.ModelFileImport
import com.nexus.phone.nexus.ai.ModelPaths
import com.nexus.phone.nexus.archive.CallFinalizer
import com.nexus.phone.nexus.config.ConfigRepository
import com.nexus.phone.nexus.config.DEFAULT_GREETING_TEXT
import com.nexus.phone.nexus.config.DEFAULT_SYSTEM_PROMPT
import com.nexus.phone.nexus.config.LlmConfig
import com.nexus.phone.nexus.config.NexusConfig
import com.nexus.phone.nexus.config.NotifyConfig
import com.nexus.phone.nexus.config.SimCatalog
import com.nexus.phone.nexus.config.SimConfig
import com.nexus.phone.nexus.config.SimPolicy
import com.nexus.phone.nexus.notify.SmsWatcher
import com.nexus.phone.nexus.telecom.DialerTakeover
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.models.RadioItem
import org.fossify.commons.views.MyTextView
import kotlin.concurrent.thread

/** Nexus AI / notify / SIM settings — Fossify settings UI. */
class NexusSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityNexusSettingsBinding::inflate)
    private lateinit var repo: ConfigRepository
    private var bindingUi = false
    private var pickTarget = PickTarget.STT

    private enum class PickTarget { STT, TTS }

    private val pickModel =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onModelPicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        repo = ConfigRepository(this)
        SmsWatcher.ensureRegistered(this)

        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(nexusSettingsScroll))
            setupMaterialScrollListener(nexusSettingsScroll, nexusSettingsAppbar)
        }
        setupClicks()
        setupAutoSave()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.nexusSettingsAppbar, NavigationIcon.Arrow)
        bind(repo.refreshSimMetadata())
        applyThemeColors()
    }

    override fun onPause() {
        saveEditable(showToast = false)
        super.onPause()
    }

    private fun applyThemeColors() {
        updateTextColors(binding.nexusSettingsHolder)
        arrayOf(
            binding.nexusSectionPolicy,
            binding.nexusSectionSims,
            binding.nexusSectionGreeting,
            binding.nexusSectionModels,
            binding.nexusSectionLlm,
            binding.nexusSectionNotify,
        ).forEach { it.setTextColor(getProperPrimaryColor()) }
        colorSimRows()
    }

    private fun colorSimRows() {
        val textColor = getProperTextColor()
        for (i in 0 until binding.nexusSimsContainer.childCount) {
            val row = binding.nexusSimsContainer.getChildAt(i)
            row.findViewById<MyTextView>(R.id.nexus_sim_title)?.setTextColor(textColor)
            row.findViewById<MyTextView>(R.id.nexus_sim_policy)?.setTextColor(textColor)
        }
    }

    private fun setupClicks() {
        binding.nexusTakeoverHolder.setOnClickListener {
            binding.nexusTakeover.toggle()
            toggleTakeover(binding.nexusTakeover.isChecked)
        }
        binding.nexusTakeover.setOnClickListener {
            toggleTakeover(binding.nexusTakeover.isChecked)
        }
        binding.nexusStatus.setOnClickListener {
            if (!DialerTakeover.isDefaultDialer(this)) {
                DialerTakeover.requestRoleUi(this)?.let { startActivity(it) }
                    ?: toast(R.string.nexus_cannot_open)
            }
        }
        binding.nexusPickSttHolder.setOnClickListener { launchPick(PickTarget.STT) }
        binding.nexusPickTtsHolder.setOnClickListener { launchPick(PickTarget.TTS) }
        binding.nexusResetSttHolder.setOnClickListener {
            repo.save(repo.load().copy(sttModelPath = null))
            refreshModelStatus()
            toast(R.string.nexus_stt_reset)
        }
        binding.nexusResetTtsHolder.setOnClickListener {
            repo.save(repo.load().copy(ttsModelPath = null))
            refreshModelStatus()
            toast(R.string.nexus_tts_reset)
        }
    }

    private fun launchPick(target: PickTarget) {
        pickTarget = target
        pickModel.launch(arrayOf("application/octet-stream", "*/*"))
    }

    private fun onModelPicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        toast(R.string.nexus_model_importing)
        val target = pickTarget
        thread(name = "ModelImport") {
            val result =
                when (target) {
                    PickTarget.STT -> ModelFileImport.importStt(this, uri)
                    PickTarget.TTS -> ModelFileImport.importTts(this, uri)
                }
            runOnUiThread {
                result.fold(
                    onSuccess = { imported ->
                        val cfg = repo.load()
                        when (target) {
                            PickTarget.STT ->
                                repo.save(cfg.copy(sttModelPath = imported.modelFile.absolutePath))
                            PickTarget.TTS ->
                                repo.save(cfg.copy(ttsModelPath = imported.modelFile.absolutePath))
                        }
                        refreshModelStatus()
                        val extra =
                            if (imported.copiedSidecars.isEmpty()) {
                                ""
                            } else {
                                "；已带：${imported.copiedSidecars.joinToString()}"
                            }
                        toast(getString(R.string.nexus_model_import_ok, extra))
                    },
                    onFailure = {
                        toast(getString(R.string.nexus_model_import_fail, it.message ?: ""))
                        refreshModelStatus()
                    },
                )
            }
        }
    }

    private fun setupAutoSave() {
        val focusSaver =
            View.OnFocusChangeListener { _, hasFocus ->
                if (!hasFocus && !bindingUi) saveEditable(showToast = false)
            }
        listOf(
            binding.nexusGreetingText,
            binding.nexusLlmModel,
            binding.nexusLlmApiKey,
            binding.nexusLlmBaseUrl,
            binding.nexusLlmMaxMsgs,
            binding.nexusLlmPrompt,
            binding.nexusTtsSpeaker,
            binding.nexusWebhookUrl,
        ).forEach { it.onFocusChangeListener = focusSaver }

        fun switchSaver() {
            if (bindingUi) return
            saveEditable(showToast = false)
        }
        binding.nexusGreetingEnabled.setOnClickListener { switchSaver() }
        binding.nexusGreetingHolder.setOnClickListener {
            binding.nexusGreetingEnabled.toggle()
            switchSaver()
        }
        binding.nexusLlmEnabled.setOnClickListener { switchSaver() }
        binding.nexusLlmHolder.setOnClickListener {
            binding.nexusLlmEnabled.toggle()
            switchSaver()
        }
        binding.nexusNotifyEnabled.setOnClickListener { switchSaver() }
        binding.nexusNotifyHolder.setOnClickListener {
            binding.nexusNotifyEnabled.toggle()
            switchSaver()
        }
        binding.nexusNotifySms.setOnClickListener { switchSaver() }
        binding.nexusNotifyCall.setOnClickListener { switchSaver() }
    }

    private fun bind(cfg: NexusConfig) {
        bindingUi = true
        updateStatus(cfg)
        binding.nexusTakeover.isChecked = cfg.dialerTakeover
        binding.nexusGreetingEnabled.isChecked = cfg.greetingEnabled
        setTextIfChanged(binding.nexusGreetingText, cfg.greetingText.ifBlank { DEFAULT_GREETING_TEXT })
        binding.nexusLlmEnabled.isChecked = cfg.llm.enabled
        setTextIfChanged(binding.nexusLlmModel, cfg.llm.model)
        setTextIfChanged(binding.nexusLlmApiKey, cfg.llm.apiKey)
        setTextIfChanged(binding.nexusLlmBaseUrl, cfg.llm.baseUrl)
        setTextIfChanged(binding.nexusLlmMaxMsgs, cfg.llm.maxMsgs.toString())
        setTextIfChanged(binding.nexusLlmPrompt, cfg.llm.systemPrompt)
        setTextIfChanged(binding.nexusTtsSpeaker, cfg.ttsSpeakerId.toString())
        binding.nexusNotifyEnabled.isChecked = cfg.notify.enabled
        setTextIfChanged(binding.nexusWebhookUrl, cfg.notify.webhookUrl)
        binding.nexusNotifySms.isChecked = cfg.notify.smsEnabled
        binding.nexusNotifyCall.isChecked = cfg.notify.callEnabled
        binding.nexusArchivePath.text =
            getString(R.string.nexus_archive_path, CallFinalizer.archiveRoot(this).absolutePath)
        rebuildSims(cfg)
        refreshModelStatus()
        bindingUi = false
    }

    private fun updateStatus(cfg: NexusConfig) {
        val probe = DialerTakeover.probe(this)
        binding.nexusStatus.text =
            buildString {
                append(probe.message)
                append('\n')
                append(getString(R.string.nexus_sims_detected, cfg.sims.size))
                if (!probe.isDefaultDialer) {
                    append('\n')
                    append(getString(R.string.nexus_tap_set_default))
                }
            }
    }

    private fun refreshModelStatus() {
        val layout = ModelPaths.resolve(this)
        binding.nexusModelStatus.text =
            buildString {
                appendLine(
                    getString(
                        if (layout.asrReady()) R.string.nexus_model_stt_ready else R.string.nexus_model_stt_missing,
                    ),
                )
                appendLine(layout.asrModel.absolutePath)
                appendLine(
                    getString(
                        if (layout.ttsReady()) R.string.nexus_model_tts_ready else R.string.nexus_model_tts_missing,
                    ),
                )
                append(layout.ttsModel.absolutePath)
                val miss = layout.missing()
                if (miss.isNotEmpty()) {
                    append("\n缺失 ${miss.size} 个文件")
                }
            }
    }

    private fun setTextIfChanged(edit: EditText, value: String) {
        if (edit.text?.toString() != value) {
            edit.setText(value)
        }
    }

    private fun saveEditable(showToast: Boolean) {
        if (bindingUi) return
        val maxMsgs = binding.nexusLlmMaxMsgs.text.toString().trim().toIntOrNull() ?: 8
        val speakerId = binding.nexusTtsSpeaker.text.toString().trim().toIntOrNull() ?: 0
        if (maxMsgs < 2) return
        val cur = repo.load()
        repo.save(
            cur.copy(
                ttsSpeakerId = speakerId.coerceAtLeast(0),
                greetingEnabled = binding.nexusGreetingEnabled.isChecked,
                greetingText =
                    binding.nexusGreetingText.text.toString().trim().ifBlank { DEFAULT_GREETING_TEXT },
                llm =
                    LlmConfig(
                        enabled = binding.nexusLlmEnabled.isChecked,
                        model =
                            binding.nexusLlmModel.text.toString().trim().ifEmpty { LlmConfig().model },
                        apiKey = binding.nexusLlmApiKey.text.toString().trim(),
                        baseUrl =
                            binding.nexusLlmBaseUrl.text.toString().trim().ifEmpty { LlmConfig().baseUrl },
                        maxMsgs = maxMsgs,
                        systemPrompt =
                            binding.nexusLlmPrompt.text.toString().ifBlank { DEFAULT_SYSTEM_PROMPT },
                    ),
                notify =
                    NotifyConfig(
                        enabled = binding.nexusNotifyEnabled.isChecked,
                        webhookUrl = binding.nexusWebhookUrl.text.toString().trim(),
                        smsEnabled = binding.nexusNotifySms.isChecked,
                        callEnabled = binding.nexusNotifyCall.isChecked,
                    ),
            ),
        )
        SmsWatcher.ensureRegistered(this)
        if (showToast) toast(R.string.nexus_saved)
    }

    private fun toggleTakeover(enable: Boolean) {
        DialerTakeover.setEnabled(this, enable).fold(
            onSuccess = { toast(it) },
            onFailure = {
                binding.nexusTakeover.isChecked = false
                toast(it.message ?: getString(R.string.nexus_cannot_open))
                if (enable) DialerTakeover.requestRoleUi(this)?.let { startActivity(it) }
            },
        )
        updateStatus(repo.load())
    }

    private fun rebuildSims(cfg: NexusConfig) {
        val container = binding.nexusSimsContainer
        container.removeAllViews()
        cfg.sims.sortedBy { it.slot }.forEach { sim ->
            container.addView(buildSimRow(sim))
        }
        colorSimRows()
    }

    private fun buildSimRow(sim: SimConfig): ConstraintLayout {
        val row =
            layoutInflater.inflate(
                R.layout.item_nexus_sim_policy,
                binding.nexusSimsContainer,
                false,
            ) as ConstraintLayout
        val title = row.findViewById<MyTextView>(R.id.nexus_sim_title)
        val value = row.findViewById<MyTextView>(R.id.nexus_sim_policy)
        val textColor = getProperTextColor()
        title.setTextColor(textColor)
        value.setTextColor(textColor)
        title.text =
            getString(
                R.string.nexus_sim_title,
                sim.slot + 1,
                sim.carrier.ifBlank { getString(R.string.nexus_unknown_carrier) },
                sim.number.ifBlank { getString(R.string.nexus_unknown_number) },
            )
        value.text = SimCatalog.policyLabel(sim.policy)
        row.setOnClickListener { pickPolicy(sim) }
        return row
    }

    private fun pickPolicy(sim: SimConfig) {
        val items =
            arrayListOf(
                RadioItem(SimPolicy.AI.ordinal, SimCatalog.policyLabel(SimPolicy.AI)),
                RadioItem(SimPolicy.HUMAN.ordinal, SimCatalog.policyLabel(SimPolicy.HUMAN)),
                RadioItem(SimPolicy.REJECT.ordinal, SimCatalog.policyLabel(SimPolicy.REJECT)),
            )
        RadioGroupDialog(this, items, sim.policy.ordinal) { picked ->
            val policy = SimPolicy.values()[picked as Int]
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
            toast(
                getString(
                    R.string.nexus_sim_policy_set,
                    sim.slot + 1,
                    SimCatalog.policyLabel(policy),
                ),
            )
        }
    }
}
