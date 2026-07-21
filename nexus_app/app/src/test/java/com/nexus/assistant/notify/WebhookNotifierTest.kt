package com.nexus.assistant.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookNotifierTest {
    @Test
    fun isAllowedWebhook_acceptsHttps() {
        assertTrue(
            WebhookNotifier.isAllowedWebhook(
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc",
            ),
        )
        assertTrue(WebhookNotifier.isAllowedWebhook("https://example.com/hook"))
    }

    @Test
    fun isAllowedWebhook_rejectsNonHttps() {
        assertFalse(WebhookNotifier.isAllowedWebhook("http://example.com/hook"))
        assertFalse(WebhookNotifier.isAllowedWebhook(""))
    }

    @Test
    fun truncate_keepsUnderLimit() {
        val long = "啊".repeat(3000)
        val out = WebhookNotifier.truncateWebhookText(long, 2000)
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= 2000 + 32)
        assertTrue(out.contains("截断") || out.length < long.length)
    }

    @Test
    fun buildPayload_isTextMsgtype() {
        val json = WebhookNotifier.buildPayloadJson("hello")
        assertTrue(json.contains("\"msgtype\":\"text\"") || json.contains("\"msgtype\": \"text\""))
        assertTrue(json.contains("hello"))
    }

    @Test
    fun sendWithRetry_skipsInvalid() {
        val r = WebhookNotifier.sendWithRetry("http://bad", "hi", maxAttempts = 2, sleepMs = 0)
        assertEquals(WebhookDeliveryStatus.SKIPPED, r.status)
        assertEquals(0, r.attempts)
    }
}
