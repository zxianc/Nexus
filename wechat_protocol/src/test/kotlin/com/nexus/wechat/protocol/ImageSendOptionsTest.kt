package com.nexus.wechat.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSendOptionsTest {
    @Test
    fun default_isOriginal() {
        assertTrue(ImageSendOptions.parseOriginal(null))
        assertTrue(ImageSendOptions.parseOriginal(""))
        assertEquals(1, ImageSendOptions.compressType(original = true))
    }

    @Test
    fun explicit_false_usesCompress() {
        assertFalse(ImageSendOptions.parseOriginal("false"))
        assertFalse(ImageSendOptions.parseOriginal("0"))
        assertFalse(ImageSendOptions.parseOriginal("no"))
        assertEquals(0, ImageSendOptions.compressType(original = false))
    }

    @Test
    fun explicit_true() {
        assertTrue(ImageSendOptions.parseOriginal("true"))
        assertTrue(ImageSendOptions.parseOriginal("1"))
        assertTrue(ImageSendOptions.parseOriginal("yes"))
    }
}
