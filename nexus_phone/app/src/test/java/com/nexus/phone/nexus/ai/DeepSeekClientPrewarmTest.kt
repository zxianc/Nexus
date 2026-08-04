package com.nexus.phone.nexus.ai

import org.junit.Test

class DeepSeekClientPrewarmTest {
    @Test
    fun prewarmWithEmptyKeyIsNoOp() {
        DeepSeekClient(apiKey = "").prewarm()
    }

    @Test
    fun prewarmWithBlankKeyIsNoOp() {
        DeepSeekClient(apiKey = "   ").prewarm()
    }
}
