package com.nexus.assistant.config

import android.content.Context
import java.io.File

class ConfigRepository(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "config.json")
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

    /** Refresh carrier/number from device; keep existing policies. */
    fun refreshSimMetadata(): NexusConfig =
        synchronized(lock) {
            val merged = SimCatalog.merge(load(), SimInfoReader(appContext).read())
            save(merged)
            merged
        }
}
