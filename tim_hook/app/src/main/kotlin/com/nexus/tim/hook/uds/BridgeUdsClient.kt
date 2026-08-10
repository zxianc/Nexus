package com.nexus.tim.hook.uds

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.nexus.tim.hook.MainHook
import com.nexus.tim.hook.send.SendDispatcher
import com.nexus.tim.hook.state.LoginProbe
import com.nexus.tim.hook.version.SupportedTim
import com.nexus.tim.protocol.TimFrame
import com.nexus.tim.protocol.TimFrameTypes
import com.nexus.tim.protocol.TimIpc
import com.nexus.tim.protocol.TimMsgFields
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

class BridgeUdsClient(
    private val appContextProvider: () -> Context?,
    private val loginProbe: LoginProbe,
    private val hostClassLoader: ClassLoader,
) : Runnable {
    private val liveSocket = AtomicReference<Socket?>(null)

    override fun run() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                sessionLoop()
            } catch (e: Throwable) {
                Log.w(MainHook.TAG, "IPC session error: ${e.message}")
            } finally {
                liveSocket.set(null)
            }
            try {
                Thread.sleep(RECONNECT_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun sessionLoop() {
        Socket().use { sock ->
            sock.connect(
                InetSocketAddress(TimIpc.TCP_HOST, TimIpc.TCP_PORT),
                CONNECT_TIMEOUT_MS,
            )
            sock.tcpNoDelay = true
            liveSocket.set(sock)
            Log.i(MainHook.TAG, "connected to bridge tcp://${TimIpc.TCP_HOST}:${TimIpc.TCP_PORT}")
            writeHello(sock)

            val helloRefresh = Thread({
                while (!Thread.currentThread().isInterrupted && liveSocket.get() === sock) {
                    try {
                        Thread.sleep(HELLO_REFRESH_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                    try {
                        writeHello(sock)
                    } catch (_: Throwable) {
                        break
                    }
                }
            }, "nexus-tim-hello-refresh").apply {
                isDaemon = true
                start()
            }

            try {
                var pending = ByteArray(0)
                val tmp = ByteArray(8192)
                val input = sock.getInputStream()
                while (!Thread.currentThread().isInterrupted) {
                    val n = input.read(tmp)
                    if (n < 0) break
                    pending += tmp.copyOf(n)
                    val (frames, rest) = TimFrame.decodeAll(pending)
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

    private fun handleFrame(sock: Socket, type: Int, payload: ByteArray) {
        when (type) {
            TimFrameTypes.PING -> write(sock, TimFrameTypes.PONG, ByteArray(0))
            TimFrameTypes.SEND_TEXT -> {
                val req = JSONObject(payload.toString(Charsets.UTF_8))
                val result = SendDispatcher(hostClassLoader, appContextProvider()).sendText(req)
                write(sock, TimFrameTypes.SEND_RESULT, result.toString().toByteArray())
            }
            else -> Unit
        }
    }

    private fun writeHello(sock: Socket) {
        val appContext = appContextProvider()
        val snap = loginProbe.probe(appContext)
        val version = resolveVersionName(appContext)
        val body = JSONObject()
            .put(TimMsgFields.TIM_VERSION, version)
            .put(TimMsgFields.LOGGED_IN, snap.loggedIn)
            .put(TimMsgFields.USER_ID, snap.userId)
            .put(TimMsgFields.NICK, snap.nick)
        write(sock, TimFrameTypes.HELLO, body.toString().toByteArray())
        Log.i(
            MainHook.TAG,
            "HELLO version=$version loggedIn=${snap.loggedIn} uin=${snap.userId}",
        )
    }

    private fun resolveVersionName(appContext: Context?): String {
        return try {
            val pm = appContext?.packageManager ?: return SupportedTim.VERSION_NAME
            @Suppress("DEPRECATION")
            pm.getPackageInfo(SupportedTim.PACKAGE, 0).versionName ?: SupportedTim.VERSION_NAME
        } catch (_: PackageManager.NameNotFoundException) {
            SupportedTim.VERSION_NAME
        }
    }

    private fun write(sock: Socket, type: Int, payload: ByteArray) {
        val bytes = TimFrame.encode(type, payload)
        synchronized(sock) {
            sock.getOutputStream().write(bytes)
            sock.getOutputStream().flush()
        }
    }

    companion object {
        private const val RECONNECT_MS = 2_000L
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val HELLO_REFRESH_MS = 8_000L
    }
}
