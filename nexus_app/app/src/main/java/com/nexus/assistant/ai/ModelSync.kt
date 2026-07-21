package com.nexus.assistant.ai

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Copy Magisk models into app-private filesDir (untrusted_app cannot read /data/adb).
 */
object ModelSync {
    fun syncFromMagisk(context: Context): Result<String> {
        val dest = ModelPaths.appModelsRoot(context).absolutePath
        val src = ModelPaths.MAGISK_MODELS
        val pkg = context.packageName
        val script =
            buildString {
                appendLine("set -e")
                appendLine("mkdir -p '$dest'")
                appendLine("cp -a '$src/sense-voice' '$dest/'")
                appendLine("cp -a '$src/vits-zh-ll' '$dest/'")
                appendLine("OWNER=\$(stat -c %u /data/data/$pkg)")
                appendLine("GROUP=\$(stat -c %g /data/data/$pkg)")
                appendLine("chown -R \"\$OWNER:\$GROUP\" '$dest'")
                appendLine("chmod -R u+rwX '$dest'")
                appendLine("echo OK")
            }

        return try {
            val proc =
                ProcessBuilder("su", "-c", script)
                    .redirectErrorStream(true)
                    .start()
            val out =
                BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            val code = proc.waitFor()
            if (code == 0 && out.contains("OK")) {
                val layout = ModelPaths.resolve(context)
                Result.success(
                    "synced asr=${layout.asrReady()} tts=${layout.ttsReady()} -> $dest",
                )
            } else {
                Result.failure(IllegalStateException("su sync failed code=$code out=$out"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncFromMagisk", e)
            Result.failure(e)
        }
    }

    private const val TAG = "ModelSync"
}
