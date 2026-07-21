package com.nexus.assistant.notify

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

enum class WebhookDeliveryStatus {
    /** Not attempted (disabled / empty URL). */
    SKIPPED,

    /** Delivered within retry budget. */
    SENT,

    /** All in-memory attempts failed; will not retry from disk. */
    FAILED,
}

data class WebhookDeliveryResult(
    val status: WebhookDeliveryStatus,
    val attempts: Int,
    val error: String? = null,
)

/**
 * In-memory webhook sender: try → retry → mark failed (no file queue).
 * Default payload shape matches common group-robot text webhooks (e.g. WeCom).
 */
object WebhookNotifier {
    private val gson = Gson()

    fun isAllowedWebhook(url: String): Boolean {
        val u = url.trim()
        return u.startsWith("https://")
    }

    fun truncateWebhookText(s: String, maxBytes: Int): String {
        val raw = s.toByteArray(StandardCharsets.UTF_8)
        if (maxBytes <= 0 || raw.size <= maxBytes) return s
        val note = "\n…(已截断)"
        val noteBytes = note.toByteArray(StandardCharsets.UTF_8)
        var keep = (maxBytes - noteBytes.size).coerceAtLeast(0)
        while (keep > 0 && !isValidUtf8Prefix(raw, keep)) {
            keep--
        }
        return String(raw, 0, keep, StandardCharsets.UTF_8) + note
    }

    fun buildPayloadJson(content: String): String {
        val text = truncateWebhookText(content.trim(), 2000)
        return gson.toJson(
            mapOf(
                "msgtype" to "text",
                "text" to mapOf("content" to text),
            ),
        )
    }

    fun sendWebhook(webhookUrl: String, content: String): Result<Unit> {
        if (!isAllowedWebhook(webhookUrl)) {
            return Result.failure(IllegalArgumentException("webhook_url 须为 https://"))
        }
        if (content.trim().isEmpty()) {
            return Result.failure(IllegalArgumentException("empty content"))
        }
        return try {
            val body = buildPayloadJson(content)
            val conn = (URL(webhookUrl.trim()).openConnection() as HttpURLConnection)
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val resp =
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()
                    ?.readText()
                    .orEmpty()
            conn.disconnect()
            if (code !in 200..299) {
                return Result.failure(IllegalStateException("HTTP $code body=$resp"))
            }
            // WeCom-style: {errcode:0}; other HTTPS hooks may return empty / non-JSON.
            val errcode =
                try {
                    JsonParser.parseString(resp).asJsonObject.get("errcode")?.asInt
                } catch (_: Exception) {
                    null
                }
            if (errcode != null && errcode != 0) {
                Result.failure(IllegalStateException("webhook errcode=$errcode body=$resp"))
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Send from memory with retries. Does not touch disk.
     * @param maxAttempts total tries including the first (default 3).
     */
    fun sendWithRetry(
        webhookUrl: String,
        content: String,
        maxAttempts: Int = 3,
        sleepMs: Long = 800L,
    ): WebhookDeliveryResult {
        if (!isAllowedWebhook(webhookUrl) || content.trim().isEmpty()) {
            return WebhookDeliveryResult(WebhookDeliveryStatus.SKIPPED, 0, "skipped")
        }
        val attempts = maxAttempts.coerceAtLeast(1)
        var lastError: String? = null
        repeat(attempts) { i ->
            val r = sendWebhook(webhookUrl, content)
            if (r.isSuccess) {
                return WebhookDeliveryResult(WebhookDeliveryStatus.SENT, i + 1, null)
            }
            lastError = r.exceptionOrNull()?.message ?: "unknown"
            Log.w(TAG, "webhook attempt ${i + 1}/$attempts failed: $lastError")
            if (i < attempts - 1 && sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return WebhookDeliveryResult(
                        WebhookDeliveryStatus.FAILED,
                        i + 1,
                        lastError,
                    )
                }
            }
        }
        return WebhookDeliveryResult(WebhookDeliveryStatus.FAILED, attempts, lastError)
    }

    private fun isValidUtf8Prefix(raw: ByteArray, len: Int): Boolean {
        return try {
            val round = String(raw, 0, len, StandardCharsets.UTF_8).toByteArray(StandardCharsets.UTF_8)
            round.size == len
        } catch (_: Exception) {
            false
        }
    }

    private const val TAG = "WebhookNotifier"
}
