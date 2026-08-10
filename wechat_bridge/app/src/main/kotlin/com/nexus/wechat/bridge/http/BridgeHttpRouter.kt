package com.nexus.wechat.bridge.http

import com.nexus.wechat.bridge.state.BridgeState
import com.nexus.wechat.bridge.store.EventStore
import com.nexus.wechat.bridge.store.MediaStore
import com.nexus.wechat.bridge.store.SharedStaging
import com.nexus.wechat.protocol.ImageSendOptions
import com.nexus.wechat.protocol.WechatMsgFields
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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

class BridgeHttpRouter(
    private val state: BridgeState,
    private val eventStore: EventStore,
    private val mediaStore: MediaStore? = null,
    private val sendText: ((chatId: String, text: String, ats: List<String>) -> Pair<Int, JSONObject>)? = null,
    private val sendMedia: ((chatId: String, kind: String, path: String, name: String, mediaId: String, dataB64: String, original: Boolean) -> Pair<Int, JSONObject>)? = null,
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
        val membersMatch = MEMBERS_PATH.matchEntire(path)
        val mediaMatch = MEDIA_PATH.matchEntire(path)
        // Health stays open so LAN probes work without leaking chat data.
        val needsAuth = !(method == "GET" && path == "/v1/health")
        if (needsAuth &&
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
        return when {
            method == "GET" && path == "/v1/health" -> health()
            method == "GET" && path == "/v1/me" -> me()
            method == "GET" && path == "/v1/chats" -> chats()
            method == "GET" && path == "/v1/contacts" -> contacts()
            method == "GET" && path == "/v1/groups" -> groups()
            method == "GET" && membersMatch != null -> members(membersMatch.groupValues[1])
            method == "GET" && mediaMatch != null -> getMedia(mediaMatch.groupValues[1])
            method == "GET" && path == "/v1/events" -> events(query)
            method == "POST" && path == "/v1/messages/text" -> postText(body)
            method == "POST" && path == "/v1/messages/image" -> postMedia("image", form, files)
            method == "POST" && path == "/v1/messages/file" -> postMedia("file", form, files)
            else -> RouterResponse.json(
                404,
                JSONObject().put("ok", false).put("error", "not_found"),
            )
        }
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
        val chatId = json.optString(WechatMsgFields.CHAT_ID, "")
        val text = json.optString(WechatMsgFields.TEXT, "")
        if (chatId.isEmpty() || text.isEmpty()) {
            return RouterResponse.json(400, JSONObject().put("ok", false).put("error", "missing_fields"))
        }
        val atsJson = json.optJSONArray(WechatMsgFields.ATS)
        val ats = ArrayList<String>()
        if (atsJson != null) {
            for (i in 0 until atsJson.length()) {
                ats.add(atsJson.getString(i))
            }
        }
        val (status, resp) = handler(chatId, text, ats)
        return RouterResponse.json(status, resp)
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
        val chatId = form[WechatMsgFields.CHAT_ID].orEmpty()
        if (chatId.isEmpty()) {
            return RouterResponse.json(400, JSONObject().put("ok", false).put("error", "missing_fields"))
        }
        val upload = files["file"] ?: files["image"] ?: files.values.firstOrNull()
            ?: return RouterResponse.json(400, JSONObject().put("ok", false).put("error", "missing_file"))
        val name = form["name"].orEmpty().ifEmpty { upload.name }.ifEmpty { if (kind == "image") "image.jpg" else "file.bin" }
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
        // Prefer shared staging; always include base64 so Hook can materialize inside WeChat.
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
        val original = if (kind == "image") {
            ImageSendOptions.parseOriginal(form[WechatMsgFields.ORIGINAL])
        } else {
            true
        }
        val (status, resp) = sender(chatId, kind, stagedPath, name, mediaId, dataB64, original)
        resp.put("media_id", mediaId)
        if (kind == "image") {
            resp.put(WechatMsgFields.ORIGINAL, original)
        }
        if (!resp.has("media")) {
            resp.put(
                "media",
                JSONObject()
                    .put(WechatMsgFields.KIND, kind)
                    .put("url", store.urlOf(mediaId))
                    .put(WechatMsgFields.NAME, name),
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

    private fun health(): RouterResponse {
        val mismatch = state.wechatVersion != null &&
            state.wechatVersion != state.supportedVersion
        val json = JSONObject()
            .put("bridge", "ok")
            .put("hook", if (state.hookConnected) "connected" else "disconnected")
            .put("wechat_version", state.wechatVersion)
            .put("supported_wechat_version", state.supportedVersion)
            .put("logged_in", state.loggedIn)
            .put("wechat_version_mismatch", mismatch)
            .put(WechatMsgFields.USER_ID, state.me.userId)
            .put(WechatMsgFields.NICK, state.me.nick)
        return RouterResponse.json(200, json)
    }

    private fun me(): RouterResponse {
        if (!state.hookConnected) {
            return RouterResponse.json(503, JSONObject().put("ok", false).put("error", "hook_unavailable"))
        }
        val json = JSONObject()
            .put("ok", true)
            .put(WechatMsgFields.USER_ID, state.me.userId)
            .put(WechatMsgFields.NICK, state.me.nick)
            .put(WechatMsgFields.LOGGED_IN, state.loggedIn)
            .put(WechatMsgFields.WECHAT_VERSION, state.wechatVersion)
        return RouterResponse.json(200, json)
    }

    private fun chats(): RouterResponse {
        if (!state.hookConnected) {
            return RouterResponse.json(503, JSONObject().put("ok", false).put("error", "hook_unavailable"))
        }
        val arr = JSONArray()
        for (c in state.chats) {
            arr.put(
                JSONObject()
                    .put(WechatMsgFields.CHAT_ID, c.chatId)
                    .put(WechatMsgFields.TITLE, c.title)
                    .put(WechatMsgFields.IS_GROUP, c.isGroup)
                    .put(WechatMsgFields.MEMBERS, membersArray(c.members)),
            )
        }
        return RouterResponse.json(
            200,
            JSONObject().put("ok", true).put(WechatMsgFields.CHATS, arr),
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
                    .put(WechatMsgFields.USER_ID, c.userId)
                    .put(WechatMsgFields.DISPLAY, c.display),
            )
        }
        return RouterResponse.json(
            200,
            JSONObject().put("ok", true).put(WechatMsgFields.CONTACTS, arr),
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
                    .put(WechatMsgFields.CHAT_ID, g.chatId)
                    .put(WechatMsgFields.TITLE, g.title)
                    .put(WechatMsgFields.IS_GROUP, true),
            )
        }
        return RouterResponse.json(
            200,
            JSONObject().put("ok", true).put(WechatMsgFields.GROUPS, arr),
        )
    }

    private fun members(chatId: String): RouterResponse {
        if (!state.hookConnected) {
            return RouterResponse.json(503, JSONObject().put("ok", false).put("error", "hook_unavailable"))
        }
        val decoded = java.net.URLDecoder.decode(chatId, Charsets.UTF_8.name())
        val chat = state.chats.firstOrNull { it.chatId == decoded }
            ?: state.groups.firstOrNull { it.chatId == decoded }
            ?: return RouterResponse.json(404, JSONObject().put("ok", false).put("error", "chat_not_found"))
        return RouterResponse.json(
            200,
            JSONObject()
                .put("ok", true)
                .put(WechatMsgFields.CHAT_ID, chat.chatId)
                .put(WechatMsgFields.MEMBERS, membersArray(chat.members)),
        )
    }

    private fun membersArray(members: List<com.nexus.wechat.bridge.state.MemberInfo>): JSONArray {
        val arr = JSONArray()
        for (m in members) {
            arr.put(
                JSONObject()
                    .put(WechatMsgFields.USER_ID, m.userId)
                    .put(WechatMsgFields.DISPLAY, m.display),
            )
        }
        return arr
    }

    private fun events(query: Map<String, String>): RouterResponse {
        val after = query["after"]?.toLongOrNull() ?: 0L
        val list = eventStore.after(after)
        val arr = JSONArray()
        for (ev in list) {
            val payload = JSONObject(ev.payload.toString()) // defensive copy
            enrichMediaUrl(payload)
            arr.put(
                JSONObject()
                    .put("cursor", ev.cursor)
                    .put("type", ev.type)
                    .put("payload", payload),
            )
        }
        val json = JSONObject()
            .put("cursor", eventStore.latestCursor())
            .put("events", arr)
        return RouterResponse.json(200, json)
    }

    private fun enrichMediaUrl(payload: JSONObject) {
        val mediaId = payload.optString(WechatMsgFields.MEDIA_ID, "")
        if (mediaId.isEmpty() || mediaStore == null) return
        if (payload.has("media")) return
        val kind = payload.optString(WechatMsgFields.MEDIA_KIND, "file")
        val name = payload.optString(WechatMsgFields.MEDIA_NAME, mediaId)
        payload.put(
            "media",
            JSONObject()
                .put(WechatMsgFields.KIND, kind)
                .put("url", mediaStore.urlOf(mediaId))
                .put(WechatMsgFields.NAME, name),
        )
    }

    companion object {
        private val MEMBERS_PATH = Regex("^/v1/chats/([^/]+)/members$")
        private val MEDIA_PATH = Regex("^/v1/media/([^/]+)$")
    }
}
