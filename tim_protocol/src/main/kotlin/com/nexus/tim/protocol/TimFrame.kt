package com.nexus.tim.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object TimFrameTypes {
    const val HELLO = 1
    const val SEND_TEXT = 2
    const val SEND_IMAGE = 3
    const val SEND_FILE = 4
    const val SEND_RESULT = 5
    const val MSG_IN = 6
    const val MEDIA_READY = 7
    const val PING = 8
    const val PONG = 9
    const val LIST_MEMBERS = 10
    const val LIST_MEMBERS_RESULT = 11
}

data class DecodedFrame(
    val type: Int,
    val flags: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedFrame) return false
        return type == other.type && flags == other.flags && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + flags
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

object TimFrame {
    const val HEADER_SIZE = 6
    const val MAX_PAYLOAD = 16_777_216

    fun encode(type: Int, payload: ByteArray, flags: Int = 0): ByteArray {
        require(type in 0..255) { "type out of range: $type" }
        require(flags in 0..255) { "flags out of range: $flags" }
        require(payload.size <= MAX_PAYLOAD) { "payload too large: ${payload.size}" }
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = type.toByte()
        out[1] = flags.toByte()
        ByteBuffer.wrap(out, 2, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size)
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, out, HEADER_SIZE, payload.size)
        }
        return out
    }

    fun decodeAll(buffer: ByteArray): Pair<List<DecodedFrame>, ByteArray> {
        val frames = ArrayList<DecodedFrame>()
        var offset = 0
        while (offset + HEADER_SIZE <= buffer.size) {
            val type = buffer[offset].toInt() and 0xff
            val flags = buffer[offset + 1].toInt() and 0xff
            val len = ByteBuffer.wrap(buffer, offset + 2, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
            if (len < 0 || len > MAX_PAYLOAD) {
                throw IllegalArgumentException("invalid payload length: $len")
            }
            if (offset + HEADER_SIZE + len > buffer.size) {
                break
            }
            val payload = buffer.copyOfRange(offset + HEADER_SIZE, offset + HEADER_SIZE + len)
            frames.add(DecodedFrame(type, flags, payload))
            offset += HEADER_SIZE + len
        }
        val rest = if (offset == 0) {
            buffer.copyOf()
        } else if (offset >= buffer.size) {
            ByteArray(0)
        } else {
            buffer.copyOfRange(offset, buffer.size)
        }
        return frames to rest
    }
}
