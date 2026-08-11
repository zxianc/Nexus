package com.nexus.tim.bridge.http

import com.nexus.tim.bridge.state.BridgeState
import com.nexus.tim.bridge.state.MemberInfo
import com.nexus.tim.bridge.store.EventStore
import com.nexus.tim.bridge.store.MediaStore
import com.nexus.tim.bridge.store.SharedStaging
import com.nexus.tim.protocol.ImageSendOptions
import com.nexus.tim.protocol.TimMsgFields
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

data class RouterResponse(
    val status: Int,
    val json: JSONObject? = null,
    val bytes: ByteArray? = null,
    val contentType: String = "application/json; charset=utf-8",
    val fileName: String? = null,
) {
    companion object {
        fun json(status: Int, body: JSONObject) = RouterResponse(status = status, json = body)
    }
}

class TimHttpRouter(
    private val state: BridgeState,
    private val eventStore: EventStore,
    private val mediaStore: MediaStore? = null,
    private val sendText: ((chatId: String, text: String, ats: List<String>) -> Pair<Int, JSONObject>)? = null,
    private val sendMedia: ((chatId: String, kind: String, path: String, name: String, mediaId: String, dataB64: String, original: Boolean) -> Pair<Int, JSONObject>)? = null,
    private val fetchMembers: ((chatId: String) -> Pair<Int, JSONObject>)? = null,
    private val authEnabled: () -> Boolean = { false },
    private val authToken: () -> String = { "" },
) {
    fun handle(
        method: String,
        path: String,
        query: Map<String, String>,
        body: ByteArray?,
        form: Map<String, String> = emptyMap(),
        files: Map<String, File> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): RouterResponse {
        val isHealth = method == "GET" && path == "/v1/health"
        if (!isHealth &&
            !ApiTokenAuth.isAuthorized(
                enabled = authEnabled(),
                expectedToken = authToken(),
                headers = headers,
                query = query,
            )
        ) {
            return RouterResponse.json(
                401,
                JSONObject().put("ok", false).put("error", "unauthorized"),
            )
        }
        val membersMatch = MEMBERS_PATH.matchEntire(path)
        val mediaMatch = MEDIA_PATH.matchEntire(path)
        return when {
            isHealth -> health()
            method == "GET" && path == "/v1/me" -> me()
            method == "GET" && path == "/v1/contacts" -> contacts()
            method == "GET" && path == "/v1/groups" -> groups()
            method == "GET" && membersMatch != null -> members(membersMatch.groupValues[1])
            method == "GET" && mediaMatch != null -> getMedia(mediaMatch.groupValues[1])
            method == "GET" && path == "/v1/events" -> events(query)
            method == "POST" && path == "/v1/messages/text" -> postText(body)
            method == "POST" && path == "/v1/messages/image" -> postMedia("image", form, files)
            else -> RouterResponse.json(
                404,
                JSONObject().put("ok", false).put("error", "not_found"),
            )
        }
    }

    private fun health(): RouterResponse {
        val ver = state.timVersion
        val mismatch = ver != null && ver != state.supportedVersion
        return RouterResponse.json(
            200,
            JSONObject()
                .put("bridge", "ok")
                .put("hook", if (state.hookConnected) "connected" else "disconnected")
                .put("tim_version", ver)
                .put("supported_tim_version", state.supportedVersion)
                .put("logged_in", state.loggedIn)
                .put("tim_version_mismatch", mismatch)
                .put("recv_hook", state.recvHook)
                .put("user_id", state.me.userId)
                .put("nick", state.me.nick),
        )
    }

    private fun me(): RouterResponse {
        if (!state.hookConnected) {
            return RouterResponse.json(503, JSONObject().put("ok", false).put("error", "hook_disconnected"))
        }
        return RouterResponse.json(
            200,
            JSONObject()
                .put("user_id", state.me.userId)
                .put("nick", state.me.nick)
                .put("logged_in", state.loggedIn),
        )
    }

    private fun contacts(): RouterResponse {
        if (!state.hookConnected) {
            return RouterResponse.json(503, JSONObject().put("ok", false).put("error", "hook_unavailable"))
        }
        val arr = JSONArray()
        for (c in state.contacts) {
            arr.put(
                JSONObject()
                    .put(TimMsgFields.USER_ID, c.userId)
                    .put(TimMsgFields.DISPLAY, c.display),
            )
        }
        return RouterResponse.json(
            200,
            JSONObject().put("ok", true).put(TimMsgFields.CONTACTS, arr),
        )
    }

    private fun groups(): RouterResponse {
        if (!state.hookConnected) {
            return RouterResponse.json(503, JSONObject().put("ok", false).put("error", "hook_unavailable"))
        }
        val arr = JSONArray()
        for (g in state.groups) {
            arr.put(
                JSONObject()
                    .put(TimMsgFields.CHAT_ID, g.chatId)
                    .put(TimMsgFields.TITLE, g.title)
                    .put(TimMsgFields.IS_GROUP, true),
            )
        }
        return RouterResponse.json(
            200,
            JSONObject().put("ok", true).put(TimMsgFields.GROUPS, arr),
        )
    }

    private fun members(rawChatId: String): RouterResponse {
        if (!state.hookConnected) {
            return RouterResponse.json(503, JSONObject().put("ok", false).put("error", "hook_unavailable"))
        }
        val chatId = URLDecoder.decode(rawChatId, Charsets.UTF_8.name())
        if (!isTroopChatId(chatId)) {
            return RouterResponse.json(404, JSONObject().put("ok", false).put("error", "chat_not_found"))
        }
        val group = state.groups.firstOrNull { it.chatId == chatId }
            ?: return RouterResponse.json(404, JSONObject().put("ok", false).put("error", "chat_not_found"))
        val cached = state.membersOf(chatId)
        if (cached.isNotEmpty()) {
            return RouterResponse.json(
                200,
                JSONObject()
                    .put("ok", true)
                    .put(TimMsgFields.CHAT_ID, group.chatId)
                    .put(TimMsgFields.MEMBERS, membersArray(cached)),
            )
        }
        val handler = fetchMembers
            ?: return RouterResponse.json(503, JSONObject().put("ok", false).put("error", "members_not_ready"))
        val (status, result) = handler(chatId)
        return RouterResponse.json(status, result)
    }

    private fun membersArray(members: List<MemberInfo>): JSONArray {
        val arr = JSONArray()
        for (m in members) {
            arr.put(
                JSONObject()
                    .put(TimMsgFields.USER_ID, m.userId)
                    .put(TimMsgFields.DISPLAY, m.display),
            )
        }
        return arr
    }

    private fun isTroopChatId(chatId: String): Boolean {
        val t = chatId.trim()
        if (t.startsWith("troop:") || t.startsWith("g:")) {
            val digits = t.substringAfter(':')
            return digits.matches(TROOP_UIN_RE)
        }
        return t.matches(TROOP_UIN_RE)
    }

    private fun events(query: Map<String, String>): RouterResponse {
        val after = query["after"]?.toLongOrNull() ?: 0L
        val list = eventStore.after(after)
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("cursor", e.cursor)
                    .put("type", e.type)
                    .put("payload", e.payload),
            )
        }
        return RouterResponse.json(
            200,
            JSONObject()
                .put("events", arr)
                .put("cursor", eventStore.latestCursor()),
        )
    }

    private fun postText(body: ByteArray?): RouterResponse {
        val handler = sendText
            ?: return RouterResponse.json(503, JSONObject().put("ok", false).put("error", "send_not_ready"))
        if (body == null || body.isEmpty()) {
            return RouterResponse.json(400, JSONObject().put("ok", false).put("error", "empty_body"))
        }
        val json = try {
            JSONObject(body.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return RouterResponse.json(400, JSONObject().put("ok", false).put("error", "invalid_json"))
        }
        val chatId = json.optString(TimMsgFields.CHAT_ID, "")
        val text = json.optString(TimMsgFields.TEXT, "")
        if (chatId.isEmpty() || text.isEmpty()) {
            return RouterResponse.json(400, JSONObject().put("ok", false).put("error", "missing_fields"))
        }
        val atsJson = json.optJSONArray(TimMsgFields.ATS)
        val ats = ArrayList<String>()
        if (atsJson != null) {
            for (i in 0 until atsJson.length()) {
                ats.add(atsJson.getString(i))
            }
        }
        val (status, result) = handler(chatId, text, ats)
        return RouterResponse.json(status, result)
    }

    private fun postMedia(
        kind: String,
        form: Map<String, String>,
        files: Map<String, File>,
    ): RouterResponse {
        val store = mediaStore
            ?: return RouterResponse.json(503, JSONObject().put("ok", false).put("error", "media_not_ready"))
        val sender = sendMedia
            ?: return RouterResponse.json(503, JSONObject().put("ok", false).put("error", "send_not_ready"))
        val chatId = form[TimMsgFields.CHAT_ID].orEmpty()
        if (chatId.isEmpty()) {
            return RouterResponse.json(400, JSONObject().put("ok", false).put("error", "missing_fields"))
        }
        val upload = files["file"] ?: files["image"] ?: files.values.firstOrNull()
            ?: return RouterResponse.json(400, JSONObject().put("ok", false).put("error", "missing_file"))
        val name = form["name"].orEmpty().ifEmpty { upload.name }.ifEmpty { "image.jpg" }
        val bytes = try {
            upload.readBytes()
        } catch (_: Exception) {
            return RouterResponse.json(400, JSONObject().put("ok", false).put("error", "read_failed"))
        }
        val saved = try {
            store.saveOutgoing(bytes, name)
        } catch (_: MediaStore.TooLarge) {
            return RouterResponse.json(400, JSONObject().put("ok", false).put("error", "file_too_large"))
        }
        val mediaId = store.registerOutgoing(saved, kind, name)
        val stagedPath = try {
            SharedStaging.stageOutgoing(saved, name).absolutePath
        } catch (_: Throwable) {
            ""
        }
        val dataB64 = java.util.Base64.getEncoder().encodeToString(bytes)
        if (stagedPath.isEmpty() && dataB64.isEmpty()) {
            return RouterResponse.json(
                500,
                JSONObject().put("ok", false).put("error", "stage_failed"),
            )
        }
        val original = ImageSendOptions.parseOriginal(form[TimMsgFields.ORIGINAL])
        val (status, resp) = sender(chatId, kind, stagedPath, name, mediaId, dataB64, original)
        resp.put("media_id", mediaId)
        resp.put(TimMsgFields.ORIGINAL, original)
        if (!resp.has("media")) {
            resp.put(
                "media",
                JSONObject()
                    .put(TimMsgFields.KIND, kind)
                    .put("url", store.urlOf(mediaId))
                    .put(TimMsgFields.NAME, name),
            )
        }
        return RouterResponse.json(status, resp)
    }

    private fun getMedia(mediaId: String): RouterResponse {
        val store = mediaStore
            ?: return RouterResponse.json(503, JSONObject().put("ok", false).put("error", "media_not_ready"))
        val file = store.open(mediaId)
            ?: return RouterResponse.json(404, JSONObject().put("ok", false).put("error", "not_found"))
        val name = store.nameOf(mediaId) ?: file.name
        val kind = store.kindOf(mediaId) ?: "file"
        val ctype = when {
            kind == "image" || name.endsWith(".png", true) -> "image/png"
            name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
            name.endsWith(".gif", true) -> "image/gif"
            name.endsWith(".webp", true) -> "image/webp"
            else -> "application/octet-stream"
        }
        return RouterResponse(
            status = 200,
            bytes = file.readBytes(),
            contentType = ctype,
            fileName = name,
        )
    }

    companion object {
        private val MEMBERS_PATH = Regex("^/v1/chats/([^/]+)/members$")
        private val MEDIA_PATH = Regex("^/v1/media/([^/]+)$")
        private val TROOP_UIN_RE = Regex("\\d{5,12}")
    }
}
