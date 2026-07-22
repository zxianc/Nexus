package com.nexus.phone.nexus.ai

import com.google.gson.JsonParser

object DeepSeekSse {
    fun extractDeltaContent(dataPayload: String): String? {
        val payload = dataPayload.trim()
        if (payload.isEmpty() || payload == "[DONE]") return null
        return try {
            val root = JsonParser.parseString(payload).asJsonObject
            val choices = root.getAsJsonArray("choices") ?: return null
            if (choices.size() == 0) return null
            val delta = choices[0].asJsonObject.getAsJsonObject("delta") ?: return null
            // Prefer assistant content. Ignore reasoning_content (chain-of-thought) for phone TTS.
            textOrNull(delta.get("content"))
        } catch (_: Exception) {
            null
        }
    }

    private fun textOrNull(el: com.google.gson.JsonElement?): String? {
        if (el == null || el.isJsonNull) return null
        return try {
            el.asString.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }
}
