package com.nexus.assistant.config

import org.json.JSONArray
import org.json.JSONObject

enum class SimPolicy {
    HUMAN,
    AI,
    REJECT;

    companion object {
        fun fromWire(raw: String): SimPolicy =
            when (raw.lowercase()) {
                "ai" -> AI
                "reject" -> REJECT
                else -> HUMAN
            }
    }

    fun toWire(): String =
        when (this) {
            HUMAN -> "human"
            AI -> "ai"
            REJECT -> "reject"
        }
}

data class SimConfig(
    val slot: Int,
    val label: String,
    val carrier: String = "",
    val number: String = "",
    val policy: SimPolicy = SimPolicy.HUMAN,
)

data class LlmConfig(
    val enabled: Boolean = true,
    val model: String = "deepseek-v4-flash",
    val apiKey: String = "",
    val baseUrl: String = "https://api.deepseek.com",
    val maxMsgs: Int = 24,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
)

data class NotifyConfig(
    val enabled: Boolean = false,
    val webhookUrl: String = "",
    val smsEnabled: Boolean = true,
    val callEnabled: Boolean = true,
)

data class NexusConfig(
    val sims: List<SimConfig>,
    val llm: LlmConfig,
    val notify: NotifyConfig,
    val modelDir: String? = null,
    val archiveSafUri: String? = null,
) {
    fun toJson(): String {
        val root = JSONObject()
        val simsArr = JSONArray()
        for (s in sims) {
            simsArr.put(
                JSONObject()
                    .put("slot", s.slot)
                    .put("label", s.label)
                    .put("carrier", s.carrier)
                    .put("number", s.number)
                    .put("policy", s.policy.toWire()),
            )
        }
        root.put("sims", simsArr)
        root.put(
            "llm",
            JSONObject()
                .put("enabled", llm.enabled)
                .put("model", llm.model)
                .put("api_key", llm.apiKey)
                .put("base_url", llm.baseUrl)
                .put("max_msgs", llm.maxMsgs)
                .put("system_prompt", llm.systemPrompt),
        )
        root.put(
            "notify",
            JSONObject()
                .put("enabled", notify.enabled)
                .put("webhook_url", notify.webhookUrl)
                .put("sms_enabled", notify.smsEnabled)
                .put("call_enabled", notify.callEnabled),
        )
        root.put("model_dir", modelDir ?: JSONObject.NULL)
        root.put("archive_saf_uri", archiveSafUri ?: JSONObject.NULL)
        return root.toString(2)
    }

    fun policyForSlot(slot: Int): SimPolicy =
        sims.firstOrNull { it.slot == slot }?.policy ?: SimPolicy.HUMAN

    companion object {
        fun default(): NexusConfig =
            NexusConfig(
                sims =
                    listOf(
                        SimConfig(0, "卡1", policy = SimPolicy.HUMAN),
                        SimConfig(1, "卡2", policy = SimPolicy.HUMAN),
                    ),
                llm = LlmConfig(),
                notify = NotifyConfig(),
            )

        fun fromJson(json: String): NexusConfig {
            val root = JSONObject(json)
            val simsArr = root.optJSONArray("sims") ?: JSONArray()
            val sims = ArrayList<SimConfig>()
            for (i in 0 until simsArr.length()) {
                val o = simsArr.getJSONObject(i)
                sims.add(
                    SimConfig(
                        slot = o.optInt("slot", i),
                        label = o.optString("label", "卡${i + 1}"),
                        carrier = o.optString("carrier", ""),
                        number = o.optString("number", ""),
                        policy = SimPolicy.fromWire(o.optString("policy", "human")),
                    ),
                )
            }
            if (sims.isEmpty()) {
                sims.addAll(default().sims)
            }
            val llmObj = root.optJSONObject("llm") ?: JSONObject()
            val notifyObj = root.optJSONObject("notify") ?: JSONObject()
            return NexusConfig(
                sims = sims,
                llm =
                    LlmConfig(
                        enabled = llmObj.optBoolean("enabled", true),
                        model = llmObj.optString("model", "deepseek-v4-flash"),
                        apiKey = llmObj.optString("api_key", ""),
                        baseUrl = llmObj.optString("base_url", "https://api.deepseek.com"),
                        maxMsgs = llmObj.optInt("max_msgs", 24),
                        systemPrompt = llmObj.optString("system_prompt", DEFAULT_SYSTEM_PROMPT),
                    ),
                notify =
                    NotifyConfig(
                        enabled = notifyObj.optBoolean("enabled", false),
                        webhookUrl = notifyObj.optString("webhook_url", ""),
                        smsEnabled = notifyObj.optBoolean("sms_enabled", true),
                        callEnabled = notifyObj.optBoolean("call_enabled", true),
                    ),
                modelDir =
                    if (root.isNull("model_dir")) {
                        null
                    } else {
                        root.optString("model_dir").ifEmpty { null }
                    },
                archiveSafUri =
                    if (root.isNull("archive_saf_uri")) {
                        null
                    } else {
                        root.optString("archive_saf_uri").ifEmpty { null }
                    },
            )
        }
    }
}

const val DEFAULT_SYSTEM_PROMPT =
    "你是机主的电话助理，正在代接来电。用简体中文口语简短回答，每句尽量短，适合语音播报。"
