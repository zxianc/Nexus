package com.nexus.assistant.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName

enum class SimPolicy {
    @SerializedName("human")
    HUMAN,

    @SerializedName("ai")
    AI,

    @SerializedName("reject")
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
    @SerializedName("api_key") val apiKey: String = "",
    @SerializedName("base_url") val baseUrl: String = "https://api.deepseek.com",
    @SerializedName("max_msgs") val maxMsgs: Int = 24,
    @SerializedName("system_prompt") val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
)

data class NotifyConfig(
    val enabled: Boolean = false,
    @SerializedName("webhook_url") val webhookUrl: String = "",
    @SerializedName("sms_enabled") val smsEnabled: Boolean = true,
    @SerializedName("call_enabled") val callEnabled: Boolean = true,
)

data class NexusConfig(
    val sims: List<SimConfig>,
    val llm: LlmConfig,
    val notify: NotifyConfig,
    @SerializedName("model_dir") val modelDir: String? = null,
    @SerializedName("archive_saf_uri") val archiveSafUri: String? = null,
) {
    fun toJson(): String = gson.toJson(this)

    fun policyForSlot(slot: Int): SimPolicy =
        sims.firstOrNull { it.slot == slot }?.policy ?: SimPolicy.HUMAN

    companion object {
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

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
            val parsed = gson.fromJson(json, NexusConfig::class.java)
            return if (parsed.sims.isNullOrEmpty()) {
                parsed.copy(sims = default().sims)
            } else {
                parsed
            }
        }
    }
}

const val DEFAULT_SYSTEM_PROMPT =
    "你是机主的电话助理，正在代接来电。用简体中文口语简短回答，每句尽量短，适合语音播报。"
