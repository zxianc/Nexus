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
 * DeepSeek OpenAI-compatible **stream-only** chat.
 * thinking=disabled; each sentence is emitted to [onSentence] as soon as a delimiter arrives.
 */
class DeepSeekClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.deepseek.com",
    private val model: String = "deepseek-v4-flash",
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 60_000,
) {
    private val gson = Gson()

    fun chatStream(
        messages: List<ChatMessage>,
        onSentence: (String) -> Unit,
        onFirstDelta: (() -> Unit)? = null,
    ): String {
        val key = apiKey.trim()
        if (key.isEmpty()) {
            throw IllegalStateException("deepseek: empty API key")
        }
        val url = URL(baseUrl.trimEnd('/') + "/chat/completions")
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

        val conn =
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Accept", "text/event-stream")
            }

        try {
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
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "DeepSeekClient"

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
