package com.nexus.wechat.bridge.store

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventStoreTest {
    @Test
    fun appendAndPoll_afterCursor() {
        val store = EventStore()
        val c1 = store.append(BridgeEvent(0, "message", JSONObject().put("msg_id", "m1")))
        val c2 = store.append(BridgeEvent(0, "message", JSONObject().put("msg_id", "m2")))
        assertTrue(c2 > c1)
        assertEquals(0, store.after(c2).size)
        assertEquals(1, store.after(c1).size)
        assertEquals("m2", store.after(c1)[0].payload.getString("msg_id"))
    }
}
