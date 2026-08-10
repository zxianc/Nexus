package com.nexus.tim.bridge.push

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RedisEventPayloadTest {
    @Test
    fun streamFields_shape() {
        val fields = RedisEventPayload.streamFields(
            type = "message",
            payload = JSONObject().put("chat_id", "1").put("text", "hi"),
            tsMs = 123L,
        )
        assertEquals("message", fields["type"])
        assertEquals("123", fields["ts"])
        val data = JSONObject(fields["data"])
        assertEquals("1", data.getString("chat_id"))
        assertEquals("hi", data.getString("text"))
    }
}
