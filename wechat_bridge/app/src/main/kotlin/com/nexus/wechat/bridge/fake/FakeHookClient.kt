package com.nexus.wechat.bridge.fake

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.nexus.wechat.protocol.WechatFrame
import com.nexus.wechat.protocol.WechatFrameTypes
import com.nexus.wechat.protocol.WechatMsgFields
import com.nexus.wechat.bridge.uds.HookUdsServer
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-only in-process FakeHook that speaks the UDS protocol.
 * Enable from MainActivity (debug builds).
 */
class FakeHookClient(
    private val wechatVersion: String = "8.0.49",
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({
            while (running.get()) {
                try {
                    runSession()
                } catch (e: Exception) {
                    Log.w(TAG, "FakeHook session error", e)
                }
                if (running.get()) Thread.sleep(1000)
            }
        }, "fake-hook").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
    }

    private fun runSession() {
        LocalSocket().use { sock ->
            sock.connect(
                LocalSocketAddress(HookUdsServer.SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT),
            )
            Log.i(TAG, "FakeHook connected")
            val hello = JSONObject()
                .put(WechatMsgFields.WECHAT_VERSION, wechatVersion)
                .put(WechatMsgFields.LOGGED_IN, true)
                .put(WechatMsgFields.USER_ID, "wxid_fakebot")
                .put(WechatMsgFields.NICK, "fakebot")
                .put(
                    WechatMsgFields.CHATS,
                    JSONArray().put(
                        JSONObject()
                            .put("chat_id", "wxid_a")
                            .put("title", "Alice")
                            .put("is_group", false),
                    ),
                )
            write(sock, WechatFrameTypes.HELLO, hello.toString().toByteArray())

            var pending = ByteArray(0)
            val tmp = ByteArray(8192)
            val input = sock.inputStream
            while (running.get()) {
                val n = input.read(tmp)
                if (n < 0) break
                pending += tmp.copyOf(n)
                val (frames, rest) = WechatFrame.decodeAll(pending)
                pending = rest
                for (f in frames) {
                    when (f.type) {
                        WechatFrameTypes.SEND_TEXT -> handleSendText(sock, f.payload)
                        WechatFrameTypes.PING -> write(sock, WechatFrameTypes.PONG, ByteArray(0))
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun handleSendText(sock: LocalSocket, payload: ByteArray) {
        val req = JSONObject(payload.toString(Charsets.UTF_8))
        val requestId = req.getString(WechatMsgFields.REQUEST_ID)
        val chatId = req.getString(WechatMsgFields.CHAT_ID)
        val text = req.getString(WechatMsgFields.TEXT)
        val msgId = "fake-${UUID.randomUUID()}"
        val result = JSONObject()
            .put(WechatMsgFields.REQUEST_ID, requestId)
            .put(WechatMsgFields.OK, true)
            .put(WechatMsgFields.MSG_ID, msgId)
        write(sock, WechatFrameTypes.SEND_RESULT, result.toString().toByteArray())

        val inbound = JSONObject()
            .put(WechatMsgFields.MSG_ID, "echo-$msgId")
            .put(WechatMsgFields.CHAT_ID, chatId)
            .put(WechatMsgFields.FROM_ID, "wxid_fakebot")
            .put(WechatMsgFields.IS_GROUP, false)
            .put(WechatMsgFields.TEXT, "echo:$text")
            .put(WechatMsgFields.ATS, JSONArray())
            .put(WechatMsgFields.TS, System.currentTimeMillis() / 1000)
        write(sock, WechatFrameTypes.MSG_IN, inbound.toString().toByteArray())
    }

    private fun write(sock: LocalSocket, type: Int, payload: ByteArray) {
        val frame = WechatFrame.encode(type, payload)
        synchronized(sock) {
            sock.outputStream.write(frame)
            sock.outputStream.flush()
        }
    }

    companion object {
        private const val TAG = "FakeHook"
    }
}
