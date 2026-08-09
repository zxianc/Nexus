package com.nexus.wechat.bridge.store

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
    private val inDir = File(rootDir, "in").also { it.mkdirs() }
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

    fun registerIncoming(
        path: String,
        kind: String,
        name: String,
        preferredId: String? = null,
    ): String {
        val src = File(path)
        val safe = sanitizeName(name)
        val id = preferredId?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().replace("-", "").take(16)
        // If already registered under this id, reuse.
        entries[id]?.file?.takeIf { it.isFile }?.let { return id }
        val dest = File(inDir, "${id}_$safe")
        val copied = when {
            src.isFile && src.canonicalPath == dest.canonicalPath -> true
            src.isFile -> try {
                if (src.length() > maxBytes) throw TooLarge("file_too_large")
                src.copyTo(dest, overwrite = true)
                true
            } catch (e: TooLarge) {
                throw e
            } catch (_: Exception) {
                copyWithSu(src, dest)
            }
            else -> {
                // WeChat-private paths are invisible to Bridge; pull via Magisk su.
                copyWithSu(src, dest)
            }
        }
        if (!copied || !dest.isFile || dest.length() <= 0L) {
            throw IllegalArgumentException("incoming_missing")
        }
        if (dest.length() > maxBytes) {
            dest.delete()
            throw TooLarge("file_too_large")
        }
        entries[id] = Entry(dest, kind, safe)
        return id
    }

    private fun copyWithSu(src: File, dest: File): Boolean {
        dest.parentFile?.mkdirs()
        val script =
            "cp ${shellQuote(src.absolutePath)} ${shellQuote(dest.absolutePath)} && " +
                "chmod 644 ${shellQuote(dest.absolutePath)}"
        val cmds = listOf(
            arrayOf("su", "-c", script),
            arrayOf("su", "0", "sh", "-c", script),
        )
        for (cmd in cmds) {
            try {
                val p = Runtime.getRuntime().exec(cmd)
                if (p.waitFor() == 0 && dest.isFile && dest.length() > 0) return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    private fun shellQuote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    /** Register an already-staged readable path without copying (optional). */
    fun attachExisting(mediaId: String, file: File, kind: String, name: String) {
        entries[mediaId] = Entry(file, kind, sanitizeName(name))
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
