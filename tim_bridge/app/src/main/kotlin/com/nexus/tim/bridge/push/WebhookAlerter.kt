package com.nexus.tim.bridge.push

import android.util.Log
import com.nexus.tim.bridge.config.BridgeConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Fire-and-forget JSON alerts with per-code throttling. */
class WebhookAlerter(
    private val configProvider: () -> BridgeConfig,
    private val minIntervalMs: Long = 30_000L,
) {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tim-webhook-alert").apply { isDaemon = true }
    }
    private val lastSent = ConcurrentHashMap<String, Long>()

    fun alert(code: String, message: String, detail: JSONObject? = null) {
        executor.execute {
            val cfg = configProvider().normalized()
            if (!cfg.webhookReady) return@execute
            val now = System.currentTimeMillis()
            val prev = lastSent[code] ?: 0L
            if (now - prev < minIntervalMs) {
                Log.i(TAG, "throttled code=$code")
                return@execute
            }
            lastSent[code] = now
            val body = buildBody(cfg.webhookUrl, code, message, now, detail)
            try {
                val result = postJson(cfg.webhookUrl, body)
                if (result.ok) {
                    Log.i(TAG, "ok code=$code http=${result.http} ${result.body.take(120)}")
                } else {
                    Log.w(TAG, "failed code=$code http=${result.http} ${result.body.take(200)}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "webhook failed: ${t.message}")
            }
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun buildBody(
        url: String,
        code: String,
        message: String,
        nowMs: Long,
        detail: JSONObject?,
    ): String {
        if (isWeComBot(url)) {
            val content = buildString {
                append("[tim_bridge] ")
                append(code)
                append('\n')
                append(message)
                if (detail != null && detail.length() > 0) {
                    append('\n')
                    append(detail.toString())
                }
            }
            return JSONObject()
                .put("msgtype", "text")
                .put("text", JSONObject().put("content", content.take(2000)))
                .toString()
        }
        val body = JSONObject()
            .put("source", "tim_bridge")
            .put("level", "error")
            .put("code", code)
            .put("message", message)
            .put("ts", nowMs / 1000)
        if (detail != null) body.put("detail", detail)
        return body.toString()
    }

    private fun postJson(url: String, json: String): PostResult {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 5_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val http = conn.responseCode
            val stream = if (http in 200..299) conn.inputStream else conn.errorStream
            val respBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val ok = http in 200..299 && !isWeComError(respBody)
            return PostResult(ok = ok, http = http, body = respBody)
        } finally {
            conn.disconnect()
        }
    }

    private data class PostResult(val ok: Boolean, val http: Int, val body: String)

    companion object {
        private const val TAG = "TimBridgeWebhook"

        fun isWeComBot(url: String): Boolean =
            url.contains("qyapi.weixin.qq.com", ignoreCase = true)

        fun isWeComError(body: String): Boolean {
            if (body.isBlank()) return false
            return try {
                JSONObject(body).optInt("errcode", 0) != 0
            } catch (_: Exception) {
                false
            }
        }
    }
}
