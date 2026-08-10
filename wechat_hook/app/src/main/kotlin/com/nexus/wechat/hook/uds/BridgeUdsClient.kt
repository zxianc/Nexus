package com.nexus.wechat.hook.uds

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.nexus.wechat.hook.MainHook
import com.nexus.wechat.hook.runtime.WeChatRuntime
import com.nexus.wechat.hook.send.SendDispatcher
import com.nexus.wechat.hook.state.ContactDirectory
import com.nexus.wechat.hook.state.LoginProbe
import com.nexus.wechat.hook.version.SupportedWeChat
import com.nexus.wechat.protocol.WechatFrame
import com.nexus.wechat.protocol.WechatFrameTypes
import com.nexus.wechat.protocol.WechatMsgFields
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

class BridgeUdsClient(
    private val fallbackLoader: ClassLoader,
    private val loginProbe: LoginProbe,
    private val contacts: ContactDirectory,
) : Runnable {
    private val liveSocket = AtomicReference<LocalSocket?>(null)
    @Volatile private var lastHelloSig: String = ""

    fun emitFrame(type: Int, payload: ByteArray) {
        val sock = liveSocket.get() ?: return
        try {
            write(sock, type, payload)
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "emitFrame failed: ${t.message}")
        }
    }

    override fun run() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                sessionLoop()
            } catch (e: Throwable) {
                Log.w(MainHook.TAG, "UDS session error: ${e.message}")
            } finally {
                liveSocket.set(null)
                lastHelloSig = ""
            }
            try {
                Thread.sleep(RECONNECT_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun sessionLoop() {
        LocalSocket().use { sock ->
            sock.connect(
                LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT),
            )
            liveSocket.set(sock)
            Log.i(MainHook.TAG, "connected to bridge UDS @$SOCKET_NAME")
            maybeSendHello(sock, force = true)

            val helloRefresh = Thread({
                while (!Thread.currentThread().isInterrupted && liveSocket.get() === sock) {
                    try {
                        Thread.sleep(HELLO_REFRESH_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                    try {
                        maybeSendHello(sock, force = false)
                    } catch (_: Throwable) {
                        break
                    }
                }
            }, "nexus-wechat-hello-refresh").apply {
                isDaemon = true
                start()
            }

            try {
                var pending = ByteArray(0)
                val tmp = ByteArray(8192)
                val input = sock.inputStream
                while (!Thread.currentThread().isInterrupted) {
                    val n = input.read(tmp)
                    if (n < 0) break
                    pending += tmp.copyOf(n)
                    val (frames, rest) = WechatFrame.decodeAll(pending)
                    pending = rest
                    for (f in frames) {
                        handleFrame(sock, f.type, f.payload)
                    }
                }
            } finally {
                helloRefresh.interrupt()
            }
        }
    }

    private fun handleFrame(sock: LocalSocket, type: Int, payload: ByteArray) {
        when (type) {
            WechatFrameTypes.PING -> write(sock, WechatFrameTypes.PONG, ByteArray(0))
            WechatFrameTypes.SEND_TEXT -> {
                val req = JSONObject(payload.toString(Charsets.UTF_8))
                val cl = WeChatRuntime.classLoader(fallbackLoader)
                WeChatRuntime.ensureKernelReady(fallbackLoader)
                val result = SendDispatcher(cl, contacts).sendText(req)
                write(sock, WechatFrameTypes.SEND_RESULT, result.toString().toByteArray())
            }
            WechatFrameTypes.SEND_IMAGE,
            WechatFrameTypes.SEND_FILE,
            -> {
                val req = JSONObject(payload.toString(Charsets.UTF_8))
                if (!req.has(WechatMsgFields.KIND)) {
                    req.put(
                        WechatMsgFields.KIND,
                        if (type == WechatFrameTypes.SEND_IMAGE) "image" else "file",
                    )
                }
                val cl = WeChatRuntime.classLoader(fallbackLoader)
                WeChatRuntime.ensureKernelReady(fallbackLoader)
                val result = SendDispatcher(cl, contacts).sendMedia(req)
                write(sock, WechatFrameTypes.SEND_RESULT, result.toString().toByteArray())
            }
            else -> Unit
        }
    }

    private fun maybeSendHello(sock: LocalSocket, force: Boolean) {
        loginProbe.refreshIdentity()
        val hello = buildHello()
        val sig = "${hello.optBoolean(WechatMsgFields.LOGGED_IN)}|" +
            "${hello.optString(WechatMsgFields.USER_ID)}|" +
            "${hello.optJSONArray(WechatMsgFields.CHATS)?.length() ?: 0}|" +
            "${hello.optJSONArray(WechatMsgFields.CONTACTS)?.length() ?: 0}|" +
            "${hello.optJSONArray(WechatMsgFields.GROUPS)?.length() ?: 0}"
        if (!force && sig == lastHelloSig) return
        lastHelloSig = sig
        write(sock, WechatFrameTypes.HELLO, hello.toString().toByteArray())
        Log.i(
            MainHook.TAG,
            "HELLO logged_in=${hello.optBoolean(WechatMsgFields.LOGGED_IN)} " +
                "chats=${hello.optJSONArray(WechatMsgFields.CHATS)?.length() ?: 0} " +
                "contacts=${hello.optJSONArray(WechatMsgFields.CONTACTS)?.length() ?: 0} " +
                "groups=${hello.optJSONArray(WechatMsgFields.GROUPS)?.length() ?: 0}",
        )
    }

    private fun buildHello(): JSONObject {
        loginProbe.refreshIdentity()
        val selfId = loginProbe.userIdOrEmpty()
        val chats = contacts.listChats()
        val contactList = contacts.listContacts(selfId = selfId)
        val groups = contacts.listGroups()
        return JSONObject()
            .put(WechatMsgFields.WECHAT_VERSION, SupportedWeChat.VERSION_NAME)
            .put(WechatMsgFields.LOGGED_IN, loginProbe.isLoggedIn())
            .put(WechatMsgFields.USER_ID, selfId)
            .put(WechatMsgFields.NICK, loginProbe.nickOrEmpty())
            .put(WechatMsgFields.CHATS, chats)
            .put(WechatMsgFields.CONTACTS, contactList)
            .put(WechatMsgFields.GROUPS, groups)
    }

    private fun write(sock: LocalSocket, type: Int, payload: ByteArray) {
        val frame = WechatFrame.encode(type, payload)
        synchronized(sock) {
            sock.outputStream.write(frame)
            sock.outputStream.flush()
        }
    }

    companion object {
        const val SOCKET_NAME = "nexus_wechat"
        private const val RECONNECT_MS = 2000L
        private const val HELLO_REFRESH_MS = 3000L
    }
}
