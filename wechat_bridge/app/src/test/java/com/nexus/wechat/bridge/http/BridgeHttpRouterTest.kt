package com.nexus.wechat.bridge.http

import com.nexus.wechat.bridge.state.BridgeState
import com.nexus.wechat.bridge.state.ChatInfo
import com.nexus.wechat.bridge.state.ContactInfo
import com.nexus.wechat.bridge.state.MeInfo
import com.nexus.wechat.bridge.state.MemberInfo
import com.nexus.wechat.bridge.store.EventStore
import com.nexus.wechat.bridge.store.MediaStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

class BridgeHttpRouterTest {
    @Test
    fun health_reportsDisconnectedHook() {
        val state = BridgeState(supportedVersion = "8.0.49")
        val body = BridgeHttpRouter(state, EventStore()).handle("GET", "/v1/health", emptyMap(), null)
        assertEquals(200, body.status)
        assertEquals("ok", body.json!!.getString("bridge"))
        assertEquals("disconnected", body.json!!.getString("hook"))
        assertEquals(false, body.json!!.getBoolean("logged_in"))
    }

    @Test
    fun events_emptyAfterZero() {
        val store = EventStore()
        val body = BridgeHttpRouter(BridgeState(supportedVersion = "x"), store)
            .handle("GET", "/v1/events", mapOf("after" to "0"), null)
        assertEquals(200, body.status)
        assertEquals(0, body.json!!.getJSONArray("events").length())
    }

    @Test
    fun me_and_chats_from_state() {
        val state = BridgeState(supportedVersion = "8.0.76").apply {
            hookConnected = true
            loggedIn = true
            me = MeInfo(userId = "wxid_bot", nick = "bot")
            chats = listOf(
                ChatInfo(
                    chatId = "wxid_a",
                    title = "Alice",
                    isGroup = false,
                ),
                ChatInfo(
                    chatId = "123@chatroom",
                    title = "Family",
                    isGroup = true,
                    members = listOf(MemberInfo("wxid_a", "Alice")),
                ),
            )
        }
        val router = BridgeHttpRouter(state, EventStore())
        val me = router.handle("GET", "/v1/me", emptyMap(), null)
        assertEquals(200, me.status)
        assertEquals("wxid_bot", me.json!!.getString("user_id"))

        val chats = router.handle("GET", "/v1/chats", emptyMap(), null)
        assertEquals(200, chats.status)
        assertEquals(2, chats.json!!.getJSONArray("chats").length())

        val members = router.handle("GET", "/v1/chats/123@chatroom/members", emptyMap(), null)
        assertEquals(200, members.status)
        assertEquals(1, members.json!!.getJSONArray("members").length())
        assertEquals("Alice", members.json!!.getJSONArray("members").getJSONObject(0).getString("display"))
    }

    @Test
    fun apiAuth_blocksWithoutToken() {
        val state = BridgeState(supportedVersion = "8.0.76").apply { hookConnected = true }
        val router = BridgeHttpRouter(
            state = state,
            eventStore = EventStore(),
            authEnabled = { true },
            authToken = { "s3cret" },
        )
        val denied = router.handle("GET", "/v1/me", emptyMap(), null)
        assertEquals(401, denied.status)
        assertEquals("unauthorized", denied.json!!.getString("error"))

        val health = router.handle("GET", "/v1/health", emptyMap(), null)
        assertEquals(200, health.status)

        val ok = router.handle(
            "GET",
            "/v1/me",
            emptyMap(),
            null,
            headers = mapOf("authorization" to "Bearer s3cret"),
        )
        assertEquals(200, ok.status)
    }

