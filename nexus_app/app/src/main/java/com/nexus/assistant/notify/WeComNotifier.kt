package com.nexus.assistant.notify

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * WeCom group robot webhook sender + notify_queue drain.
 */
object WeComNotifier {
    private val gson = Gson()

    fun isAllowedWebhook(url: String): Boolean {
        val u = url.trim()
        if (u.isEmpty()) return false
        return u.startsWith("https://qyapi.weixin.qq.com/cgi-bin/webhook/send") ||
            u.contains("qyapi.weixin.qq.com")
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
            return Result.failure(IllegalArgumentException("webhook_url 非法"))
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
            val err =
                try {
                    JsonParser.parseString(resp).asJsonObject.get("errcode")?.asInt ?: -1
                } catch (_: Exception) {
                    -1
                }
            if (code in 200..299 && err == 0) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("wecom HTTP $code err=$err body=$resp"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Drain notify_queue JSON jobs under root; delete each file on success. */
    fun drainQueue(root: File, webhookUrl: String): Int {
        if (!isAllowedWebhook(webhookUrl)) return 0
        val dir = File(root, "notify_queue")
        if (!dir.isDirectory) return 0
        var ok = 0
        dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?.forEach { job ->
                try {
                    val obj = JsonParser.parseString(job.readText()).asJsonObject
                    val text = obj.get("text")?.asString.orEmpty()
                    val r = sendWebhook(webhookUrl, text)
                    if (r.isSuccess) {
                        job.delete()
                        ok++
                    } else {
                        Log.w(TAG, "notify job fail ${job.name}: ${r.exceptionOrNull()?.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "notify job ${job.name}", e)
                }
            }
        return ok
    }

    private fun isValidUtf8Prefix(raw: ByteArray, len: Int): Boolean {
        return try {
            String(raw, 0, len, StandardCharsets.UTF_8)
            val round = String(raw, 0, len, StandardCharsets.UTF_8).toByteArray(StandardCharsets.UTF_8)
            round.size == len
        } catch (_: Exception) {
            false
        }
    }

    private const val TAG = "WeComNotifier"
}
