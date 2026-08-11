package com.nexus.tim.bridge.store

import android.os.Environment
import android.util.Log
import java.io.File
import java.util.UUID

/**
 * Stage files so the TIM process can read them.
 * Tries Magisk `su 0`, then `/storage/emulated/0/nexus_tim`.
 */
object SharedStaging {
    private const val TAG = "TimStaging"
    private const val TMP_ROOT = "/data/local/tmp/nexus_tim"

    /** Best-effort: create world-writable staging dirs for Hook ↔ Bridge media. */
    fun ensureDirs() {
        val script =
            "mkdir -p $TMP_ROOT/in $TMP_ROOT/out && chmod 777 $TMP_ROOT $TMP_ROOT/in $TMP_ROOT/out"
        for (cmd in listOf(arrayOf("su", "-c", script), arrayOf("su", "0", "sh", "-c", script))) {
            try {
                val p = Runtime.getRuntime().exec(cmd)
                if (p.waitFor() == 0) {
                    Log.i(TAG, "staging dirs ready under $TMP_ROOT")
                    return
                }
            } catch (t: Throwable) {
                Log.w(TAG, "ensureDirs su failed: ${t.message}")
            }
        }
        for (root in listOf(File("/sdcard"), File("/storage/emulated/0"))) {
            try {
                File(root, "nexus_tim/in").mkdirs()
                File(root, "nexus_tim/out").mkdirs()
            } catch (_: Throwable) {
            }
        }
    }

    fun stageOutgoing(src: File, name: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._\\-]"), "_").ifEmpty { "bin" }.take(80)
        val leaf = "${UUID.randomUUID()}_$safe"
        val staged = stageWithSu(src, "out", leaf)
            ?: stageOnPublic(src, "out", leaf)
            ?: throw IllegalStateException("stage_failed")
        Log.i(TAG, "staged ${src.absolutePath} -> ${staged.absolutePath}")
        return staged
    }

    private fun stageWithSu(src: File, sub: String, leaf: String): File? {
        val destPath = "$TMP_ROOT/$sub/$leaf"
        val script =
            "mkdir -p $TMP_ROOT/$sub && cp ${shellQuote(src.absolutePath)} ${shellQuote(destPath)} && " +
                "chmod 755 $TMP_ROOT $TMP_ROOT/$sub && chmod 644 ${shellQuote(destPath)}"
        val cmds = listOf(
            arrayOf("su", "0", "sh", "-c", script),
            arrayOf("su", "-c", script),
        )
        for (cmd in cmds) {
            try {
                val p = Runtime.getRuntime().exec(cmd)
                val code = p.waitFor()
                val err = p.errorStream.bufferedReader().readText()
                val out = File(destPath)
                if (code == 0 && out.isFile && out.length() > 0) return out
                Log.w(TAG, "su stage exit=$code err=${err.take(200)}")
            } catch (t: Throwable) {
                Log.w(TAG, "su stage failed: ${t.message}")
            }
        }
        return null
    }

    private fun stageOnPublic(src: File, sub: String, leaf: String): File? {
        val roots = mutableListOf<File>()
        try {
            Environment.getExternalStorageDirectory()?.let { roots.add(it) }
        } catch (_: Throwable) {
        }
        roots.add(File("/storage/emulated/0"))
        roots.add(File("/sdcard"))
        for (root in roots.distinctBy { it.absolutePath }) {
            try {
                val dir = File(root, "nexus_tim/$sub")
                if (!dir.exists() && !dir.mkdirs()) continue
                val dest = File(dir, leaf)
                src.copyTo(dest, overwrite = true)
                dest.setReadable(true, false)
                if (dest.isFile && dest.length() > 0) return dest
            } catch (t: Throwable) {
                Log.w(TAG, "public stage ${root.absolutePath} failed: ${t.message}")
            }
        }
        return null
    }

    private fun shellQuote(path: String): String = "'" + path.replace("'", "'\\''") + "'"
}
