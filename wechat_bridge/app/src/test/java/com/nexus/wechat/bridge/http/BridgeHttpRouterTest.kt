package com.nexus.wechat.bridge.http

import com.nexus.wechat.bridge.state.BridgeState
import com.nexus.wechat.bridge.store.EventStore
import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeHttpRouterTest {
    @Test
    fun health_reportsDisconnectedHook() {
        val state = BridgeState(supportedVersion = "8.0.49")
        val body = BridgeHttpRouter(state, EventStore()).handle("GET", "/v1/health", emptyMap(), null)
        assertEquals(200, body.status)
        assertEquals("ok", body.json.getString("bridge"))
        assertEquals("disconnected", body.json.getString("hook"))
        assertEquals(false, body.json.getBoolean("logged_in"))
    }

    @Test
    fun events_emptyAfterZero() {
        val store = EventStore()
        val body = BridgeHttpRouter(BridgeState(supportedVersion = "x"), store)
            .handle("GET", "/v1/events", mapOf("after" to "0"), null)
        assertEquals(200, body.status)
        assertEquals(0, body.json.getJSONArray("events").length())
    }
}
