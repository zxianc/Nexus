package com.nexus.wechat.protocol

/**
 * WeChat 8.0.76 `kl5.s5.rj` compressType:
 * - 0 = compressed (gallery default)
 * - 1 = original / without compress (gallery "原图")
 */
object ImageSendOptions {
    fun parseOriginal(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return true
        return when (raw.trim().lowercase()) {
            "0", "false", "no", "off", "compress" -> false
            else -> true
        }
    }

    fun compressType(original: Boolean): Int = if (original) 1 else 0
}
