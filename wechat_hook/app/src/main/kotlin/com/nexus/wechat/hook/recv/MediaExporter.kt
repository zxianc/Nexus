package com.nexus.wechat.hook.recv

import android.content.Context
import android.util.Log
import com.nexus.wechat.hook.MainHook
import java.io.File
import java.util.UUID

/**
 * Export WeChat media files to a Bridge-readable staging directory.
 */
object MediaExporter {
    private const val EXPORT_ROOT = "/data/local/tmp/nexus_wechat/in"
    private const val SD_ROOT = "/sdcard/nexus_wechat/in"

    fun export(
        context: Context?,
        msgType: Int,
        mediaHint: String?,
        preferredName: String,
    ): ExportResult? {
        val kind = kindFor(msgType) ?: return null
        val src = resolveMediaFile(context, msgType, mediaHint) ?: return null
        if (src.length() <= 0L) {
            Log.w(MainHook.TAG, "export skip empty source ${src.absolutePath}")
            return null
        }
        val mediaId = UUID.randomUUID().toString().replace("-", "").take(16)
        val ext = guessExt(kind, preferredName, src.name)
        val leaf = "${mediaId}_$preferredName".replace(Regex("[^A-Za-z0-9._\\-]"), "_")
            .let { if (it.contains('.')) it else "$it$ext" }
            .take(100)
        val dest = copyWorldReadable(context, src, leaf) ?: return null
        Log.i(MainHook.TAG, "exported $kind ${src.absolutePath} -> ${dest.absolutePath}")
        return ExportResult(mediaId = mediaId, path = dest.absolutePath, kind = kind, name = leaf)
    }

    data class ExportResult(
        val mediaId: String,
        val path: String,
        val kind: String,
        val name: String,
    )

    fun kindFor(type: Int): String? = when (type) {
        3 -> "image"
        49 -> "file"
        else -> null
    }

    private fun guessExt(kind: String, preferred: String, srcName: String): String {
        val fromPreferred = preferred.substringAfterLast('.', missingDelimiterValue = "")
        if (fromPreferred.length in 1..5) return ".$fromPreferred"
        val fromSrc = srcName.substringAfterLast('.', missingDelimiterValue = "")
        if (fromSrc.length in 1..5) return ".$fromSrc"
        return if (kind == "image") ".jpg" else ".bin"
    }

    private fun copyWorldReadable(context: Context?, src: File, leaf: String): File? {
        // Prefer shared staging (Bridge can read without su) when pre-created 0777.
        val candidates = mutableListOf(
            File(EXPORT_ROOT, leaf),
            File(SD_ROOT, leaf),
        )
        context?.externalCacheDir?.let { candidates.add(File(File(it, "nexus_wechat_in"), leaf)) }
        context?.cacheDir?.let { candidates.add(File(File(it, "nexus_wechat_in"), leaf)) }
        for (dest in candidates) {
            try {
                val parent = dest.parentFile ?: continue
                if (!parent.exists() && !parent.mkdirs()) {
                    Log.w(MainHook.TAG, "export mkdirs failed ${parent.absolutePath}")
                    continue
                }
                src.copyTo(dest, overwrite = true)
                dest.setReadable(true, false)
                dest.setWritable(true, false)
                parent.setReadable(true, false)
                parent.setExecutable(true, false)
                if (dest.isFile && dest.length() > 0) return dest
            } catch (t: Throwable) {
                Log.w(MainHook.TAG, "export copy failed ${dest.absolutePath}: ${t.message}")
            }
        }
        return null
    }

    private fun resolveMediaFile(context: Context?, type: Int, mediaHint: String?): File? {
        if (mediaHint.isNullOrBlank()) return null
        directFile(mediaHint)?.let { resolved ->
            resolveUuidPointer(resolved, context)?.takeIf { it.length() > 0 }?.let { return it }
            if (resolved.length() > 0 && !looksLikeUuidPointer(resolved)) return resolved
        }
        val names = candidateNames(type, mediaHint)
        if (names.isEmpty()) return null
        val appRoot = context?.filesDir?.parentFile ?: return null
        val microMsg = File(appRoot, "MicroMsg")
        val profiles = microMsg.listFiles() ?: return null
        val roots = when (type) {
            3 -> arrayOf("image2")
            49 -> arrayOf("attachment", "openapi", "image2")
            else -> arrayOf("image2", "attachment")
        }
        var best: File? = null
        for (profile in profiles) {
            if (profile == null || !profile.isDirectory) continue
            // Prefer account dirs that look like md5 hashes.
            if (profile.name.length < 16 && profile.name != "image2") continue
            for (rootName in roots) {
                val found = findNamedFile(File(profile, rootName), names, depth = 6, visited = intArrayOf(0))
                if (found != null) {
                    val preferred = preferBestImage(found, profile)
                    if (best == null || preferred.length() > best!!.length()) {
                        best = preferred
                    }
                }
            }
        }
        return best?.takeIf { it.length() > 0 && !looksLikeUuidPointer(it) }
    }

