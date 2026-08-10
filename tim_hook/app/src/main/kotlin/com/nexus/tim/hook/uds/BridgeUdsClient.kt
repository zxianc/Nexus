package com.nexus.tim.hook.uds

import android.content.Context
import android.content.pm.PackageManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.nexus.tim.hook.MainHook
import com.nexus.tim.hook.state.LoginProbe
import com.nexus.tim.hook.version.SupportedTim
import com.nexus.tim.protocol.TimFrame
import com.nexus.tim.protocol.TimFrameTypes
import com.nexus.tim.protocol.TimMsgFields
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

class BridgeUdsClient(
    private val appContextProvider: () -> Context?,
    private val loginProbe: LoginProbe,
) : Runnable {
    private val liveSocket = AtomicReference<LocalSocket?>(null)

    override fun run() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                sessionLoop()
            } catch (e: Throwable) {
                Log.w(MainHook.TAG, "UDS session error: ${e.message}")
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
        LocalSocket().use { sock ->
            sock.connect(
                LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT),
            )
            liveSocket.set(sock)
            Log.i(MainHook.TAG, "connected to bridge UDS @$SOCKET_NAME")
            writeHello(sock)

            var pending = ByteArray(0)
            val tmp = ByteArray(8192)
            val input = sock.inputStream
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
        }
    }

    private fun handleFrame(sock: LocalSocket, type: Int, payload: ByteArray) {
        when (type) {
            TimFrameTypes.PING -> write(sock, TimFrameTypes.PONG, ByteArray(0))
            TimFrameTypes.SEND_TEXT -> {
                val req = JSONObject(payload.toString(Charsets.UTF_8))
                val requestId = req.optString(TimMsgFields.REQUEST_ID, "")
                val result = JSONObject()
                    .put(TimMsgFields.REQUEST_ID, requestId)
                    .put(TimMsgFields.OK, false)
                    .put(TimMsgFields.ERROR, "send_not_implemented")
                write(sock, TimFrameTypes.SEND_RESULT, result.toString().toByteArray())
            }
            else -> Unit
        }
    }

    private fun writeHello(sock: LocalSocket) {
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

    private fun write(sock: LocalSocket, type: Int, payload: ByteArray) {
        val bytes = TimFrame.encode(type, payload)
        synchronized(sock) {
            sock.outputStream.write(bytes)
            sock.outputStream.flush()
        }
    }

    companion object {
        const val SOCKET_NAME = "nexus_tim"
        private const val RECONNECT_MS = 2_000L
    }
}
