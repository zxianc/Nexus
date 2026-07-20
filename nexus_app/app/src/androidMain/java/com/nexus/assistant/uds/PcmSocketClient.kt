package com.nexus.assistant.uds

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.nexus.assistant.protocol.ApcmHeader
import com.nexus.assistant.protocol.FrameReader
import com.nexus.assistant.protocol.PcmProtocol
import com.nexus.assistant.protocol.ProtocolException
import java.io.InputStream

/**
 * Android LocalSocket client for framed pcm.sock.
 * Lives under androidMain — compile with AGP when Android SDK is available.
 */
class PcmSocketClient(
    private val path: String = "/data/vendor/ai_hook/pcm.sock",
) {
    private var socket: LocalSocket? = null
    val reader = FrameReader()

    fun connect() {
        val s = LocalSocket()
        s.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
        socket = s
    }

    fun readApcmHeader(timeoutMs: Long): ApcmHeader {
        val input: InputStream = socket?.inputStream ?: throw ProtocolException("not connected")
        val hdr = ByteArray(16)
        var off = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (off < 16) {
            if (System.currentTimeMillis() > deadline) {
                throw ProtocolException("APCM timeout")
            }
            val n = input.read(hdr, off, 16 - off)
            if (n < 0) {
                throw ProtocolException("EOF before APCM")
            }
            off += n
        }
        return PcmProtocol.parseApcmHeader(hdr)
    }

    fun sendMute(on: Boolean) {
        val payload = byteArrayOf(if (on) 1 else 0)
        writeFrame(PcmProtocol.TYPE_CTRL_MUTE, payload)
    }

    fun sendSession(start: Boolean) {
        writeFrame(PcmProtocol.TYPE_CTRL_SESSION, byteArrayOf(if (start) 1 else 0))
    }

    fun sendFlushUl() {
        writeFrame(PcmProtocol.TYPE_CTRL_FLUSH_UL, ByteArray(0))
    }

    fun sendPcmUl(pcm: ByteArray) {
        writeFrame(PcmProtocol.TYPE_PCM_UL, pcm)
    }

    private fun writeFrame(type: Int, payload: ByteArray) {
        val out = socket?.outputStream ?: throw ProtocolException("not connected")
        out.write(PcmProtocol.encodeFrame(type, payload))
        out.flush()
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }
}
