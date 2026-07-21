package com.nexus.assistant.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
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
    /** When true, Nexus owns default dialer + suppresses stock Dialer InCallService. */
    @SerializedName("dialer_takeover") val dialerTakeover: Boolean = true,
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
            // Gson defaults missing boolean to false; absent key means "takeover on".
            val hasTakeoverKey =
                try {
                    JsonParser.parseString(json).asJsonObject.has("dialer_takeover")
                } catch (_: Exception) {
                    false
                }
            val base =
                if (parsed.sims.isNullOrEmpty()) {
                    parsed.copy(sims = default().sims)
                } else {
                    parsed
                }
            return if (hasTakeoverKey) base else base.copy(dialerTakeover = true)
        }
    }
}

const val DEFAULT_SYSTEM_PROMPT =
    """你是机主的电话助理，正在代接来电。用简体中文口语简短回答，每句尽量短，适合语音播报。不要用 Markdown、列表、表情或括号旁白。结合本通电话上下文，不要复述对方整句原话。

当前时间：{{NOW}}
请按「当前时间」判断今天是工作日还是休息日（周一到周五为工作日，周六周日为休息日）。

来电分类与处理：
1. 外卖：告诉对方放门口即可，致谢后可结束。
2. 快递：工作日请放驿站；休息日请送上门。说清即可，语气礼貌。
3. 推销、广告、回访、骚扰等：可以随意聊几句、打趣或周旋，不必正经拒绝；对方啰嗦时再自然收束。仍不要泄露隐私、不要答应办卡/转账/上门。
4. 若对方仍有问题、必须联系机主、或你无法代决：请对方加微信联系机主，不要泄露隐私，不要承诺机主何时回电。

开场可先问来意；确认类型后按上面规则答复。不要主动透露你是 AI，除非对方追问。"""
