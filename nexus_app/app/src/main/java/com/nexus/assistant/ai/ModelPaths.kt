package com.nexus.assistant.ai

import android.content.Context
import com.nexus.assistant.config.ConfigRepository
import java.io.File

/**
 * Resolved model files. STT/TTS are independent; names need not be sense-voice / vits-zh-ll.
 */
data class ModelLayout(
    val asrModel: File,
    val asrTokens: File,
    val ttsModel: File,
    val ttsTokens: File,
    val ttsLexicon: File,
    /** Directory for optional TTS rule FSTs (phone.fst, …). */
    val ttsSidecarDir: File,
) {
    fun asrReady(): Boolean = asrModel.isFile && asrTokens.isFile

    fun ttsReady(): Boolean =
        ttsModel.isFile && ttsTokens.isFile && ttsLexicon.isFile

    fun missing(): List<String> {
        val miss = ArrayList<String>()
        if (!asrModel.isFile) miss.add(asrModel.absolutePath)
        if (!asrTokens.isFile) miss.add(asrTokens.absolutePath)
        if (!ttsModel.isFile) miss.add(ttsModel.absolutePath)
        if (!ttsTokens.isFile) miss.add(ttsTokens.absolutePath)
        if (!ttsLexicon.isFile) miss.add(ttsLexicon.absolutePath)
        return miss
    }
}

object ModelPaths {
    fun appModelsRoot(context: Context): File = File(context.filesDir, "models")

    fun defaultSttDir(context: Context): File = File(appModelsRoot(context), "sense-voice")

    fun defaultTtsDir(context: Context): File = File(appModelsRoot(context), "vits-zh-ll")

    fun importedSttDir(context: Context): File = File(context.filesDir, "imported/stt")

    fun importedTtsDir(context: Context): File = File(context.filesDir, "imported/tts")

    fun resolve(context: Context): ModelLayout {
        val cfg = ConfigRepository(context).load()
        val legacyRoot =
            cfg.modelDir?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it) }

        val asrModel =
            cfg.sttModelPath?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isFile }
                ?: pickDefaultAsrModel(context, legacyRoot)
        val asrTokens = File(asrModel.parentFile ?: asrModel, "tokens.txt")

        val ttsModel =
            cfg.ttsModelPath?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isFile }
                ?: pickDefaultTtsModel(context, legacyRoot)
        val ttsDir = ttsModel.parentFile ?: ttsModel
        return ModelLayout(
            asrModel = asrModel,
            asrTokens = asrTokens,
            ttsModel = ttsModel,
            ttsTokens = File(ttsDir, "tokens.txt"),
            ttsLexicon = File(ttsDir, "lexicon.txt"),
            ttsSidecarDir = ttsDir,
        )
    }

    private fun pickDefaultAsrModel(context: Context, legacyRoot: File?): File {
        val dirs =
            buildList {
                if (legacyRoot != null) add(File(legacyRoot, "sense-voice"))
                if (legacyRoot != null) add(legacyRoot)
                add(defaultSttDir(context))
            }
        for (dir in dirs) {
            findOnnx(dir, preferInt8 = true)?.let { return it }
        }
        return File(defaultSttDir(context), "model.int8.onnx")
    }

    private fun pickDefaultTtsModel(context: Context, legacyRoot: File?): File {
        val dirs =
            buildList {
                if (legacyRoot != null) add(File(legacyRoot, "vits-zh-ll"))
                if (legacyRoot != null) add(legacyRoot)
                add(defaultTtsDir(context))
            }
        for (dir in dirs) {
            findOnnx(dir, preferInt8 = false)?.let { return it }
        }
        return File(defaultTtsDir(context), "model.onnx")
    }

    /** Prefer model.int8.onnx / model.onnx, else first *.onnx in dir. */
    fun findOnnx(dir: File, preferInt8: Boolean): File? {
        if (!dir.isDirectory) return null
        val int8 = File(dir, "model.int8.onnx")
        val plain = File(dir, "model.onnx")
        if (preferInt8 && int8.isFile) return int8
        if (plain.isFile) return plain
        if (!preferInt8 && int8.isFile) return int8
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".onnx", ignoreCase = true) }
            ?.minByOrNull { it.name.lowercase() }
    }
}
