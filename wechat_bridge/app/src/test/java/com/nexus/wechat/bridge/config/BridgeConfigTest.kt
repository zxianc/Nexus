package com.nexus.wechat.bridge.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeConfigTest {
    @Test
    fun normalize_trimsAndDefaults() {
        val c = BridgeConfig(
            redisHost = " 192.168.1.10 ",
            redisPort = 0,
            redisPassword = " secret ",
            redisStreamKey = " ",
            redisEnabled = true,
            webhookUrl = " https://hook.example/x ",
            webhookEnabled = true,
        ).normalized()
        assertEquals("192.168.1.10", c.redisHost)
        assertEquals(BridgeConfig.DEFAULT_REDIS_PORT, c.redisPort)
        assertEquals("secret", c.redisPassword)
        assertEquals(BridgeConfig.DEFAULT_STREAM_KEY, c.redisStreamKey)
        assertEquals("https://hook.example/x", c.webhookUrl)
        assertTrue(c.redisReady)
        assertTrue(c.webhookReady)
    }

    @Test
    fun redisReady_requiresHostWhenEnabled() {
        assertFalse(
            BridgeConfig(redisHost = "", redisEnabled = true).normalized().redisReady,
        )
        assertTrue(
            BridgeConfig(redisHost = "10.0.0.1", redisEnabled = true).normalized().redisReady,
        )
        assertFalse(
            BridgeConfig(redisHost = "10.0.0.1", redisEnabled = false).normalized().redisReady,
        )
    }

    @Test
    fun webhookReady_requiresHttpUrl() {
        assertFalse(
            BridgeConfig(webhookUrl = "ftp://x", webhookEnabled = true).normalized().webhookReady,
        )
        assertTrue(
            BridgeConfig(webhookUrl = "http://192.168.1.2:9000/alert", webhookEnabled = true)
                .normalized().webhookReady,
        )
    }
}
