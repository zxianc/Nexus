package com.nexus.wechat.bridge.uds

import com.nexus.wechat.bridge.state.BridgeState
import com.nexus.wechat.bridge.state.ChatInfo
import com.nexus.wechat.bridge.state.ContactInfo
import com.nexus.wechat.bridge.state.MeInfo
import com.nexus.wechat.bridge.state.MemberInfo
import com.nexus.wechat.bridge.push.OutboundHub
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
    private val outbound: OutboundHub? = null,
) {
    @Volatile
    var writer: ((ByteArray) -> Unit)? = null

    /** Fired on UI/service thread callers after hook/login status changes. */
    @Volatile
    var onStatusChanged: (() -> Unit)? = null

    private val pending = ConcurrentHashMap<String, CompletableFuture<SendResult>>()
    private var wasConnected = false

    fun onConnected() {
        state.hookConnected = true
        wasConnected = true
        notifyStatusChanged()
    }

    fun onDisconnected() {
        val hadHook = wasConnected || state.hookConnected
        state.hookConnected = false
        state.loggedIn = false
        state.me = MeInfo()
        state.chats = emptyList()
        state.contacts = emptyList()
        state.groups = emptyList()
        pending.forEach { (_, fut) ->
            fut.complete(SendResult(ok = false, error = "hook_disconnected"))
        }
        pending.clear()
        if (hadHook) {
            wasConnected = false
            outbound?.alert("hook_disconnected", "WeChat hook UDS disconnected")
        }
        notifyStatusChanged()
    }

    fun onFrame(type: Int, payload: ByteArray) {
        val text = payload.toString(Charsets.UTF_8)
        when (type) {
            WechatFrameTypes.HELLO -> handleHello(text)
            WechatFrameTypes.SEND_RESULT -> handleSendResult(text)
            WechatFrameTypes.MSG_IN -> handleMsgIn(text)
            WechatFrameTypes.MEDIA_READY -> handleMediaReady(text)
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
        val body = JSONObject()
            .put(WechatMsgFields.CHAT_ID, chatId)
            .put(WechatMsgFields.TEXT, text)
            .put(WechatMsgFields.ATS, JSONArray(ats))
        return requestSend(WechatFrameTypes.SEND_TEXT, body, timeoutMs)
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
        val type = if (kind == "image") WechatFrameTypes.SEND_IMAGE else WechatFrameTypes.SEND_FILE
        val body = JSONObject()
            .put(WechatMsgFields.CHAT_ID, chatId)
            .put(WechatMsgFields.PATH, path)
            .put(WechatMsgFields.NAME, name)
            .put(WechatMsgFields.KIND, kind)
            .put(WechatMsgFields.MEDIA_ID, mediaId)
            .put(WechatMsgFields.DATA_B64, dataB64)
            .put(WechatMsgFields.ORIGINAL, original)
        return requestSend(type, body, timeoutMs)
    }

    private fun requestSend(type: Int, body: JSONObject, timeoutMs: Long): SendResult {
        if (!state.hookConnected || writer == null) {
            return SendResult(ok = false, error = "hook_unavailable")
        }
        if (state.wechatVersion != null && state.wechatVersion != state.supportedVersion) {
            return SendResult(ok = false, error = "version_mismatch")
        }
        val requestId = UUID.randomUUID().toString()
        val fut = CompletableFuture<SendResult>()
        pending[requestId] = fut
        body.put(WechatMsgFields.REQUEST_ID, requestId)
        writeType(type, body.toString().toByteArray(Charsets.UTF_8))
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
        state.me = MeInfo(
            userId = json.optString(WechatMsgFields.USER_ID, ""),
            nick = json.optString(WechatMsgFields.NICK, ""),
        )
        state.chats = parseChats(json.optJSONArray(WechatMsgFields.CHATS))
        state.contacts = parseContacts(json.optJSONArray(WechatMsgFields.CONTACTS))
        state.groups = parseGroups(json.optJSONArray(WechatMsgFields.GROUPS))
        val ver = state.wechatVersion
        if (ver != null && ver != state.supportedVersion) {
            outbound?.alert(
                "version_mismatch",
                "WeChat version $ver != supported ${state.supportedVersion}",
                JSONObject()
                    .put("wechat_version", ver)
                    .put("supported", state.supportedVersion),
            )
        }
        notifyStatusChanged()
    }

    private fun notifyStatusChanged() {
        try {
            onStatusChanged?.invoke()
        } catch (_: Exception) {
        }
    }

    private fun parseContacts(arr: JSONArray?): List<ContactInfo> {
        if (arr == null) return emptyList()
        val out = ArrayList<ContactInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val userId = c.optString(WechatMsgFields.USER_ID, "")
            if (userId.isEmpty()) continue
            out.add(
                ContactInfo(
                    userId = userId,
                    display = c.optString(WechatMsgFields.DISPLAY, userId),
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
            val chatId = c.optString(WechatMsgFields.CHAT_ID, "")
            if (chatId.isEmpty()) continue
            val membersArr = c.optJSONArray(WechatMsgFields.MEMBERS)
            val members = ArrayList<MemberInfo>()
            if (membersArr != null) {
                for (j in 0 until membersArr.length()) {
                    val m = membersArr.optJSONObject(j) ?: continue
                    val uid = m.optString(WechatMsgFields.USER_ID, "")
                    if (uid.isEmpty()) continue
                    members.add(
                        MemberInfo(
                            userId = uid,
                            display = m.optString(WechatMsgFields.DISPLAY, uid),
                        ),
                    )
                }
            }
            out.add(
                ChatInfo(
                    chatId = chatId,
                    title = c.optString(WechatMsgFields.TITLE, chatId),
                    isGroup = true,
                    members = members,
                ),
            )
        }
        return out
    }

    private fun parseChats(arr: JSONArray?): List<ChatInfo> {
        if (arr == null) return emptyList()
        val out = ArrayList<ChatInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val chatId = c.optString(WechatMsgFields.CHAT_ID, "")
            if (chatId.isEmpty()) continue
            val membersArr = c.optJSONArray(WechatMsgFields.MEMBERS)
            val members = ArrayList<MemberInfo>()
            if (membersArr != null) {
                for (j in 0 until membersArr.length()) {
                    val m = membersArr.optJSONObject(j) ?: continue
                    val uid = m.optString(WechatMsgFields.USER_ID, "")
                    if (uid.isEmpty()) continue
                    members.add(
                        MemberInfo(
                            userId = uid,
                            display = m.optString(WechatMsgFields.DISPLAY, uid),
                        ),
                    )
                }
            }
            out.add(
                ChatInfo(
                    chatId = chatId,
                    title = c.optString(WechatMsgFields.TITLE, chatId),
                    isGroup = c.optBoolean(WechatMsgFields.IS_GROUP, chatId.endsWith("@chatroom")),
                    members = members,
                ),
            )
        }
        return out
    }

    private fun handleSendResult(text: String) {
        val json = JSONObject(text)
        val requestId = json.optString(WechatMsgFields.REQUEST_ID, "")
        val fut = pending.remove(requestId) ?: return
        val msgId = json.optString(WechatMsgFields.MSG_ID).ifEmpty { null }
        val error = json.optString(WechatMsgFields.ERROR).ifEmpty { null }
        val ok = json.optBoolean(WechatMsgFields.OK, false)
        fut.complete(
            SendResult(
                ok = ok,
                msgId = msgId,
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

    var onMediaReady: ((mediaId: String, path: String, kind: String, name: String) -> Unit)? = null

    private fun handleMediaReady(text: String) {
        val json = JSONObject(text)
        val mediaId = json.optString(WechatMsgFields.MEDIA_ID, "")
        val path = json.optString(WechatMsgFields.PATH, "")
        val kind = json.optString(WechatMsgFields.KIND, json.optString(WechatMsgFields.MEDIA_KIND, "file"))
        val name = json.optString(WechatMsgFields.NAME, json.optString(WechatMsgFields.MEDIA_NAME, "bin"))
        if (mediaId.isEmpty() || path.isEmpty()) return
        outbound?.publishEvent(
            "media_ready",
            JSONObject()
                .put(WechatMsgFields.MEDIA_ID, mediaId)
                .put(WechatMsgFields.PATH, path)
                .put(WechatMsgFields.KIND, kind)
                .put(WechatMsgFields.NAME, name),
        )
        onMediaReady?.invoke(mediaId, path, kind, name)
    }

    private fun writeType(type: Int, payload: ByteArray) {
        val w = writer ?: return
        w(WechatFrame.encode(type, payload))
    }
}
