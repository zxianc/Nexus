package com.nexus.tim.bridge.http

import com.nexus.tim.bridge.state.BridgeState
import com.nexus.tim.bridge.store.EventStore
import com.nexus.tim.protocol.TimMsgFields
import org.json.JSONArray
import org.json.JSONObject

data class RouterResponse(
    val status: Int,
    val json: JSONObject? = null,
) {
    companion object {
        fun json(status: Int, body: JSONObject) = RouterResponse(status = status, json = body)
    }
}

class TimHttpRouter(
    private val state: BridgeState,
    private val eventStore: EventStore,
    private val sendText: ((chatId: String, text: String) -> Pair<Int, JSONObject>)? = null,
    private val authEnabled: () -> Boolean = { false },
    private val authToken: () -> String = { "" },
) {
    fun handle(
        method: String,
        path: String,
        query: Map<String, String>,
        body: ByteArray?,
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
        return when {
            isHealth -> health()
            method == "GET" && path == "/v1/me" -> me()
            method == "GET" && path == "/v1/events" -> events(query)
            method == "POST" && path == "/v1/messages/text" -> postText(body)
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
        val (status, result) = handler(chatId, text)
        return RouterResponse.json(status, result)
    }
}
