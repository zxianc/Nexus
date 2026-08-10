package com.nexus.tim.bridge.config

import android.content.Context

class BridgeConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): BridgeConfig = BridgeConfig(
        redisHost = prefs.getString(KEY_REDIS_HOST, "") ?: "",
        redisPort = prefs.getInt(KEY_REDIS_PORT, BridgeConfig.DEFAULT_REDIS_PORT),
        redisPassword = prefs.getString(KEY_REDIS_PASSWORD, "") ?: "",
        redisStreamKey = prefs.getString(KEY_REDIS_STREAM, BridgeConfig.DEFAULT_STREAM_KEY)
            ?: BridgeConfig.DEFAULT_STREAM_KEY,
        redisEnabled = prefs.getBoolean(KEY_REDIS_ENABLED, false),
        webhookUrl = prefs.getString(KEY_WEBHOOK_URL, "") ?: "",
        webhookEnabled = prefs.getBoolean(KEY_WEBHOOK_ENABLED, false),
        apiAuthEnabled = prefs.getBoolean(KEY_API_AUTH_ENABLED, false),
        apiToken = prefs.getString(KEY_API_TOKEN, "") ?: "",
    ).normalized()

    fun save(config: BridgeConfig) {
        val c = config.normalized()
        prefs.edit()
            .putString(KEY_REDIS_HOST, c.redisHost)
            .putInt(KEY_REDIS_PORT, c.redisPort)
            .putString(KEY_REDIS_PASSWORD, c.redisPassword)
            .putString(KEY_REDIS_STREAM, c.redisStreamKey)
            .putBoolean(KEY_REDIS_ENABLED, c.redisEnabled)
            .putString(KEY_WEBHOOK_URL, c.webhookUrl)
            .putBoolean(KEY_WEBHOOK_ENABLED, c.webhookEnabled)
            .putBoolean(KEY_API_AUTH_ENABLED, c.apiAuthEnabled)
            .putString(KEY_API_TOKEN, c.apiToken)
            .apply()
    }

    companion object {
        private const val PREFS = "tim_bridge_config"
        private const val KEY_REDIS_HOST = "redis_host"
        private const val KEY_REDIS_PORT = "redis_port"
        private const val KEY_REDIS_PASSWORD = "redis_password"
        private const val KEY_REDIS_STREAM = "redis_stream"
        private const val KEY_REDIS_ENABLED = "redis_enabled"
        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val KEY_WEBHOOK_ENABLED = "webhook_enabled"
        private const val KEY_API_AUTH_ENABLED = "api_auth_enabled"
        private const val KEY_API_TOKEN = "api_token"
    }
}
