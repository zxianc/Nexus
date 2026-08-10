package com.nexus.tim.bridge.uds

import android.util.Log
import com.nexus.tim.protocol.TimFrame
import com.nexus.tim.protocol.TimIpc
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** Loopback TCP server for TIM hook (see [TimIpc.TCP_PORT]). */
class HookUdsServer(
    private val session: HookSession,
) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var clientThread: Thread? = null
    @Volatile private var client: Socket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ss = ServerSocket(TimIpc.TCP_PORT, 1, InetAddress.getByName(TimIpc.TCP_HOST))
        server = ss
        acceptThread = Thread({
            while (running.get()) {
                try {
                    replaceClient(ss.accept())
                } catch (e: IOException) {
                    if (running.get()) Log.w(TAG, "accept failed", e)
                }
            }
        }, "tim-ipc-accept").also { it.isDaemon = true; it.start() }
        Log.i(TAG, "IPC listening tcp://${TimIpc.TCP_HOST}:${TimIpc.TCP_PORT}")
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

    private fun replaceClient(sock: Socket) {
        try {
            client?.close()
        } catch (_: Exception) {
        }
        client = sock
        session.writer = { bytes ->
            synchronized(sock) {
                sock.getOutputStream().write(bytes)
                sock.getOutputStream().flush()
            }
        }
        session.onConnected()
        clientThread?.interrupt()
        clientThread = Thread({
            var pending = ByteArray(0)
            val tmp = ByteArray(8192)
            try {
                val input = sock.getInputStream()
                while (running.get() && !Thread.currentThread().isInterrupted) {
                    val n = input.read(tmp)
                    if (n < 0) break
                    pending = pending + tmp.copyOf(n)
                    val (frames, rest) = TimFrame.decodeAll(pending)
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
        }, "tim-ipc-client").also { it.isDaemon = true; it.start() }
    }

    companion object {
        private const val TAG = "TimBridgeIpc"
    }
}
