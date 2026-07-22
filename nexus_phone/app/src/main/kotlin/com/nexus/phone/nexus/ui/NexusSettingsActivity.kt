package com.nexus.phone.nexus.ui

import android.os.Bundle
import androidx.constraintlayout.widget.ConstraintLayout
import com.nexus.phone.R
import com.nexus.phone.activities.SimpleActivity
import com.nexus.phone.databinding.ActivityNexusSettingsBinding
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
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.models.RadioItem
import org.fossify.commons.views.MyTextView

/** Nexus AI / notify / SIM settings — Fossify settings UI. */
class NexusSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityNexusSettingsBinding::inflate)
    private lateinit var repo: ConfigRepository

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
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.nexusSettingsAppbar, NavigationIcon.Arrow)
        bind(repo.refreshSimMetadata())
        updateTextColors(binding.nexusSettingsHolder)
        arrayOf(
            binding.nexusSectionPolicy,
            binding.nexusSectionSims,
            binding.nexusSectionGreeting,
            binding.nexusSectionLlm,
            binding.nexusSectionNotify,
        ).forEach { it.setTextColor(getProperPrimaryColor()) }
    }

    private fun setupClicks() {
        binding.nexusTakeoverHolder.setOnClickListener {
            binding.nexusTakeover.toggle()
            toggleTakeover(binding.nexusTakeover.isChecked)
        }
        binding.nexusTakeover.setOnClickListener {
            toggleTakeover(binding.nexusTakeover.isChecked)
        }
        binding.nexusDefaultDialerHolder.setOnClickListener {
            DialerTakeover.requestRoleUi(this)?.let { startActivity(it) }
                ?: toast(R.string.nexus_cannot_open)
        }
        binding.nexusRefreshSimsHolder.setOnClickListener {
            bind(repo.refreshSimMetadata())
            toast(R.string.nexus_sims_refreshed)
        }
        binding.nexusSaveHolder.setOnClickListener { saveEditable() }
    }

    private fun bind(cfg: NexusConfig) {
        binding.nexusStatus.text = DialerTakeover.probe(this).message +
            "\n" + getString(R.string.nexus_sims_detected, cfg.sims.size)
        binding.nexusTakeover.isChecked = cfg.dialerTakeover
        binding.nexusGreetingEnabled.isChecked = cfg.greetingEnabled
        binding.nexusGreetingText.setText(cfg.greetingText.ifBlank { DEFAULT_GREETING_TEXT })
        binding.nexusLlmEnabled.isChecked = cfg.llm.enabled
        binding.nexusLlmModel.setText(cfg.llm.model)
        binding.nexusLlmApiKey.setText(cfg.llm.apiKey)
        binding.nexusLlmBaseUrl.setText(cfg.llm.baseUrl)
        binding.nexusLlmMaxMsgs.setText(cfg.llm.maxMsgs.toString())
        binding.nexusLlmPrompt.setText(cfg.llm.systemPrompt)
        binding.nexusTtsSpeaker.setText(cfg.ttsSpeakerId.toString())
        binding.nexusNotifyEnabled.isChecked = cfg.notify.enabled
        binding.nexusWebhookUrl.setText(cfg.notify.webhookUrl)
        binding.nexusNotifySms.isChecked = cfg.notify.smsEnabled
        binding.nexusNotifyCall.isChecked = cfg.notify.callEnabled
        binding.nexusArchivePath.text =
            getString(R.string.nexus_archive_path, CallFinalizer.archiveRoot(this).absolutePath)
        rebuildSims(cfg)
    }

    private fun saveEditable() {
        val maxMsgs =
            binding.nexusLlmMaxMsgs.text.toString().trim().toIntOrNull()
                ?: run {
                    toast(R.string.nexus_max_msgs_invalid)
                    return
                }
        if (maxMsgs < 2) {
            toast(R.string.nexus_max_msgs_min)
            return
        }
        val speakerId =
            binding.nexusTtsSpeaker.text.toString().trim().toIntOrNull()
                ?: run {
                    toast(R.string.nexus_speaker_invalid)
                    return
                }
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
        toast(R.string.nexus_saved)
        bind(repo.load())
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
        binding.nexusStatus.text = DialerTakeover.probe(this).message
    }

    private fun rebuildSims(cfg: NexusConfig) {
        val container = binding.nexusSimsContainer
        container.removeAllViews()
        cfg.sims.sortedBy { it.slot }.forEach { sim ->
            container.addView(buildSimRow(sim))
        }
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
