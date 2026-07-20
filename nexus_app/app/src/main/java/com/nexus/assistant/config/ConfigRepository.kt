package com.nexus.assistant.config

import android.content.Context
import java.io.File

class ConfigRepository(context: Context) {
    private val file = File(context.filesDir, "config.json")
    private val lock = Any()

    fun load(): NexusConfig =
        synchronized(lock) {
            if (!file.exists()) {
                val cfg = NexusConfig.default()
                save(cfg)
                return cfg
            }
            return try {
                NexusConfig.fromJson(file.readText())
            } catch (_: Exception) {
                NexusConfig.default()
            }
        }

    fun save(cfg: NexusConfig) {
        synchronized(lock) {
            file.writeText(cfg.toJson())
        }
    }
}
