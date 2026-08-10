package com.nexus.tim.bridge.uds

import com.nexus.tim.bridge.push.OutboundHub
import com.nexus.tim.bridge.state.BridgeState
import com.nexus.tim.bridge.state.MeInfo
import com.nexus.tim.bridge.store.BridgeEvent
import com.nexus.tim.bridge.store.EventStore
import com.nexus.tim.protocol.TimFrame
import com.nexus.tim.protocol.TimFrameTypes
import com.nexus.tim.protocol.TimMsgFields
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
    private val outbound: OutboundHub? = null,
) {
    @Volatile
    var writer: ((ByteArray) -> Unit)? = null

    @Volatile
    var onStatusChanged: (() -> Unit)? = null

    private var wasConnected = false
    private val pending = ConcurrentHashMap<String, CompletableFuture<SendResult>>()

    fun onConnected() {
        wasConnected = true
        state.hookConnected = true
        notifyStatus()
    }

    fun onDisconnected() {
        val hadHook = wasConnected || state.hookConnected
        state.hookConnected = false
        state.loggedIn = false
        state.recvHook = false
        state.me = MeInfo()
        pending.forEach { (_, fut) ->
            fut.complete(SendResult(ok = false, error = "hook_disconnected"))
        }
        pending.clear()
        if (hadHook) {
            wasConnected = false
            outbound?.alert("hook_disconnected", "TIM hook disconnected")
        }
        notifyStatus()
    }

    fun onFrame(type: Int, payload: ByteArray) {
        val text = payload.toString(Charsets.UTF_8)
        when (type) {
            TimFrameTypes.HELLO -> handleHello(text)
            TimFrameTypes.SEND_RESULT -> handleSendResult(text)
            TimFrameTypes.MSG_IN -> handleMsgIn(text)
            TimFrameTypes.PING -> writeType(TimFrameTypes.PONG, ByteArray(0))
            else -> Unit
        }
    }

    fun requestSendText(chatId: String, text: String, timeoutMs: Long = 15_000): SendResult {
        if (!state.hookConnected || writer == null) {
            return SendResult(ok = false, error = "hook_disconnected")
        }
        if (state.timVersion != null && state.timVersion != state.supportedVersion) {
            return SendResult(ok = false, error = "version_mismatch")
        }
        val requestId = UUID.randomUUID().toString()
        val fut = CompletableFuture<SendResult>()
        pending[requestId] = fut
        val body = JSONObject()
            .put(TimMsgFields.REQUEST_ID, requestId)
            .put(TimMsgFields.CHAT_ID, chatId)
            .put(TimMsgFields.TEXT, text)
        try {
            writeType(TimFrameTypes.SEND_TEXT, body.toString().toByteArray())
            return fut.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            pending.remove(requestId)
            return SendResult(ok = false, error = "timeout")
        } catch (t: Throwable) {
            pending.remove(requestId)
            return SendResult(ok = false, error = t.message ?: "send_failed")
        }
    }

    private fun handleHello(text: String) {
        val json = JSONObject(text)
        state.timVersion = json.optString(TimMsgFields.TIM_VERSION).ifEmpty { null }
        state.loggedIn = json.optBoolean(TimMsgFields.LOGGED_IN, false)
        state.recvHook = json.optBoolean("recv_hook", false)
        state.hookConnected = true
        wasConnected = true
        state.me = MeInfo(
            userId = json.optString(TimMsgFields.USER_ID, ""),
            nick = json.optString(TimMsgFields.NICK, ""),
        )
        val ver = state.timVersion
        if (ver != null && ver != state.supportedVersion) {
            outbound?.alert(
                "version_mismatch",
                "TIM version $ver != supported ${state.supportedVersion}",
                JSONObject()
                    .put("tim_version", ver)
                    .put("supported", state.supportedVersion),
            )
        }
        notifyStatus()
    }

    private fun handleSendResult(text: String) {
        val json = JSONObject(text)
        val requestId = json.optString(TimMsgFields.REQUEST_ID, "")
        val fut = pending.remove(requestId) ?: return
        val error = json.optString(TimMsgFields.ERROR).ifEmpty { null }
        val ok = json.optBoolean(TimMsgFields.OK, false)
        fut.complete(
            SendResult(
                ok = ok,
                msgId = json.optString(TimMsgFields.MSG_ID).ifEmpty { null },
                error = error,
            ),
        )
        if (!ok && !error.isNullOrBlank()) {
            outbound?.alert(
                "send_failed",
                "Send failed: $error",
                JSONObject().put("error", error).put("request_id", requestId),
            )
        }
    }

    private fun handleMsgIn(text: String) {
        val payload = JSONObject(text)
        eventStore.append(BridgeEvent(0, "message", payload))
        outbound?.publishEvent("message", payload)
    }

    private fun writeType(type: Int, payload: ByteArray) {
        val w = writer ?: return
        w(TimFrame.encode(type, payload))
    }

    private fun notifyStatus() {
        try {
            onStatusChanged?.invoke()
        } catch (_: Exception) {
        }
    }
}
