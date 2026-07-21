package com.nexus.assistant.ai

data class ChatMessage(
    val role: String,
    val content: String,
)

/** One phone-call chat history (system prompt not stored). */
class CallSession(
    maxMessages: Int = 24,
) {
    private val lock = Any()
    private val hist = ArrayList<ChatMessage>()
    private val maxMessages = if (maxMessages <= 0) 24 else maxMessages

    @Volatile
    var generation: Long = 0
        private set

    fun reset() {
        synchronized(lock) {
            hist.clear()
            generation++
        }
    }

    fun appendUser(text: String) {
        append("user", text)
    }

    fun appendAssistant(text: String) {
        append("assistant", text)
    }

    fun appendAssistantGen(gen: Long, text: String): Boolean = appendGen(gen, "assistant", text)

    fun appendUserGen(gen: Long, text: String): Boolean = appendGen(gen, "user", text)

    fun messages(system: String): List<ChatMessage> {
        val out = ArrayList<ChatMessage>()
        val sys = SystemPrompt.expand(system)
        if (sys.isNotBlank()) {
            out.add(ChatMessage("system", sys))
        }
        synchronized(lock) {
            out.addAll(hist)
        }
        return out
    }

    /** Snapshot user/assistant turns for archive (no system prompt). */
    fun snapshot(): List<ChatMessage> {
        synchronized(lock) {
            return hist.toList()
        }
    }

    fun transcriptLines(): List<String> =
        snapshot().map { m ->
            val who =
                when (m.role) {
                    "user" -> "用户"
                    "assistant" -> "助理"
                    else -> m.role
                }
            "$who: ${m.content}"
        }

    private fun append(role: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        synchronized(lock) {
            hist.add(ChatMessage(role, t))
            trimLocked()
        }
    }

    private fun appendGen(gen: Long, role: String, text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        synchronized(lock) {
            if (gen != generation) return false
            hist.add(ChatMessage(role, t))
            trimLocked()
            return true
        }
    }

    private fun trimLocked() {
        if (hist.size <= maxMessages) return
        val drop = hist.size - maxMessages
        repeat(drop) { hist.removeAt(0) }
    }
}
