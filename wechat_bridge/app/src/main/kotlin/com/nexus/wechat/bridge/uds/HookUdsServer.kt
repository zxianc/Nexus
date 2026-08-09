package com.nexus.wechat.bridge.uds

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import com.nexus.wechat.protocol.WechatFrame
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Abstract namespace UDS server. Socket name is `nexus_wechat`
 * (equivalent to `@nexus_wechat` in abstract namespace).
 */
class HookUdsServer(
    private val session: HookSession,
) {
    private val running = AtomicBoolean(false)
    private var server: LocalServerSocket? = null
    private var acceptThread: Thread? = null
    private var clientThread: Thread? = null
    @Volatile private var client: LocalSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ss = LocalServerSocket(SOCKET_NAME)
        server = ss
        acceptThread = Thread({
            while (running.get()) {
                try {
                    val sock = ss.accept()
                    replaceClient(sock)
                } catch (e: IOException) {
                    if (running.get()) Log.w(TAG, "accept failed", e)
                }
            }
        }, "wechat-uds-accept").also { it.isDaemon = true; it.start() }
        Log.i(TAG, "UDS listening abstract:$SOCKET_NAME")
    }

    fun stop() {
        running.set(false)
        try {
            client?.close()
        } catch (_: Exception) {
        }
        client = null
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        acceptThread?.interrupt()
        clientThread?.interrupt()
        session.onDisconnected()
        session.writer = null
    }

    private fun replaceClient(sock: LocalSocket) {
        try {
            client?.close()
        } catch (_: Exception) {
        }
        client = sock
        session.writer = { bytes ->
            synchronized(sock) {
                sock.outputStream.write(bytes)
                sock.outputStream.flush()
            }
        }
        session.onConnected()
        clientThread?.interrupt()
        clientThread = Thread({
            val buf = ByteArray(0)
            var pending = buf
            val tmp = ByteArray(8192)
            try {
                val input = sock.inputStream
                while (running.get() && !Thread.currentThread().isInterrupted) {
                    val n = input.read(tmp)
                    if (n < 0) break
                    pending = pending + tmp.copyOf(n)
                    val (frames, rest) = WechatFrame.decodeAll(pending)
                    pending = rest
                    for (f in frames) {
                        session.onFrame(f.type, f.payload)
                    }
                }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "client read ended", e)
            } finally {
                if (client === sock) {
                    session.writer = null
                    session.onDisconnected()
                    client = null
                }
            }
        }, "wechat-uds-client").also { it.isDaemon = true; it.start() }
    }

    companion object {
        const val SOCKET_NAME = "nexus_wechat"
        private const val TAG = "WeChatBridgeUds"
    }
}
