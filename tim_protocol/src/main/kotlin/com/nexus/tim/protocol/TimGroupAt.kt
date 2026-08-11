package com.nexus.tim.protocol

/** Normalize TIM group @ targets for send / protocol payloads. */
object TimGroupAt {
    const val NOTIFY_ALL = "notify@all"

    fun normalizeAts(ats: List<String>): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (raw in ats) {
            val id = raw.trim()
            if (id.isEmpty()) continue
            val key = if (id.equals(NOTIFY_ALL, ignoreCase = true)) NOTIFY_ALL else id
            if (seen.add(key)) out.add(key)
        }
        return out
    }
}
