package com.nexus.phone.nexus.ai

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nexus.phone.nexus.config.LlmConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * DeepSeek OpenAI-compatible chat (stream for calls; tiny non-stream for ring prewarm).
 * thinking=disabled; each sentence is emitted to [onSentence] as soon as a delimiter arrives.
 */
class DeepSeekClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.deepseek.com",
    private val model: String = "deepseek-v4-flash",
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {
    private val gson = Gson()

    /**
     * DNS + TLS + one cheap completion during RINGING so the first real turn
     * does not pay a multi-second cold HTTPS cost (seen as llm_first≈5–6s).
     */
    fun prewarm() {
        val key = apiKey.trim()
        if (key.isEmpty()) return
        val t0 = System.currentTimeMillis()
        try {
            val conn = openChatConnection(key, accept = "application/json")
            try {
                val req =
                    JsonObject().apply {
                        addProperty("model", model.ifBlank { "deepseek-v4-flash" })
                        addProperty("stream", false)
                        addProperty("max_tokens", 1)
                        add(
                            "thinking",
                            JsonObject().apply { addProperty("type", "disabled") },
                        )
                        add(
                            "messages",
                            gson.toJsonTree(
                                listOf(mapOf("role" to "user", "content" to ".")),
                            ),
                        )
                    }
                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use {
                    it.write(gson.toJson(req))
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.let { s ->
                    BufferedReader(InputStreamReader(s, StandardCharsets.UTF_8)).use {
                        it.readText()
                    }
                }
                Log.i(TAG, "prewarm_ms=${System.currentTimeMillis() - t0} http=$code")
            } finally {
                // Keep socket in Android keepalive pool for the upcoming call turn.
                closeQuietly(conn)
            }
        } catch (e: Exception) {
            Log.w(
                TAG,
                "prewarm failed ms=${System.currentTimeMillis() - t0}: " +
                    "${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    fun chatStream(
        messages: List<ChatMessage>,
        onSentence: (String) -> Unit,
        onFirstDelta: (() -> Unit)? = null,
    ): String {
        val key = apiKey.trim()
        if (key.isEmpty()) {
            throw IllegalStateException("deepseek: empty API key")
        }
        val conn = openChatConnection(key, accept = "text/event-stream")
        try {
            val req =
                JsonObject().apply {
                    addProperty("model", model.ifBlank { "deepseek-v4-flash" })
                    addProperty("stream", true)
                    // Phone path: never wait on chain-of-thought.
                    add(
                        "thinking",
                        JsonObject().apply { addProperty("type", "disabled") },
                    )
                    add(
                        "messages",
                        gson.toJsonTree(messages.map { mapOf("role" to it.role, "content" to it.content) }),
                    )
                }
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use {
                it.write(gson.toJson(req))
            }
            val code = conn.responseCode
            val stream =
                if (code in 200..299) {
                    conn.inputStream
                } else {
                    conn.errorStream ?: conn.inputStream
                }
            if (code !in 200..299) {
                val err =
                    BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use {
                        it.readText().take(2048)
                    }
                throw IllegalStateException("deepseek: HTTP $code: ${err.trim()}")
            }

            val full = StringBuilder()
            val sentences = SentenceBuf(onSentence)
            var first = true
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    val delta = DeepSeekSse.extractDeltaContent(payload) ?: continue
                    if (first) {
                        first = false
                        onFirstDelta?.invoke()
                    }
                    full.append(delta)
                    // Emit ASAP on sentence / clause boundaries → TTS without waiting for full reply.
                    sentences.push(delta)
                }
            }
            sentences.flush()
            val out = full.toString().trim()
            if (out.isEmpty()) {
                Log.w(TAG, "stream finished with empty content")
            } else {
                Log.i(TAG, "stream ok chars=${out.length}")
            }
            return out
        } finally {
            closeQuietly(conn)
        }
    }

    private fun openChatConnection(apiKey: String, accept: String): HttpURLConnection {
        val url = URL(baseUrl.trimEnd('/') + "/chat/completions")
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", accept)
            // Prefer keepalive reuse across prewarm → first call turn.
            setRequestProperty("Connection", "keep-alive")
        }
    }

    private fun closeQuietly(conn: HttpURLConnection) {
        try {
            conn.inputStream?.close()
        } catch (_: Exception) {
        }
        try {
            conn.errorStream?.close()
        } catch (_: Exception) {
        }
        // Intentionally no disconnect(): on Android that closes the pooled socket.
    }

    companion object {
        private const val TAG = "DeepSeekClient"

        /** Keep tight so a hung stream cannot occupy aiBusy for a whole short call. */
        const val DEFAULT_CONNECT_TIMEOUT_MS = 8_000
        const val DEFAULT_READ_TIMEOUT_MS = 12_000

        fun fromConfig(cfg: LlmConfig): DeepSeekClient? {
            if (!cfg.enabled || cfg.apiKey.isBlank()) {
                Log.w(TAG, "LLM disabled or api_key empty")
                return null
            }
            return DeepSeekClient(
                apiKey = cfg.apiKey,
                baseUrl = cfg.baseUrl.ifBlank { "https://api.deepseek.com" },
                model = cfg.model.ifBlank { "deepseek-v4-flash" },
            )
        }
    }
}
