package com.nexus.phone.nexus.ai

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

    @Test
    fun extractDeltaContent_skipsNullContentAndReasoningOnly() {
        assertNull(
            DeepSeekSse.extractDeltaContent(
                """{"choices":[{"delta":{"content":null,"reasoning_content":"嗯"},"index":0}]}""",
            ),
        )
        assertNull(
            DeepSeekSse.extractDeltaContent(
                """{"choices":[{"delta":{"content":"","reasoning_content":"思考"},"index":0}]}""",
            ),
        )
    }
}
