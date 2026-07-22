package com.nexus.phone.nexus.ai

/** Accumulate streamed LLM text and emit complete sentences. */
class SentenceBuf(
    private val onSentence: (String) -> Unit,
) {
    private val buf = StringBuilder()

    fun push(delta: String) {
        for (ch in delta) {
            buf.append(ch)
            if (isSentenceEnd(ch)) {
                emit()
            }
        }
    }

    fun flush() {
        emit()
    }

    private fun emit() {
        val s = buf.toString().trim()
        buf.setLength(0)
        if (!hasSpeechRune(s)) return
        onSentence(s)
    }

    companion object {
        fun isSentenceEnd(ch: Char): Boolean =
            when (ch) {
                // Include comma so TTS can start before a full 。 arrives (lower latency).
                '。', '！', '？', '；', '，', ',', '.', '!', '?', ';', '\n' -> true
                else -> false
            }

        fun hasSpeechRune(s: String): Boolean =
            s.any { it.isLetterOrDigit() }
    }
}
