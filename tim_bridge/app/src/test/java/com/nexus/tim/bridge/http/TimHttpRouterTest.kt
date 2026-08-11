package com.nexus.tim.bridge.http

import com.nexus.tim.bridge.state.BridgeState
import com.nexus.tim.bridge.state.ChatInfo
import com.nexus.tim.bridge.state.ContactInfo
import com.nexus.tim.bridge.state.MemberInfo
import com.nexus.tim.bridge.store.EventStore
import com.nexus.tim.bridge.store.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

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

    @Test
    fun members_from_cache() {
        val state = BridgeState(supportedVersion = "4.1.0").apply {
            hookConnected = true
            groups = listOf(
                ChatInfo(
                    chatId = "troop:723765339",
                    title = "TestGroup",
                    isGroup = true,
                    members = listOf(
                        MemberInfo("95019432", "Alice"),
                        MemberInfo("123456", "Bob"),
                    ),
                ),
            )
        }
        var fetched = false
        val router = TimHttpRouter(
            state,
            EventStore(),
            fetchMembers = {
                fetched = true
                500 to JSONObject().put("ok", false).put("error", "should_not_fetch")
            },
        )
        val body = router.handle("GET", "/v1/chats/troop%3A723765339/members", emptyMap(), null)
        assertEquals(200, body.status)
        assertEquals(true, body.json!!.getBoolean("ok"))
        assertEquals("troop:723765339", body.json!!.getString("chat_id"))
        assertEquals(2, body.json!!.getJSONArray("members").length())
        assertEquals("Alice", body.json!!.getJSONArray("members").getJSONObject(0).getString("display"))
        assertFalse(fetched)
    }

    @Test
    fun members_unknown_chat_404() {
        val state = BridgeState(supportedVersion = "4.1.0").apply {
            hookConnected = true
            groups = listOf(ChatInfo("troop:111", "G", isGroup = true))
        }
        val router = TimHttpRouter(state, EventStore())
        val body = router.handle("GET", "/v1/chats/troop:999/members", emptyMap(), null)
        assertEquals(404, body.status)
        assertEquals("chat_not_found", body.json!!.getString("error"))
    }

    @Test
    fun members_disconnected_503() {
        val state = BridgeState(supportedVersion = "4.1.0").apply {
            hookConnected = false
            groups = listOf(
                ChatInfo(
                    "troop:723765339",
                    "G",
                    isGroup = true,
                    members = listOf(MemberInfo("1", "A")),
                ),
            )
        }
        val router = TimHttpRouter(state, EventStore())
        val body = router.handle("GET", "/v1/chats/troop:723765339/members", emptyMap(), null)
        assertEquals(503, body.status)
        assertEquals("hook_unavailable", body.json!!.getString("error"))
    }

    @Test
    fun members_fetch_when_cache_empty() {
        val state = BridgeState(supportedVersion = "4.1.0").apply {
            hookConnected = true
            groups = listOf(ChatInfo("troop:723765339", "G", isGroup = true))
        }
        val router = TimHttpRouter(
            state,
            EventStore(),
            fetchMembers = { chatId ->
                200 to JSONObject()
                    .put("ok", true)
                    .put("chat_id", chatId)
                    .put(
                        "members",
                        JSONArray().put(
                            JSONObject().put("user_id", "9").put("display", "Z"),
                        ),
                    )
            },
        )
        val body = router.handle("GET", "/v1/chats/troop:723765339/members", emptyMap(), null)
        assertEquals(200, body.status)
        assertEquals("Z", body.json!!.getJSONArray("members").getJSONObject(0).getString("display"))
    }

    @Test
    fun postText_passesAtsIntoSendText() {
        var seenChat = ""
        var seenText = ""
        var seenAts: List<String> = emptyList()
        val router = TimHttpRouter(
            state = BridgeState(supportedVersion = "4.1.0"),
            eventStore = EventStore(),
            sendText = { chatId, text, ats ->
                seenChat = chatId
                seenText = text
                seenAts = ats
                200 to JSONObject().put("ok", true).put("msg_id", "m1")
            },
        )
        val body = router.handle(
            "POST",
            "/v1/messages/text",
            emptyMap(),
            """{"chat_id":"troop:723765339","text":"hi","ats":["95019432","notify@all"]}""".toByteArray(),
        )
        assertEquals(200, body.status)
        assertEquals(true, body.json!!.getBoolean("ok"))
        assertEquals("troop:723765339", seenChat)
        assertEquals("hi", seenText)
        assertEquals(listOf("95019432", "notify@all"), seenAts)
    }

    @Test
    fun postText_omittedAts_passesEmptyList() {
        var seenAts: List<String>? = null
        val router = TimHttpRouter(
            state = BridgeState(supportedVersion = "4.1.0"),
            eventStore = EventStore(),
            sendText = { _, _, ats ->
                seenAts = ats
                200 to JSONObject().put("ok", true)
            },
        )
        val body = router.handle(
            "POST",
            "/v1/messages/text",
            emptyMap(),
            """{"chat_id":"12345","text":"hi"}""".toByteArray(),
        )
        assertEquals(200, body.status)
        assertEquals(emptyList<String>(), seenAts)
    }

    @Test
    fun postImage_missingFile_400() {
        val dir = Files.createTempDirectory("tim-media-").toFile()
        try {
            val router = TimHttpRouter(
                state = BridgeState(supportedVersion = "4.1.0"),
                eventStore = EventStore(),
                mediaStore = MediaStore(dir),
                sendMedia = { _, _, _, _, _, _, _ ->
                    200 to JSONObject().put("ok", true)
                },
            )
            val body = router.handle(
                method = "POST",
                path = "/v1/messages/image",
                query = emptyMap(),
                body = null,
                form = mapOf("chat_id" to "12345"),
                files = emptyMap(),
            )
            assertEquals(400, body.status)
            assertEquals("missing_file", body.json!!.getString("error"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun getMedia_notFound_404() {
        val dir = Files.createTempDirectory("tim-media-").toFile()
        try {
            val router = TimHttpRouter(
                state = BridgeState(supportedVersion = "4.1.0"),
                eventStore = EventStore(),
                mediaStore = MediaStore(dir),
            )
            val body = router.handle("GET", "/v1/media/doesnotexist", emptyMap(), null)
            assertEquals(404, body.status)
            assertEquals("not_found", body.json!!.getString("error"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun postImage_and_getMedia_roundtrip() {
        val dir = Files.createTempDirectory("tim-media-").toFile()
        try {
            val store = MediaStore(dir)
            val upload = File(dir, "tiny.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            var seenMediaId = ""
            var seenOriginal: Boolean? = null
            val router = TimHttpRouter(
                state = BridgeState(supportedVersion = "4.1.0"),
                eventStore = EventStore(),
                mediaStore = store,
                sendMedia = { _, kind, _, _, mediaId, _, original ->
                    assertEquals("image", kind)
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
                form = mapOf("chat_id" to "12345", "name" to "tiny.png"),
                files = mapOf("file" to upload),
            )
            assertEquals(200, post.status)
            assertTrue(post.json!!.getBoolean("ok"))
            assertEquals(true, seenOriginal)
            assertEquals(true, post.json!!.getBoolean("original"))
            assertEquals(seenMediaId, post.json!!.getString("media_id"))
            assertTrue(post.json!!.getJSONObject("media").getString("url").contains(seenMediaId))

            val get = router.handle("GET", "/v1/media/$seenMediaId", emptyMap(), null)
            assertEquals(200, get.status)
            assertNotNull(get.bytes)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), get.bytes)
        } finally {
            dir.deleteRecursively()
        }
    }
}
