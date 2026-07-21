package com.nexus.assistant.ai

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nexus.assistant.config.LlmConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * DeepSeek OpenAI-compatible chat completions (SSE stream).
 */
class DeepSeekClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.deepseek.com",
    private val model: String = "deepseek-v4-flash",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 90_000,
) {
    private val gson = Gson()

    fun chatStream(
        messages: List<ChatMessage>,
        onSentence: (String) -> Unit,
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
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    val delta = DeepSeekSse.extractDeltaContent(payload) ?: continue
                    full.append(delta)
                    sentences.push(delta)
                }
            }
            sentences.flush()
            return full.toString().trim()
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
