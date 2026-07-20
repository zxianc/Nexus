package com.nexus.assistant.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtocolException(msg: String) : Exception(msg)

data class ApcmHeader(val rate: Int, val channels: Int, val bits: Int, val kind: Int)

sealed class PcmFrame {
    data class PcmDl(val pcm: ByteArray) : PcmFrame()
    data class PcmUl(val pcm: ByteArray) : PcmFrame()
    data class CtrlMute(val on: Boolean) : PcmFrame()
    data object CtrlFlushUl : PcmFrame()
    data class CtrlSession(val start: Boolean) : PcmFrame()
    data class Unknown(val type: Int, val payload: ByteArray) : PcmFrame()
}

object PcmProtocol {
    const val APCM_MAGIC = 0x4D435041
    const val TYPE_PCM_DL = 0x01
    const val TYPE_PCM_UL = 0x02
    const val TYPE_CTRL_MUTE = 0x10
    const val TYPE_CTRL_FLUSH_UL = 0x11
    const val TYPE_CTRL_SESSION = 0x12
    const val MAX_PAYLOAD = 64 * 1024

    fun parseApcmHeader(buf: ByteArray): ApcmHeader {
        require(buf.size >= 16)
        val bb = ByteBuffer.wrap(buf, 0, 16).order(ByteOrder.LITTLE_ENDIAN)
        val magic = bb.int
        if (magic != APCM_MAGIC) {
            throw ProtocolException("bad APCM magic 0x${magic.toString(16)}")
        }
        val rate = bb.int
        val ch = bb.short.toInt() and 0xffff
        val bits = bb.short.toInt() and 0xffff
        val kind = bb.short.toInt() and 0xffff
        return ApcmHeader(rate, ch, bits, kind)
    }

    fun encodeFrame(type: Int, payload: ByteArray, flags: Int = 0): ByteArray {
        if (payload.size > MAX_PAYLOAD) {
            throw ProtocolException("payload too large ${payload.size}")
        }
        val out = ByteArray(4 + payload.size)
        out[0] = type.toByte()
        out[1] = flags.toByte()
        out[2] = (payload.size and 0xff).toByte()
        out[3] = ((payload.size shr 8) and 0xff).toByte()
        System.arraycopy(payload, 0, out, 4, payload.size)
        return out
    }
}

class FrameReader {
    private val buf = ArrayList<Byte>(4096)

    fun feed(data: ByteArray): List<PcmFrame> {
        for (b in data) buf.add(b)
        val out = ArrayList<PcmFrame>()
        while (true) {
            if (buf.size < 4) break
            val type = buf[0].toInt() and 0xff
            val len = (buf[2].toInt() and 0xff) or ((buf[3].toInt() and 0xff) shl 8)
            if (len > PcmProtocol.MAX_PAYLOAD) {
                throw ProtocolException("frame length $len")
            }
            if (buf.size < 4 + len) break
            val payload = ByteArray(len) { i -> buf[4 + i] }
            repeat(4 + len) { buf.removeAt(0) }
            out.add(decode(type, payload))
        }
        return out
    }

    private fun decode(type: Int, payload: ByteArray): PcmFrame = when (type) {
        PcmProtocol.TYPE_PCM_DL -> PcmFrame.PcmDl(payload)
        PcmProtocol.TYPE_PCM_UL -> PcmFrame.PcmUl(payload)
        PcmProtocol.TYPE_CTRL_MUTE ->
            PcmFrame.CtrlMute(payload.isNotEmpty() && payload[0] != 0.toByte())
        PcmProtocol.TYPE_CTRL_FLUSH_UL -> PcmFrame.CtrlFlushUl
        PcmProtocol.TYPE_CTRL_SESSION ->
            PcmFrame.CtrlSession(payload.isNotEmpty() && payload[0] != 0.toByte())
        else -> PcmFrame.Unknown(type, payload)
    }
}
