package com.nexus.tim.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSendOptionsTest {
    @Test
    fun defaultOriginal_usesCompressTypeZero() {
        assertTrue(ImageSendOptions.parseOriginal(null))
        assertTrue(ImageSendOptions.parseOriginal(""))
        assertEquals(0, ImageSendOptions.compressType(original = true))
    }

    @Test
    fun compressFlag_mapsToCompressTypeOne() {
        assertFalse(ImageSendOptions.parseOriginal("false"))
        assertFalse(ImageSendOptions.parseOriginal("0"))
        assertFalse(ImageSendOptions.parseOriginal("no"))
        assertEquals(1, ImageSendOptions.compressType(original = false))
    }

    @Test
    fun truthyOriginal() {
        assertTrue(ImageSendOptions.parseOriginal("true"))
        assertTrue(ImageSendOptions.parseOriginal("1"))
        assertTrue(ImageSendOptions.parseOriginal("yes"))
    }
}
