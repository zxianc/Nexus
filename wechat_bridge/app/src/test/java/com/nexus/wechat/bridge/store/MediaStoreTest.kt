package com.nexus.wechat.bridge.store

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MediaStoreTest {
    private fun tempDir(): File =
        Files.createTempDirectory("media-store-").toFile()

    @Test
    fun rejectOversize() {
        val dir = tempDir()
        try {
            val store = MediaStore(dir, maxBytes = 1024)
            assertThrows(MediaStore.TooLarge::class.java) {
                store.saveOutgoing(ByteArray(2048), "a.bin")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun saveOutgoing_and_open_roundtrip() {
        val dir = tempDir()
        try {
            val store = MediaStore(dir)
            val bytes = byteArrayOf(1, 2, 3, 9)
            val saved = store.saveOutgoing(bytes, "pic.png")
            assertTrue(saved.exists())
            assertTrue(saved.name.contains("pic.png"))
            val mediaId = store.registerOutgoing(saved, "image", "pic.png")
            val opened = store.open(mediaId)
            assertNotNull(opened)
            assertArrayEquals(bytes, opened!!.readBytes())
            assertEquals("image", store.kindOf(mediaId))
            assertEquals("pic.png", store.nameOf(mediaId))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun registerIncoming_path() {
        val dir = tempDir()
        try {
            val store = MediaStore(dir)
            val src = File(dir, "src.bin").apply { writeBytes(byteArrayOf(7, 8)) }
            val mediaId = store.registerIncoming(src.absolutePath, "file", "doc.bin")
            val opened = store.open(mediaId)!!
            assertArrayEquals(byteArrayOf(7, 8), opened.readBytes())
            assertEquals("file", store.kindOf(mediaId))
        } finally {
            dir.deleteRecursively()
        }
    }
}
