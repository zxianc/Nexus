package com.nexus.wechat.bridge.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookAlerterTest {
    @Test
    fun detectsWeComBotUrl() {
        assertTrue(
            WebhookAlerter.isWeComBot(
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx",
            ),
        )
        assertFalse(WebhookAlerter.isWeComBot("https://example.com/hook"))
    }

    @Test
    fun parsesWeComErrcode() {
        assertTrue(WebhookAlerter.isWeComError("""{"errcode":40008,"errmsg":"invalid"}"""))
        assertFalse(WebhookAlerter.isWeComError("""{"errcode":0,"errmsg":"ok"}"""))
        assertFalse(WebhookAlerter.isWeComError(""))
    }
}
