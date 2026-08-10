package com.nexus.tim.bridge.config

/**
 * User-editable outbound settings (SharedPreferences).
 * Messages → Redis Stream; exceptions → Webhook.
 */
data class BridgeConfig(
    val redisHost: String = "",
    val redisPort: Int = DEFAULT_REDIS_PORT,
    val redisPassword: String = "",
    val redisStreamKey: String = DEFAULT_STREAM_KEY,
    val redisEnabled: Boolean = false,
    val webhookUrl: String = "",
    val webhookEnabled: Boolean = false,
    val apiAuthEnabled: Boolean = false,
    val apiToken: String = "",
) {
    val redisReady: Boolean
        get() = redisEnabled && redisHost.isNotBlank() && redisPort in 1..65535

    val webhookReady: Boolean
        get() = webhookEnabled &&
            (webhookUrl.startsWith("http://") || webhookUrl.startsWith("https://"))

    val apiAuthReady: Boolean
        get() = apiAuthEnabled && apiToken.isNotBlank()

    fun normalized(): BridgeConfig {
        val port = if (redisPort in 1..65535) redisPort else DEFAULT_REDIS_PORT
        val key = redisStreamKey.trim().ifEmpty { DEFAULT_STREAM_KEY }
        return copy(
            redisHost = redisHost.trim(),
            redisPort = port,
            redisPassword = redisPassword.trim(),
            redisStreamKey = key,
            webhookUrl = webhookUrl.trim(),
            apiToken = apiToken.trim(),
        )
    }

    companion object {
        const val DEFAULT_REDIS_PORT = 6379
        const val DEFAULT_STREAM_KEY = "nexus:tim:events"
    }
}