    private fun preferBestImage(file: File, profile: File): File {
        val parent = file.parentFile ?: return resolveUuidPointer(file, profile = profile) ?: file
        val name = file.name.trim().trimEnd('\u0000')
        val base = when {
            name.endsWith("hd") && name.startsWith("th_") -> name.removeSuffix("hd")
            name.startsWith("th_") -> name
            else -> name
        }
        val hash = base.removePrefix("th_")
        val local = listOfNotNull(
            File(parent, "${hash}.jpg").takeIf { it.isFile },
            File(parent, hash).takeIf { it.isFile },
            File(parent, "th_${hash}hd").takeIf { it.isFile },
            File(parent, "th_$hash").takeIf { it.isFile },
            file,
        )
        val resolved = local.mapNotNull { resolveUuidPointer(it, profile = profile) ?: it.takeIf { f -> f.length() > 0 && !looksLikeUuidPointer(f) } }
        return resolved.maxByOrNull { it.length() }
            ?: resolveUuidPointer(file, profile = profile)
            ?: file
    }

    /** WeChat 8.0.76: th_* may contain a UUID; bytes live under image2/.ref/d/{uuid}. */
    private fun resolveUuidPointer(file: File, context: Context? = null, profile: File? = null): File? {
        if (!file.isFile) return null
        val uuid = readUuidPointer(file) ?: return null
        val profiles = mutableListOf<File>()
        if (profile != null) profiles.add(profile)
        val appRoot = context?.filesDir?.parentFile
        val microMsg = appRoot?.let { File(it, "MicroMsg") }
        microMsg?.listFiles()?.filter { it.isDirectory && it.name.length >= 16 }?.let { profiles.addAll(it) }
        // Also walk up from th_ path: .../image2/xx/yy/th_* → account dir.
        file.parentFile?.parentFile?.parentFile?.let { image2 ->
            if (image2.name == "image2") image2.parentFile?.let { profiles.add(0, it) }
        }
        for (p in profiles.distinctBy { it.absolutePath }) {
            val refD = File(File(File(p, "image2"), ".ref"), "d")
            val candidate = File(refD, uuid)
            if (candidate.isFile && candidate.length() > 0) return candidate
        }
        return null
    }

    private fun looksLikeUuidPointer(file: File): Boolean = readUuidPointer(file) != null

    private fun readUuidPointer(file: File): String? {
        if (!file.isFile || file.length() !in 32L..64L) return null
        val text = try {
            file.readBytes().toString(Charsets.UTF_8).trim().trimEnd('\u0000')
        } catch (_: Throwable) {
            return null
        }
        return text.takeIf { UUID_POINTER.matches(it) }
    }

    private fun directFile(hint: String): File? {
        val normalized = normalizeHint(hint)
        val candidates = listOf(hint.trim(), normalized)
        for (c in candidates) {
            if (c.isEmpty()) continue
            val f = File(c)
            if (f.isFile) return f
        }
        return null
    }

    private fun candidateNames(type: Int, hint: String): List<String> {
        val names = ArrayList<String>()
        val normalized = normalizeHint(hint)
        add(names, normalized)
        val slash = maxOf(normalized.lastIndexOf('/'), normalized.lastIndexOf(File.separatorChar))
        if (slash >= 0 && slash + 1 < normalized.length) {
            add(names, normalized.substring(slash + 1))
        }
        val snapshot = ArrayList(names)
        for (c in snapshot) {
            if (type == 3 && c.startsWith("th_") && c.length > 3) {
                add(names, c.substring(3))
                add(names, "${c}hd")
                add(names, "th_${c.substring(3)}hd")
            }
        }
        return names
    }

    private fun normalizeHint(hint: String): String {
        var value = hint.trim()
        val q = value.indexOf('?')
        if (q >= 0) value = value.substring(0, q)
        // THUMBNAIL_DIRPATH://th_xxx  → th_xxx
        val scheme = value.indexOf("://")
        if (scheme >= 0 && scheme + 3 < value.length) {
            value = value.substring(scheme + 3)
        }
        return value.trim()
    }

    private fun add(names: MutableList<String>, name: String) {
        val v = name.trim()
        if (v.isEmpty()) return
        if (names.none { it == v }) names.add(v)
    }

    private fun findNamedFile(root: File, names: List<String>, depth: Int, visited: IntArray): File? {
        if (!root.isDirectory || depth < 0 || visited[0] > 8000) return null
        val files = root.listFiles() ?: return null
        var best: File? = null
        for (f in files) {
            visited[0]++
            val name = f.name.trim().trimEnd('\u0000')
            if (f.isFile && f.length() > 0 && names.any { name == it || name.contains(it) }) {
                if (best == null || f.length() > best!!.length()) best = f
            }
        }
        // Prefer scanning siblings before descending; still recurse for image2/xx/yy layout.
        for (f in files) {
            if (f.isDirectory && f.name != ".ref") {
                val found = findNamedFile(f, names, depth - 1, visited)
                if (found != null) {
                    if (best == null || found.length() > best!!.length()) best = found
                }
            }
        }
        return best
    }

    private val UUID_POINTER = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
    )
}
