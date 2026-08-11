package com.nexus.tim.bridge.uds

import com.nexus.tim.bridge.push.OutboundHub
import com.nexus.tim.bridge.state.BridgeState
import com.nexus.tim.bridge.state.ChatInfo
import com.nexus.tim.bridge.state.ContactInfo
import com.nexus.tim.bridge.state.MeInfo
import com.nexus.tim.bridge.state.MemberInfo
import com.nexus.tim.bridge.store.BridgeEvent
import com.nexus.tim.bridge.store.EventStore
import com.nexus.tim.protocol.TimFrame
import com.nexus.tim.protocol.TimFrameTypes
import com.nexus.tim.protocol.TimMsgFields
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

data class MembersResult(
    val ok: Boolean,
    val members: List<MemberInfo> = emptyList(),
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
    private val pendingMembers = ConcurrentHashMap<String, CompletableFuture<MembersResult>>()

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
        state.contacts = emptyList()
        // Drop groups (and any cached members).
        state.groups = emptyList()
        pending.forEach { (_, fut) ->
            fut.complete(SendResult(ok = false, error = "hook_disconnected"))
        }
        pending.clear()
        pendingMembers.forEach { (_, fut) ->
            fut.complete(MembersResult(ok = false, error = "hook_disconnected"))
        }
        pendingMembers.clear()
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
            TimFrameTypes.LIST_MEMBERS_RESULT -> handleMembersResult(text)
            TimFrameTypes.MSG_IN -> handleMsgIn(text)
            TimFrameTypes.PING -> writeType(TimFrameTypes.PONG, ByteArray(0))
            else -> Unit
        }
    }

    fun requestSendText(
        chatId: String,
        text: String,
        ats: List<String> = emptyList(),
        timeoutMs: Long = 15_000,
    ): SendResult {
        val body = JSONObject()
            .put(TimMsgFields.CHAT_ID, chatId)
            .put(TimMsgFields.TEXT, text)
            .put(TimMsgFields.ATS, JSONArray(ats))
        return requestSend(TimFrameTypes.SEND_TEXT, body, timeoutMs)
    }

    fun requestSendMedia(
        chatId: String,
        kind: String,
        path: String,
        name: String,
        mediaId: String = "",
        dataB64: String = "",
        original: Boolean = true,
        timeoutMs: Long = 60_000,
    ): SendResult {
        val type = if (kind == "image") TimFrameTypes.SEND_IMAGE else TimFrameTypes.SEND_FILE
        val body = JSONObject()
            .put(TimMsgFields.CHAT_ID, chatId)
            .put(TimMsgFields.PATH, path)
            .put(TimMsgFields.NAME, name)
            .put(TimMsgFields.KIND, kind)
            .put(TimMsgFields.MEDIA_ID, mediaId)
            .put(TimMsgFields.DATA_B64, dataB64)
            .put(TimMsgFields.ORIGINAL, original)
        return requestSend(type, body, timeoutMs)
    }

    private fun requestSend(type: Int, body: JSONObject, timeoutMs: Long): SendResult {
        if (!state.hookConnected || writer == null) {
            return SendResult(ok = false, error = "hook_disconnected")
        }
        if (state.timVersion != null && state.timVersion != state.supportedVersion) {
            return SendResult(ok = false, error = "version_mismatch")
        }
        val requestId = UUID.randomUUID().toString()
        val fut = CompletableFuture<SendResult>()
        pending[requestId] = fut
        body.put(TimMsgFields.REQUEST_ID, requestId)
        try {
            writeType(type, body.toString().toByteArray())
            return fut.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            pending.remove(requestId)
            return SendResult(ok = false, error = "timeout")
        } catch (t: Throwable) {
            pending.remove(requestId)
            return SendResult(ok = false, error = t.message ?: "send_failed")
        }
    }

    fun requestMembers(chatId: String, timeoutMs: Long = 15_000): MembersResult {
        if (!state.hookConnected || writer == null) {
            return MembersResult(ok = false, error = "hook_disconnected")
        }
        val requestId = UUID.randomUUID().toString()
        val fut = CompletableFuture<MembersResult>()
        pendingMembers[requestId] = fut
        val body = JSONObject()
            .put(TimMsgFields.REQUEST_ID, requestId)
            .put(TimMsgFields.CHAT_ID, chatId)
        try {
            writeType(TimFrameTypes.LIST_MEMBERS, body.toString().toByteArray())
            return fut.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            pendingMembers.remove(requestId)
            return MembersResult(ok = false, error = "timeout")
        } catch (t: Throwable) {
            pendingMembers.remove(requestId)
            return MembersResult(ok = false, error = t.message ?: "members_failed")
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
        state.contacts = parseContacts(json.optJSONArray(TimMsgFields.CONTACTS))
        state.groups = mergeGroupsPreservingMembers(
            parseGroups(json.optJSONArray(TimMsgFields.GROUPS)),
            state.groups,
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

    private fun parseContacts(arr: JSONArray?): List<ContactInfo> {
        if (arr == null) return emptyList()
        val out = ArrayList<ContactInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val userId = c.optString(TimMsgFields.USER_ID, "")
            if (userId.isEmpty()) continue
            out.add(
                ContactInfo(
                    userId = userId,
                    display = c.optString(TimMsgFields.DISPLAY, userId),
                ),
            )
        }
        return out
    }

    private fun parseGroups(arr: JSONArray?): List<ChatInfo> {
        if (arr == null) return emptyList()
        val out = ArrayList<ChatInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val chatId = c.optString(TimMsgFields.CHAT_ID, "")
            if (chatId.isEmpty()) continue
            out.add(
                ChatInfo(
                    chatId = chatId,
                    title = c.optString(TimMsgFields.TITLE, chatId),
                    isGroup = true,
                ),
            )
        }
        return out
    }

    /** HELLO group refresh must not drop on-demand member caches. */
    private fun mergeGroupsPreservingMembers(
        incoming: List<ChatInfo>,
        previous: List<ChatInfo>,
    ): List<ChatInfo> {
        if (previous.isEmpty() || incoming.isEmpty()) return incoming
        val prevMembers = previous.associate { it.chatId to it.members }
        return incoming.map { g ->
            val cached = prevMembers[g.chatId]
            if (cached.isNullOrEmpty()) g else g.copy(members = cached)
        }
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

    private fun handleMembersResult(text: String) {
        val json = JSONObject(text)
        val requestId = json.optString(TimMsgFields.REQUEST_ID, "")
        val chatId = json.optString(TimMsgFields.CHAT_ID, "")
        val fut = pendingMembers.remove(requestId) ?: return
        val error = json.optString(TimMsgFields.ERROR).ifEmpty { null }
        val ok = json.optBoolean(TimMsgFields.OK, false)
        val members = parseMembers(json.optJSONArray(TimMsgFields.MEMBERS))
        if (ok && chatId.isNotEmpty() && members.isNotEmpty()) {
            state.groups = state.groups.map { g ->
                if (g.chatId == chatId) g.copy(members = members) else g
            }
        }
        fut.complete(MembersResult(ok = ok, members = members, error = error))
    }

    private fun parseMembers(arr: JSONArray?): List<MemberInfo> {
        if (arr == null) return emptyList()
        val out = ArrayList<MemberInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val userId = m.optString(TimMsgFields.USER_ID, "")
            if (userId.isEmpty()) continue
            out.add(
                MemberInfo(
                    userId = userId,
                    display = m.optString(TimMsgFields.DISPLAY, userId),
                ),
            )
        }
        return out
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
