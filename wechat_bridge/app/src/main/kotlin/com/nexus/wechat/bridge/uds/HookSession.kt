package com.nexus.wechat.bridge.uds

import com.nexus.wechat.bridge.state.BridgeState
import com.nexus.wechat.bridge.store.BridgeEvent
import com.nexus.wechat.bridge.store.EventStore
import com.nexus.wechat.protocol.WechatFrame
import com.nexus.wechat.protocol.WechatFrameTypes
import com.nexus.wechat.protocol.WechatMsgFields
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class SendResult(
    val ok: Boolean,
    val msgId: String? = null,
    val error: String? = null,
)

class HookSession(
    private val state: BridgeState,
    private val eventStore: EventStore,
) {
    @Volatile
    var writer: ((ByteArray) -> Unit)? = null

    private val pending = ConcurrentHashMap<String, CompletableFuture<SendResult>>()

    fun onConnected() {
        state.hookConnected = true
    }

    fun onDisconnected() {
        state.hookConnected = false
        state.loggedIn = false
        pending.forEach { (_, fut) ->
            fut.complete(SendResult(ok = false, error = "hook_disconnected"))
        }
        pending.clear()
    }

    fun onFrame(type: Int, payload: ByteArray) {
        val text = payload.toString(Charsets.UTF_8)
        when (type) {
            WechatFrameTypes.HELLO -> handleHello(text)
            WechatFrameTypes.SEND_RESULT -> handleSendResult(text)
            WechatFrameTypes.MSG_IN -> handleMsgIn(text)
            WechatFrameTypes.PONG -> Unit
            WechatFrameTypes.PING -> writeType(WechatFrameTypes.PONG, ByteArray(0))
            else -> Unit
        }
    }

    fun requestSendText(
        chatId: String,
        text: String,
        ats: List<String>,
        timeoutMs: Long = 15_000,
    ): SendResult {
        if (!state.hookConnected || writer == null) {
            return SendResult(ok = false, error = "hook_unavailable")
        }
        if (state.supportedVersion != BridgeState.DEFAULT_SUPPORTED_VERSION &&
            state.wechatVersion != null &&
            state.wechatVersion != state.supportedVersion
        ) {
            return SendResult(ok = false, error = "version_mismatch")
        }
        if (!state.loggedIn) {
            return SendResult(ok = false, error = "not_logged_in")
        }
        val requestId = UUID.randomUUID().toString()
        val fut = CompletableFuture<SendResult>()
        pending[requestId] = fut
        val body = JSONObject()
            .put(WechatMsgFields.REQUEST_ID, requestId)
            .put(WechatMsgFields.CHAT_ID, chatId)
            .put(WechatMsgFields.TEXT, text)
            .put(WechatMsgFields.ATS, JSONArray(ats))
        writeType(WechatFrameTypes.SEND_TEXT, body.toString().toByteArray(Charsets.UTF_8))
        return try {
            fut.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            pending.remove(requestId)
            SendResult(ok = false, error = "timeout")
        } catch (e: Exception) {
            pending.remove(requestId)
            SendResult(ok = false, error = e.message ?: "send_failed")
        }
    }

    private fun handleHello(text: String) {
        val json = JSONObject(text)
        state.wechatVersion = json.optString(WechatMsgFields.WECHAT_VERSION).ifEmpty { null }
        state.loggedIn = json.optBoolean(WechatMsgFields.LOGGED_IN, false)
        state.hookConnected = true
    }

    private fun handleSendResult(text: String) {
        val json = JSONObject(text)
        val requestId = json.optString(WechatMsgFields.REQUEST_ID, "")
        val fut = pending.remove(requestId) ?: return
        val msgId = json.optString(WechatMsgFields.MSG_ID).ifEmpty { null }
        val error = json.optString(WechatMsgFields.ERROR).ifEmpty { null }
        fut.complete(
            SendResult(
                ok = json.optBoolean(WechatMsgFields.OK, false),
                msgId = msgId,
                error = error,
            ),
        )
    }

    private fun handleMsgIn(text: String) {
        val payload = JSONObject(text)
        eventStore.append(BridgeEvent(0, "message", payload))
    }

    private fun writeType(type: Int, payload: ByteArray) {
        val w = writer ?: return
        w(WechatFrame.encode(type, payload))
    }
}
