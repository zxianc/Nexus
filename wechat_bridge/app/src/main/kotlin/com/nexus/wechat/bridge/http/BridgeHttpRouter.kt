package com.nexus.wechat.bridge.http

import com.nexus.wechat.bridge.state.BridgeState
import com.nexus.wechat.bridge.store.EventStore
import org.json.JSONArray
import org.json.JSONObject

data class RouterResponse(
    val status: Int,
    val json: JSONObject,
    val contentType: String = "application/json; charset=utf-8",
)

class BridgeHttpRouter(
    private val state: BridgeState,
    private val eventStore: EventStore,
) {
    fun handle(
        method: String,
        path: String,
        query: Map<String, String>,
        body: ByteArray?,
    ): RouterResponse {
        return when {
            method == "GET" && path == "/v1/health" -> health()
            method == "GET" && path == "/v1/events" -> events(query)
            else -> RouterResponse(
                404,
                JSONObject().put("ok", false).put("error", "not_found"),
            )
        }
    }

    private fun health(): RouterResponse {
        val mismatch = state.wechatVersion != null &&
            state.wechatVersion != state.supportedVersion &&
            state.supportedVersion != BridgeState.DEFAULT_SUPPORTED_VERSION
        val json = JSONObject()
            .put("bridge", "ok")
            .put("hook", if (state.hookConnected) "connected" else "disconnected")
            .put("wechat_version", state.wechatVersion)
            .put("supported_wechat_version", state.supportedVersion)
            .put("logged_in", state.loggedIn)
            .put("wechat_version_mismatch", mismatch)
        return RouterResponse(200, json)
    }

    private fun events(query: Map<String, String>): RouterResponse {
        val after = query["after"]?.toLongOrNull() ?: 0L
        val list = eventStore.after(after)
        val arr = JSONArray()
        for (ev in list) {
            arr.put(
                JSONObject()
                    .put("cursor", ev.cursor)
                    .put("type", ev.type)
                    .put("payload", ev.payload),
            )
        }
        val json = JSONObject()
            .put("cursor", eventStore.latestCursor())
            .put("events", arr)
        return RouterResponse(200, json)
    }
}
