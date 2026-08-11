package com.nexus.tim.bridge.http

import com.nexus.tim.bridge.state.BridgeState
import com.nexus.tim.bridge.state.ChatInfo
import com.nexus.tim.bridge.state.ContactInfo
import com.nexus.tim.bridge.store.EventStore
import org.junit.Assert.assertEquals
import org.junit.Test

class TimHttpRouterTest {
    @Test
    fun health_disconnected() {
        val state = BridgeState(supportedVersion = "4.1.0")
        val router = TimHttpRouter(state, EventStore())
        val body = router.handle("GET", "/v1/health", emptyMap(), null)
        assertEquals(200, body.status)
        assertEquals("disconnected", body.json!!.getString("hook"))
        assertEquals("4.1.0", body.json!!.getString("supported_tim_version"))
    }

    @Test
    fun health_connected() {
        val state = BridgeState(supportedVersion = "4.1.0").apply {
            hookConnected = true
            loggedIn = true
            timVersion = "4.1.0"
            me = com.nexus.tim.bridge.state.MeInfo("12345", "nick")
        }
        val router = TimHttpRouter(state, EventStore())
        val body = router.handle("GET", "/v1/health", emptyMap(), null)
        assertEquals("connected", body.json!!.getString("hook"))
        assertEquals(true, body.json!!.getBoolean("logged_in"))
        assertEquals("12345", body.json!!.getString("user_id"))
    }

    @Test
    fun auth_blocksMe_allowsHealth() {
        val state = BridgeState(supportedVersion = "4.1.0")
        val router = TimHttpRouter(
            state,
            EventStore(),
            authEnabled = { true },
            authToken = { "tok" },
        )
        assertEquals(200, router.handle("GET", "/v1/health", emptyMap(), null).status)
        assertEquals(401, router.handle("GET", "/v1/me", emptyMap(), null).status)
        assertEquals(
            503,
            router.handle(
                "GET",
                "/v1/me",
                emptyMap(),
                null,
                headers = mapOf("authorization" to "Bearer tok"),
            ).status,
        )
    }

    @Test
    fun contacts_and_groups_from_state() {
        val state = BridgeState(supportedVersion = "4.1.0").apply {
            hookConnected = true
            contacts = listOf(
                ContactInfo("95019432", "FriendA"),
                ContactInfo("123456", "FriendB"),
            )
            groups = listOf(
                ChatInfo("troop:723765339", "TestGroup", isGroup = true),
            )
        }
        val router = TimHttpRouter(state, EventStore())
        val contacts = router.handle("GET", "/v1/contacts", emptyMap(), null)
        assertEquals(200, contacts.status)
        assertEquals(2, contacts.json!!.getJSONArray("contacts").length())
        assertEquals("FriendB", contacts.json!!.getJSONArray("contacts").getJSONObject(1).getString("display"))

        val groups = router.handle("GET", "/v1/groups", emptyMap(), null)
        assertEquals(200, groups.status)
        assertEquals(1, groups.json!!.getJSONArray("groups").length())
        assertEquals("troop:723765339", groups.json!!.getJSONArray("groups").getJSONObject(0).getString("chat_id"))
        assertEquals(true, groups.json!!.getJSONArray("groups").getJSONObject(0).getBoolean("is_group"))
    }
}
