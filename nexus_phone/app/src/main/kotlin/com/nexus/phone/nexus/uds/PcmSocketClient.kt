package com.nexus.phone.nexus.uds

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.nexus.phone.nexus.protocol.ApcmHeader
import com.nexus.phone.nexus.protocol.FrameReader
import com.nexus.phone.nexus.protocol.PcmFrame
import com.nexus.phone.nexus.protocol.PcmProtocol
import com.nexus.phone.nexus.protocol.ProtocolException
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

/**
 * Framed UDS client. Prefers abstract `@nexus_pcm` (avoids vendor sock_file SELinux);
 * falls back to filesystem path for older HAL builds.
 */
class PcmSocketClient(
    private val filesystemPath: String = "/data/vendor/ai_hook/pcm.sock",
    private val abstractName: String = "nexus_pcm",
) {
    private var socket: LocalSocket? = null
    val reader = FrameReader()

    /** How the last connect succeeded (for smoke UI / logs). */
    var connectedVia: String = ""
        private set

    fun connect() {
        val abstractErr: Exception?
        try {
            val s = LocalSocket()
            s.connect(LocalSocketAddress(abstractName, LocalSocketAddress.Namespace.ABSTRACT))
            socket = s
            connectedVia = "abstract:@$abstractName"
            Log.i(TAG, "connected via $connectedVia")
            return
        } catch (e: Exception) {
            abstractErr = e
            Log.w(TAG, "abstract connect failed, try filesystem", e)
        }
        try {
            val s = LocalSocket()
            s.connect(
                LocalSocketAddress(filesystemPath, LocalSocketAddress.Namespace.FILESYSTEM),
            )
            socket = s
            connectedVia = "filesystem:$filesystemPath"
            Log.i(TAG, "connected via $connectedVia")
        } catch (e: Exception) {
            throw ProtocolException(
                "UDS connect failed (abstract: ${abstractErr?.message}; fs: ${e.message})",
            )
        }
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

    fun setReadTimeoutMs(timeoutMs: Int) {
        socket?.soTimeout = timeoutMs
    }

    /**
     * Read one socket chunk and decode frames. Empty list on read timeout (keeps session alive).
     */
    fun pollFrames(): List<PcmFrame> {
        val input: InputStream = socket?.inputStream ?: throw ProtocolException("not connected")
        val chunk = ByteArray(8192)
        return try {
            val n = input.read(chunk)
            if (n < 0) {
                throw ProtocolException("EOF while reading frames")
            }
            if (n == 0) {
                emptyList()
            } else {
                reader.feed(chunk.copyOf(n))
            }
        } catch (_: SocketTimeoutException) {
            emptyList()
        } catch (e: IOException) {
            // LocalSocket may surface EAGAIN as "Try again" when peer is busy.
            if (e.message?.contains("Try again", ignoreCase = true) == true) {
                emptyList()
            } else {
                throw e
            }
        }
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

    companion object {
        private const val TAG = "PcmSocketClient"
    }
}
