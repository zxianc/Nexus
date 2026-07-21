package com.nexus.assistant.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeComNotifierTest {
    @Test
    fun isAllowedWebhook_acceptsQyapi() {
        assertTrue(
            WeComNotifier.isAllowedWebhook(
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc",
            ),
        )
    }

    @Test
    fun isAllowedWebhook_rejectsOtherHost() {
        assertFalse(WeComNotifier.isAllowedWebhook("https://evil.example/hook"))
        assertFalse(WeComNotifier.isAllowedWebhook(""))
    }

    @Test
    fun truncate_keepsUnderLimit() {
        val long = "啊".repeat(3000)
        val out = WeComNotifier.truncateWebhookText(long, 2000)
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= 2000 + 32)
        assertTrue(out.contains("截断") || out.length < long.length)
    }

    @Test
    fun buildPayload_isTextMsgtype() {
        val json = WeComNotifier.buildPayloadJson("hello")
        assertTrue(json.contains("\"msgtype\":\"text\"") || json.contains("\"msgtype\": \"text\""))
        assertTrue(json.contains("hello"))
    }
}
