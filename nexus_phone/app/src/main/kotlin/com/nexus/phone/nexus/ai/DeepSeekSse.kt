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
            val el = delta.get("content") ?: return null
            if (el.isJsonNull) return null
            val content = el.asString
            content.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }
}
