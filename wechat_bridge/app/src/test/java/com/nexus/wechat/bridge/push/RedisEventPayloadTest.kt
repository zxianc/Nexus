package com.nexus.wechat.bridge.push

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RedisEventPayloadTest {
    @Test
    fun buildsFlatStreamFields() {
        val payload = JSONObject().put("chat_id", "wxid_a").put("text", "hi")
        val fields = RedisEventPayload.streamFields(
            type = "message",
            payload = payload,
            tsMs = 1_700_000_000_000L,
        )
        assertEquals("message", fields["type"])
        assertEquals("1700000000000", fields["ts"])
        assertTrue(fields["data"]!!.contains("wxid_a"))
        assertTrue(fields["data"]!!.contains("hi"))
    }
}
