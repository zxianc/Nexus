package com.nexus.tim.bridge.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookAlerterTest {
    @Test
    fun weComBot_detect() {
        assertTrue(
            WebhookAlerter.isWeComBot(
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc",
            ),
        )
        assertFalse(WebhookAlerter.isWeComBot("https://example.com/hook"))
    }

    @Test
    fun weComError_detect() {
        assertTrue(WebhookAlerter.isWeComError("""{"errcode":40008,"errmsg":"invalid"}"""))
        assertFalse(WebhookAlerter.isWeComError("""{"errcode":0,"errmsg":"ok"}"""))
        assertFalse(WebhookAlerter.isWeComError(""))
    }
}
