package com.nexus.assistant.ai

import android.content.Context
import com.nexus.assistant.config.ConfigRepository
import java.io.File

data class ModelLayout(
    val senseVoiceDir: File,
    val vitsDir: File,
) {
    val senseVoiceModel: File get() = File(senseVoiceDir, "model.int8.onnx")
    val senseVoiceTokens: File get() = File(senseVoiceDir, "tokens.txt")
    val vitsModel: File get() = File(vitsDir, "model.onnx")
    val vitsTokens: File get() = File(vitsDir, "tokens.txt")
    val vitsLexicon: File get() = File(vitsDir, "lexicon.txt")

    fun asrReady(): Boolean = senseVoiceModel.isFile && senseVoiceTokens.isFile

    fun ttsReady(): Boolean =
        vitsModel.isFile && vitsTokens.isFile && vitsLexicon.isFile

    fun missing(): List<String> {
        val miss = ArrayList<String>()
        if (!senseVoiceModel.isFile) miss.add(senseVoiceModel.absolutePath)
        if (!senseVoiceTokens.isFile) miss.add(senseVoiceTokens.absolutePath)
        if (!vitsModel.isFile) miss.add(vitsModel.absolutePath)
        if (!vitsTokens.isFile) miss.add(vitsTokens.absolutePath)
        if (!vitsLexicon.isFile) miss.add(vitsLexicon.absolutePath)
        return miss
    }
}

object ModelPaths {
    const val MAGISK_MODELS = "/data/adb/modules/nexus_models/models"

    fun appModelsRoot(context: Context): File = File(context.filesDir, "models")

    fun resolve(context: Context): ModelLayout {
        val cfgDir = ConfigRepository(context).load().modelDir
        val roots =
            buildList {
                if (!cfgDir.isNullOrBlank()) add(File(cfgDir))
                add(appModelsRoot(context))
                add(File(MAGISK_MODELS))
            }
        for (root in roots) {
            val layout =
                ModelLayout(
                    senseVoiceDir = File(root, "sense-voice"),
                    vitsDir = File(root, "vits-zh-ll"),
                )
            if (layout.asrReady() || layout.ttsReady()) {
                return layout
            }
        }
        return ModelLayout(
            senseVoiceDir = File(appModelsRoot(context), "sense-voice"),
            vitsDir = File(appModelsRoot(context), "vits-zh-ll"),
        )
    }
}
