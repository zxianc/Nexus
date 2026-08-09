package com.nexus.wechat.hook.uds

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.nexus.wechat.hook.MainHook
import com.nexus.wechat.hook.send.SendDispatcher
import com.nexus.wechat.hook.state.LoginProbe
import com.nexus.wechat.hook.version.SupportedWeChat
import com.nexus.wechat.protocol.WechatFrame
import com.nexus.wechat.protocol.WechatFrameTypes
import com.nexus.wechat.protocol.WechatMsgFields
import org.json.JSONArray
import org.json.JSONObject

class BridgeUdsClient(
    private val classLoader: ClassLoader,
    private val loginProbe: LoginProbe,
) : Runnable {
    private val sendDispatcher = SendDispatcher(classLoader)

    override fun run() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                sessionLoop()
            } catch (e: Throwable) {
                Log.w(MainHook.TAG, "UDS session error: ${e.message}")
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
            Log.i(MainHook.TAG, "connected to bridge UDS @$SOCKET_NAME")
            write(sock, WechatFrameTypes.HELLO, buildHello().toString().toByteArray())

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
        }
    }

    private fun handleFrame(sock: LocalSocket, type: Int, payload: ByteArray) {
        when (type) {
            WechatFrameTypes.PING -> write(sock, WechatFrameTypes.PONG, ByteArray(0))
            WechatFrameTypes.SEND_TEXT -> {
                val req = JSONObject(payload.toString(Charsets.UTF_8))
                val result = sendDispatcher.sendText(req)
                write(sock, WechatFrameTypes.SEND_RESULT, result.toString().toByteArray())
            }
            else -> Unit
        }
    }

    private fun buildHello(): JSONObject {
        val loggedIn = loginProbe.isLoggedIn()
        return JSONObject()
            .put(WechatMsgFields.WECHAT_VERSION, SupportedWeChat.VERSION_NAME)
            .put(WechatMsgFields.LOGGED_IN, loggedIn)
            .put(WechatMsgFields.USER_ID, loginProbe.userIdOrEmpty())
            .put(WechatMsgFields.NICK, loginProbe.nickOrEmpty())
            .put(WechatMsgFields.CHATS, JSONArray())
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
    }
}
