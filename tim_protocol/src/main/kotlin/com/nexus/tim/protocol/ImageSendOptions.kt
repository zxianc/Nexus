package com.nexus.tim.protocol

/**
 * TIM QQNT `IMsgUtilApi.createPicElement(String path, boolean original, int compressType)`.
 *
 * Empirically try original=true with compressType=0 first (TIM “原图” path).
 * This differs from WeChat 8.0.76 where original maps to compressType=1.
 */
object ImageSendOptions {
    fun parseOriginal(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return true
        return when (raw.trim().lowercase()) {
            "0", "false", "no", "off", "compress" -> false
            else -> true
        }
    }

    /** TIM: original → 0; compressed → 1. */
    fun compressType(original: Boolean): Int = if (original) 0 else 1
}
