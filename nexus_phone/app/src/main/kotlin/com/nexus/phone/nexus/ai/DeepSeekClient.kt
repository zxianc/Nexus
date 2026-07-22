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
 * DeepSeek OpenAI-compatible chat completions (SSE stream).
 *
 * Always sends thinking=disabled so assistant text lands in `content`
 * (deepseek-v4 otherwise streams into `reasoning_content`, which we do not speak).
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
        val streamed = streamOnce(messages, onSentence)
        if (streamed.isNotBlank()) return streamed

        // Stream can yield empty when the model only emits reasoning_content.
        // Fall back to a non-stream call with thinking disabled.
        Log.w(TAG, "stream empty; retry non-stream with thinking disabled")
        val once = completeOnce(messages)
        if (once.isNotBlank()) {
            SentenceBuf(onSentence).apply {
                push(once)
                flush()
            }
        }
        return once
    }

    private fun streamOnce(
        messages: List<ChatMessage>,
        onSentence: (String) -> Unit,
    ): String {
        val conn = openChat(messages, stream = true)
        try {
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

    private fun completeOnce(messages: List<ChatMessage>): String {
        val conn = openChat(messages, stream = false)
        try {
            val code = conn.responseCode
            val stream =
                if (code in 200..299) {
                    conn.inputStream
                } else {
                    conn.errorStream ?: conn.inputStream
                }
            val body =
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use {
                    it.readText()
                }
            if (code !in 200..299) {
                throw IllegalStateException("deepseek: HTTP $code: ${body.trim().take(2048)}")
            }
            return extractMessageContent(body).trim()
        } finally {
            conn.disconnect()
        }
    }

    private fun openChat(messages: List<ChatMessage>, stream: Boolean): HttpURLConnection {
        val url = URL(baseUrl.trimEnd('/') + "/chat/completions")
        val req =
            JsonObject().apply {
                addProperty("model", model.ifBlank { "deepseek-v4-flash" })
                addProperty("stream", stream)
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
                setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
                if (stream) {
                    setRequestProperty("Accept", "text/event-stream")
                }
            }
        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use {
            it.write(gson.toJson(req))
        }
        return conn
    }

    private fun extractMessageContent(body: String): String {
        return try {
            val root = com.google.gson.JsonParser.parseString(body).asJsonObject
            val choices = root.getAsJsonArray("choices") ?: return ""
            if (choices.size() == 0) return ""
            val message = choices[0].asJsonObject.getAsJsonObject("message") ?: return ""
            val content = message.get("content")
            if (content == null || content.isJsonNull) return ""
            content.asString.orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "parse completion", e)
            ""
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
