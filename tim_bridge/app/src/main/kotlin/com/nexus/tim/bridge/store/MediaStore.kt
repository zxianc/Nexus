package com.nexus.tim.bridge.store

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MediaStore(
    rootDir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    class TooLarge(message: String) : Exception(message)

    private data class Entry(
        val file: File,
        val kind: String,
        val name: String,
    )

    private val outDir = File(rootDir, "out").also { it.mkdirs() }
    private val entries = ConcurrentHashMap<String, Entry>()

    fun saveOutgoing(bytes: ByteArray, name: String): File {
        if (bytes.size.toLong() > maxBytes) {
            throw TooLarge("file_too_large")
        }
        val safe = sanitizeName(name)
        val file = File(outDir, "${UUID.randomUUID()}_$safe")
        file.writeBytes(bytes)
        return file
    }

    fun registerOutgoing(file: File, kind: String, name: String): String {
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        entries[id] = Entry(file, kind, sanitizeName(name))
        return id
    }

    fun open(mediaId: String): File? = entries[mediaId]?.file?.takeIf { it.isFile }

    fun kindOf(mediaId: String): String? = entries[mediaId]?.kind

    fun nameOf(mediaId: String): String? = entries[mediaId]?.name

    fun urlOf(mediaId: String): String = "/v1/media/$mediaId"

    private fun sanitizeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
        return cleaned.ifEmpty { "bin" }.take(80)
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 25L * 1024L * 1024L
    }
}
