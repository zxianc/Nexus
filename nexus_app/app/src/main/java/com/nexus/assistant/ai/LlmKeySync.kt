package com.nexus.assistant.ai

import android.content.Context
import android.util.Log
import com.nexus.assistant.config.ConfigRepository
import java.io.BufferedReader
import java.io.InputStreamReader

/** Import DeepSeek API key from Magisk nexus config / secrets into App config. */
object LlmKeySync {
    fun syncFromMagisk(context: Context): Result<String> {
        val script =
            buildString {
                appendLine("set -e")
                appendLine("KEY=\"\"")
                appendLine("if [ -f /data/adb/nexus/secrets/deepseek.key ]; then")
                appendLine("  KEY=\$(tr -d '\\r\\n' </data/adb/nexus/secrets/deepseek.key)")
                appendLine("fi")
                appendLine("if [ -z \"\$KEY\" ] && [ -f /data/adb/nexus/config.json ]; then")
                appendLine(
                    "  KEY=\$(sed -n 's/.*\"api_key\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p' " +
                        "/data/adb/nexus/config.json | head -n1)",
                )
                appendLine("fi")
                appendLine("if [ -z \"\$KEY\" ]; then")
                appendLine("  echo EMPTY")
                appendLine("  exit 0")
                appendLine("fi")
                appendLine("echo KEY:\$KEY")
            }
        return try {
            val proc =
                ProcessBuilder("su", "-c", script)
                    .redirectErrorStream(true)
                    .start()
            val out =
                BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }.trim()
            proc.waitFor()
            when {
                out.contains("EMPTY") || !out.contains("KEY:") ->
                    Result.failure(IllegalStateException("Magisk 中未找到 api_key"))
                else -> {
                    val key = out.substringAfter("KEY:").lineSequence().first().trim()
                    if (key.length < 8) {
                        Result.failure(IllegalStateException("api_key 无效"))
                    } else {
                        val repo = ConfigRepository(context)
                        val cfg = repo.load()
                        repo.save(cfg.copy(llm = cfg.llm.copy(apiKey = key, enabled = true)))
                        Result.success("已导入 API key（尾号 ${key.takeLast(4)}）")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncFromMagisk", e)
            Result.failure(e)
        }
    }

    private const val TAG = "LlmKeySync"
}
