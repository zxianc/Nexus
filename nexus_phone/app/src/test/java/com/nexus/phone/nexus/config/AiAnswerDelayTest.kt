package com.nexus.phone.nexus.config

import org.junit.Assert.assertEquals
import org.junit.Test

class AiAnswerDelayTest {
    @Test
    fun clamp_keepsDefaultInRange() {
        assertEquals(3000, ConfigRepository.clampAiAnswerDelayMs(3000))
    }

    @Test
    fun clamp_raisesBelowMin() {
        assertEquals(ConfigRepository.AI_ANSWER_DELAY_MS_MIN, ConfigRepository.clampAiAnswerDelayMs(0))
        assertEquals(ConfigRepository.AI_ANSWER_DELAY_MS_MIN, ConfigRepository.clampAiAnswerDelayMs(500))
    }

    @Test
    fun clamp_capsAboveMax() {
        assertEquals(ConfigRepository.AI_ANSWER_DELAY_MS_MAX, ConfigRepository.clampAiAnswerDelayMs(10_000))
    }

    @Test
    fun clamp_acceptsEdges() {
        assertEquals(1000, ConfigRepository.clampAiAnswerDelayMs(1000))
        assertEquals(5000, ConfigRepository.clampAiAnswerDelayMs(5000))
    }

    @Test
    fun defaultConfig_usesThreeSeconds() {
        assertEquals(3000, NexusConfig.default().aiAnswerDelayMs)
    }
}
