package com.nexus.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepSeekSseTest {
    @Test
    fun extractDeltaContent_parsesChoice() {
        val payload =
            """{"choices":[{"delta":{"content":"你好"},"index":0}]}"""
        assertEquals("你好", DeepSeekSse.extractDeltaContent(payload))
    }

    @Test
    fun extractDeltaContent_doneAndEmpty() {
        assertNull(DeepSeekSse.extractDeltaContent("[DONE]"))
        assertNull(DeepSeekSse.extractDeltaContent(""))
        assertNull(
            DeepSeekSse.extractDeltaContent("""{"choices":[{"delta":{},"index":0}]}"""),
        )
    }
}