    @Test
    fun contacts_and_groups_from_state() {
        val state = BridgeState(supportedVersion = "8.0.76").apply {
            hookConnected = true
            contacts = listOf(
                ContactInfo("wxid_a", "Alice"),
                ContactInfo("wxid_b", "Bob"),
            )
            groups = listOf(
                ChatInfo(
                    chatId = "999@chatroom",
                    title = "OnlyInGroups",
                    isGroup = true,
                    members = listOf(MemberInfo("wxid_b", "Bob")),
                ),
            )
        }
        val router = BridgeHttpRouter(state, EventStore())
        val contacts = router.handle("GET", "/v1/contacts", emptyMap(), null)
        assertEquals(200, contacts.status)
        assertEquals(2, contacts.json!!.getJSONArray("contacts").length())
        assertEquals("Bob", contacts.json!!.getJSONArray("contacts").getJSONObject(1).getString("display"))

        val groups = router.handle("GET", "/v1/groups", emptyMap(), null)
        assertEquals(200, groups.status)
        assertEquals(1, groups.json!!.getJSONArray("groups").length())
        assertEquals("999@chatroom", groups.json!!.getJSONArray("groups").getJSONObject(0).getString("chat_id"))

        val members = router.handle("GET", "/v1/chats/999@chatroom/members", emptyMap(), null)
        assertEquals(200, members.status)
        assertEquals(1, members.json!!.getJSONArray("members").length())
    }

    @Test
    fun postText_requiresChatId() {
        val router = BridgeHttpRouter(
            state = BridgeState(supportedVersion = "8.0.76"),
            eventStore = EventStore(),
            sendText = { _, _, _ -> 200 to JSONObject().put("ok", true) },
        )
        val bad = router.handle(
            "POST",
            "/v1/messages/text",
            emptyMap(),
            """{"text":"hi"}""".toByteArray(),
        )
        assertEquals(400, bad.status)
        assertTrue(bad.json!!.getString("error").contains("missing"))
    }

    @Test
    fun postText_mapsUnknownChatTo400() {
        val router = BridgeHttpRouter(
            state = BridgeState(supportedVersion = "8.0.76"),
            eventStore = EventStore(),
            sendText = { _, _, _ ->
                400 to JSONObject().put("ok", false).put("error", "unknown_chat")
            },
        )
        val bad = router.handle(
            "POST",
            "/v1/messages/text",
            emptyMap(),
            """{"chat_id":"wxid_nope","text":"hi"}""".toByteArray(),
        )
        assertEquals(400, bad.status)
        assertEquals("unknown_chat", bad.json!!.getString("error"))
    }

    @Test
    fun postImage_and_getMedia_roundtrip() {
        val dir = Files.createTempDirectory("media-http-").toFile()
        try {
            val store = MediaStore(dir)
            val upload = File(dir, "tiny.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            var seenMediaId = ""
            var seenOriginal: Boolean? = null
            val router = BridgeHttpRouter(
                state = BridgeState(supportedVersion = "8.0.76"),
                eventStore = EventStore(),
                mediaStore = store,
                sendMedia = { _, _, _, _, mediaId, _, original ->
                    seenMediaId = mediaId
                    seenOriginal = original
                    200 to JSONObject().put("ok", true).put("msg_id", "m1")
                },
            )
            val post = router.handle(
                method = "POST",
                path = "/v1/messages/image",
                query = emptyMap(),
                body = null,
                form = mapOf("chat_id" to "filehelper", "name" to "tiny.png"),
                files = mapOf("file" to upload),
            )
            assertEquals(200, post.status)
            assertTrue(post.json!!.getBoolean("ok"))
            assertEquals(true, seenOriginal)
            assertEquals(true, post.json!!.getBoolean("original"))
            assertEquals(seenMediaId, post.json!!.getString("media_id"))
            assertTrue(post.json!!.getJSONObject("media").getString("url").contains(seenMediaId))

            var seenCompress: Boolean? = null
            val router2 = BridgeHttpRouter(
                state = BridgeState(supportedVersion = "8.0.76"),
                eventStore = EventStore(),
                mediaStore = store,
                sendMedia = { _, _, _, _, _, _, original ->
                    seenCompress = original
                    200 to JSONObject().put("ok", true).put("msg_id", "m2")
                },
            )
            val postCompress = router2.handle(
                method = "POST",
                path = "/v1/messages/image",
                query = emptyMap(),
                body = null,
                form = mapOf(
                    "chat_id" to "filehelper",
                    "name" to "tiny.png",
                    "original" to "false",
                ),
                files = mapOf("file" to upload),
            )
            assertEquals(200, postCompress.status)
            assertEquals(false, seenCompress)
            assertEquals(false, postCompress.json!!.getBoolean("original"))

            val get = router.handle("GET", "/v1/media/$seenMediaId", emptyMap(), null)
            assertEquals(200, get.status)
            assertNotNull(get.bytes)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), get.bytes)
        } finally {
            dir.deleteRecursively()
        }
    }
}
