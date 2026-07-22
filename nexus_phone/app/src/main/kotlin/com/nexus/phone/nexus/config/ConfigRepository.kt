package com.nexus.phone.nexus.config

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * App-private config via SharedPreferences.
 * One-shot migrates legacy files/config.json then deletes it.
 */
class ConfigRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val legacyFile = File(appContext.filesDir, "config.json")
    private val lock = Any()

    fun load(): NexusConfig =
        synchronized(lock) {
            migrateLegacyJsonIfNeeded()
            if (!prefs.contains(KEY_VERSION)) {
                val cfg = NexusConfig.default()
                writePrefs(cfg)
                return cfg
            }
            return readPrefs()
        }

    fun save(cfg: NexusConfig) {
        synchronized(lock) {
            writePrefs(cfg)
        }
    }

    /** Refresh carrier/number from device; keep existing policies. */
    fun refreshSimMetadata(): NexusConfig =
        synchronized(lock) {
            val merged = SimCatalog.merge(load(), SimInfoReader(appContext).read())
            writePrefs(merged)
            merged
        }

    private fun migrateLegacyJsonIfNeeded() {
        if (prefs.contains(KEY_VERSION) || !legacyFile.isFile) return
        try {
            val migrated = NexusConfig.fromJson(legacyFile.readText())
            writePrefs(migrated)
            legacyFile.delete()
        } catch (_: Exception) {
            // leave file; next load falls back to defaults via KEY_VERSION missing path
        }
    }

    private fun readPrefs(): NexusConfig {
        val simCount = prefs.getInt(KEY_SIM_COUNT, 0).coerceAtLeast(0)
        val sims =
            if (simCount == 0) {
                NexusConfig.default().sims
            } else {
                (0 until simCount).map { i ->
                    SimConfig(
                        slot = prefs.getInt(keySim(i, "slot"), i),
                        label = prefs.getString(keySim(i, "label"), "卡${i + 1}") ?: "卡${i + 1}",
                        carrier = prefs.getString(keySim(i, "carrier"), "") ?: "",
                        number = prefs.getString(keySim(i, "number"), "") ?: "",
                        policy = SimPolicy.fromWire(prefs.getString(keySim(i, "policy"), "human") ?: "human"),
                    )
                }
            }
        return NexusConfig(
            sims = sims,
            llm =
                LlmConfig(
                    enabled = prefs.getBoolean(KEY_LLM_ENABLED, true),
                    model = prefs.getString(KEY_LLM_MODEL, LlmConfig().model) ?: LlmConfig().model,
                    apiKey = prefs.getString(KEY_LLM_API_KEY, "") ?: "",
                    baseUrl = prefs.getString(KEY_LLM_BASE_URL, LlmConfig().baseUrl) ?: LlmConfig().baseUrl,
                    maxMsgs = prefs.getInt(KEY_LLM_MAX_MSGS, LlmConfig().maxMsgs),
                    systemPrompt =
                        prefs.getString(KEY_LLM_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT)
                            ?: DEFAULT_SYSTEM_PROMPT,
                ),
            notify =
                NotifyConfig(
                    enabled = prefs.getBoolean(KEY_NOTIFY_ENABLED, false),
                    webhookUrl = prefs.getString(KEY_NOTIFY_WEBHOOK, "") ?: "",
                    smsEnabled = prefs.getBoolean(KEY_NOTIFY_SMS, true),
                    callEnabled = prefs.getBoolean(KEY_NOTIFY_CALL, true),
                ),
            dialerTakeover = prefs.getBoolean(KEY_DIALER_TAKEOVER, true),
            sttModelPath = prefs.getString(KEY_STT_MODEL_PATH, null),
            ttsModelPath = prefs.getString(KEY_TTS_MODEL_PATH, null),
            ttsSpeakerId = prefs.getInt(KEY_TTS_SPEAKER_ID, 0).coerceAtLeast(0),
            ttsSpeed = prefs.getFloat(KEY_TTS_SPEED, 1.0f).coerceIn(TTS_SPEED_MIN, TTS_SPEED_MAX),
            greetingEnabled = prefs.getBoolean(KEY_GREETING_ENABLED, false),
            greetingText =
                prefs.getString(KEY_GREETING_TEXT, DEFAULT_GREETING_TEXT)
                    ?: DEFAULT_GREETING_TEXT,
            modelDir = prefs.getString(KEY_MODEL_DIR, null),
            archiveSafUri = prefs.getString(KEY_ARCHIVE_SAF_URI, null),
        )
    }

    private fun writePrefs(cfg: NexusConfig) {
        val ed = prefs.edit().clear()
        ed.putInt(KEY_VERSION, PREFS_VERSION)
        ed.putInt(KEY_SIM_COUNT, cfg.sims.size)
        cfg.sims.forEachIndexed { i, sim ->
            ed.putInt(keySim(i, "slot"), sim.slot)
            ed.putString(keySim(i, "label"), sim.label)
            ed.putString(keySim(i, "carrier"), sim.carrier)
            ed.putString(keySim(i, "number"), sim.number)
            ed.putString(keySim(i, "policy"), sim.policy.toWire())
        }
        ed.putBoolean(KEY_LLM_ENABLED, cfg.llm.enabled)
        ed.putString(KEY_LLM_MODEL, cfg.llm.model)
        ed.putString(KEY_LLM_API_KEY, cfg.llm.apiKey)
        ed.putString(KEY_LLM_BASE_URL, cfg.llm.baseUrl)
        ed.putInt(KEY_LLM_MAX_MSGS, cfg.llm.maxMsgs)
        ed.putString(KEY_LLM_SYSTEM_PROMPT, cfg.llm.systemPrompt)
        ed.putBoolean(KEY_NOTIFY_ENABLED, cfg.notify.enabled)
        ed.putString(KEY_NOTIFY_WEBHOOK, cfg.notify.webhookUrl)
        ed.putBoolean(KEY_NOTIFY_SMS, cfg.notify.smsEnabled)
        ed.putBoolean(KEY_NOTIFY_CALL, cfg.notify.callEnabled)
        ed.putBoolean(KEY_DIALER_TAKEOVER, cfg.dialerTakeover)
        putOptional(ed, KEY_STT_MODEL_PATH, cfg.sttModelPath)
        putOptional(ed, KEY_TTS_MODEL_PATH, cfg.ttsModelPath)
        ed.putInt(KEY_TTS_SPEAKER_ID, cfg.ttsSpeakerId.coerceAtLeast(0))
        ed.putFloat(KEY_TTS_SPEED, cfg.ttsSpeed.coerceIn(TTS_SPEED_MIN, TTS_SPEED_MAX))
        ed.putBoolean(KEY_GREETING_ENABLED, cfg.greetingEnabled)
        ed.putString(
            KEY_GREETING_TEXT,
            cfg.greetingText.ifBlank { DEFAULT_GREETING_TEXT },
        )
        putOptional(ed, KEY_MODEL_DIR, cfg.modelDir)
        putOptional(ed, KEY_ARCHIVE_SAF_URI, cfg.archiveSafUri)
        ed.commit()
    }

    private fun putOptional(ed: SharedPreferences.Editor, key: String, value: String?) {
        if (value.isNullOrBlank()) ed.remove(key) else ed.putString(key, value)
    }

    companion object {
        private const val PREFS_NAME = "nexus_config"
        private const val PREFS_VERSION = 1
        private const val KEY_VERSION = "version"
        private const val KEY_SIM_COUNT = "sim_count"
        private const val KEY_LLM_ENABLED = "llm_enabled"
        private const val KEY_LLM_MODEL = "llm_model"
        private const val KEY_LLM_API_KEY = "llm_api_key"
        private const val KEY_LLM_BASE_URL = "llm_base_url"
        private const val KEY_LLM_MAX_MSGS = "llm_max_msgs"
        private const val KEY_LLM_SYSTEM_PROMPT = "llm_system_prompt"
        private const val KEY_NOTIFY_ENABLED = "notify_enabled"
        private const val KEY_NOTIFY_WEBHOOK = "notify_webhook_url"
        private const val KEY_NOTIFY_SMS = "notify_sms_enabled"
        private const val KEY_NOTIFY_CALL = "notify_call_enabled"
        private const val KEY_DIALER_TAKEOVER = "dialer_takeover"
        private const val KEY_STT_MODEL_PATH = "stt_model_path"
        private const val KEY_TTS_MODEL_PATH = "tts_model_path"
        private const val KEY_TTS_SPEAKER_ID = "tts_speaker_id"
        private const val KEY_TTS_SPEED = "tts_speed"
        private const val KEY_GREETING_ENABLED = "greeting_enabled"
        private const val KEY_GREETING_TEXT = "greeting_text"
        private const val KEY_MODEL_DIR = "model_dir"
        private const val KEY_ARCHIVE_SAF_URI = "archive_saf_uri"

        const val TTS_SPEED_MIN = 0.5f
        const val TTS_SPEED_MAX = 2.0f

        private fun keySim(index: Int, field: String): String = "sim_${index}_$field"
    }
}
