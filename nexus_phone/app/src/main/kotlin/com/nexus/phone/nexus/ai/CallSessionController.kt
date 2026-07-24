package com.nexus.phone.nexus.ai

import android.util.Log
import com.nexus.phone.nexus.config.LlmConfig

/**
 * Phone-call LLM state machine: user utterance → DeepSeek stream → sentence callbacks.
 */
class CallSessionController(
    private val llm: LlmConfig,
    private val client: DeepSeekClient?,
    private val onAssistantSentence: (String) -> Unit,
) {
    private val session = CallSession(llm.maxMsgs)

    /** Fired once per utterance on first SSE content delta (for latency timing). */
    @Volatile
    var onLlmFirstDelta: (() -> Unit)? = null

    fun reset() {
        session.reset()
    }

    fun ready(): Boolean = client != null && llm.enabled && llm.apiKey.isNotBlank()

    fun transcriptLines(): List<String> = session.transcriptLines()

    fun snapshot(): List<ChatMessage> = session.snapshot()

    /**
     * @return full assistant text, or empty on failure / not ready
     */
    fun onUserUtterance(text: String): String {
        val user = text.trim()
        if (user.isEmpty()) return ""
        val c = client
        if (c == null || !llm.enabled) {
            Log.w(TAG, "LLM not ready, skip")
            return ""
        }
        val gen = session.generation
        if (!session.appendUserGen(gen, user)) return ""
        return try {
            val msgs = session.messages(llm.systemPrompt)
            val full =
                c.chatStream(
                    msgs,
                    onSentence = { sentence ->
                        if (session.generation == gen) {
                            onAssistantSentence(sentence)
                        }
                    },
                    onFirstDelta = { onLlmFirstDelta?.invoke() },
                )
            if (full.isNotBlank()) {
                session.appendAssistantGen(gen, full)
            }
            Log.i(TAG, "LLM reply chars=${full.length}")
            full
        } catch (e: Exception) {
            Log.e(TAG, "LLM failed: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}")
            ""
        }
    }

    companion object {
        private const val TAG = "CallSession"
    }
}
