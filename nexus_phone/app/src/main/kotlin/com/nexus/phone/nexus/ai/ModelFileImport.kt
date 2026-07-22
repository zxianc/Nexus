package com.nexus.phone.nexus.ai

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Import a picked model onnx into app-private storage and copy same-dir sidecars.
 */
object ModelFileImport {
    private const val TAG = "ModelFileImport"

    data class Imported(
        val modelFile: File,
        val copiedSidecars: List<String>,
        val missingSidecars: List<String>,
    )

    fun importStt(context: Context, modelUri: Uri): Result<Imported> =
        importBundle(
            context = context,
            modelUri = modelUri,
            destDir = ModelPaths.importedSttDir(context),
            requiredSidecars = listOf("tokens.txt"),
            optionalSidecars = emptyList(),
        )

    fun importTts(context: Context, modelUri: Uri): Result<Imported> =
        importBundle(
            context = context,
            modelUri = modelUri,
            destDir = ModelPaths.importedTtsDir(context),
            requiredSidecars = listOf("tokens.txt", "lexicon.txt"),
            optionalSidecars = listOf("phone.fst", "date.fst", "number.fst"),
        )

    private fun importBundle(
        context: Context,
        modelUri: Uri,
        destDir: File,
        requiredSidecars: List<String>,
        optionalSidecars: List<String>,
    ): Result<Imported> {
        return try {
            val displayName = queryDisplayName(context, modelUri) ?: "model.onnx"
            if (!displayName.endsWith(".onnx", ignoreCase = true)) {
                return Result.failure(IllegalArgumentException("请选择 .onnx 模型文件"))
            }
            if (destDir.exists()) {
                destDir.listFiles()?.forEach { it.delete() }
            } else {
                destDir.mkdirs()
            }
            val modelDest = File(destDir, sanitizeName(displayName))
            copyUriToFile(context, modelUri, modelDest)

            val copied = ArrayList<String>()
            val missing = ArrayList<String>()
            for (name in requiredSidecars + optionalSidecars) {
                val ok = copySibling(context, modelUri, name, File(destDir, name))
                when {
                    ok -> copied.add(name)
                    name in requiredSidecars -> missing.add(name)
                }
            }
            if (missing.isNotEmpty()) {
                return Result.failure(
                    IllegalStateException("同目录缺少：${missing.joinToString()}（请与模型放在同一文件夹）"),
                )
            }
            Result.success(Imported(modelDest, copied, missing))
        } catch (e: Exception) {
            Log.e(TAG, "import failed", e)
            Result.failure(e)
        }
    }

    private fun copySibling(context: Context, modelUri: Uri, siblingName: String, dest: File): Boolean {
        // 1) Real filesystem parent (rare, but works for file:// and some roots)
        filePathFor(context, modelUri)?.parentFile?.let { parent ->
            val src = File(parent, siblingName)
            if (src.isFile) {
                src.copyTo(dest, overwrite = true)
                return true
            }
        }
        // 2) ExternalStorage DocumentsContract sibling id
        siblingDocumentUri(modelUri, siblingName)?.let { sib ->
            return try {
                copyUriToFile(context, sib, dest)
                true
            } catch (_: Exception) {
                false
            }
        }
        return false
    }

    private fun siblingDocumentUri(modelUri: Uri, siblingName: String): Uri? {
        if (modelUri.authority != "com.android.externalstorage.documents") return null
        return try {
            val docId = DocumentsContract.getDocumentId(modelUri)
            val slash = docId.lastIndexOf('/')
            if (slash <= 0) return null
            val siblingId = docId.substring(0, slash + 1) + siblingName
            DocumentsContract.buildDocumentUri(modelUri.authority, siblingId)
        } catch (_: Exception) {
            null
        }
    }

    private fun filePathFor(@Suppress("UNUSED_PARAMETER") context: Context, uri: Uri): File? {
        if (uri.scheme == "file") {
            val p = uri.path ?: return null
            return File(p).takeIf { it.isFile }
        }
        if (uri.authority == "com.android.externalstorage.documents") {
            try {
                val docId = DocumentsContract.getDocumentId(uri)
                // primary:Foo/bar.onnx → /storage/emulated/0/Foo/bar.onnx
                val split = docId.split(":", limit = 2)
                if (split.size == 2 && split[0] == "primary") {
                    val f = File("/storage/emulated/0/${split[1]}")
                    if (f.isFile) return f
                }
            } catch (_: Exception) {
            }
        }
        // App-private files exposed via FileProvider-like paths are uncommon; skip.
        return null
    }

    private fun copyUriToFile(context: Context, uri: Uri, dest: File) {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法打开 $uri" }
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx)
                }
            }
        return uri.lastPathSegment
    }

    private fun sanitizeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "model.onnx" }
}
