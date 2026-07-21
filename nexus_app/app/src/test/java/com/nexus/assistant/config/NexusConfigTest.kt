package com.nexus.assistant.config

import org.junit.Assert.assertEquals
import org.junit.Test

class NexusConfigTest {
    @Test
    fun default_hasHumanPolicies() {
        val cfg = NexusConfig.default()
        assertEquals(SimPolicy.HUMAN, cfg.sims[0].policy)
        assertEquals(2, cfg.sims.size)
        assertEquals(true, cfg.llm.enabled)
        assertEquals(true, cfg.dialerTakeover)
    }

    @Test
    fun policyForSlot_readsAi() {
        val cfg =
            NexusConfig.default().copy(
                sims =
                    listOf(
                        SimConfig(0, "卡1", policy = SimPolicy.AI),
                        SimConfig(1, "卡2", policy = SimPolicy.REJECT),
                    ),
            )
        assertEquals(SimPolicy.AI, cfg.policyForSlot(0))
        assertEquals(SimPolicy.REJECT, cfg.policyForSlot(1))
    }

    @Test
    fun fromJson_legacy_missingTakeoverDefaultsOn() {
        val json =
            """
            {"sims":[{"slot":0,"label":"卡1","policy":"ai"},{"slot":1,"label":"卡2","policy":"human"}],
             "llm":{"enabled":true,"model":"deepseek-v4-flash","api_key":"x"},
             "notify":{"enabled":true,"webhook_url":"https://example.com"}}
            """.trimIndent()
        val cfg = NexusConfig.fromJson(json)
        assertEquals(true, cfg.dialerTakeover)
        assertEquals(SimPolicy.AI, cfg.policyForSlot(0))
        assertEquals("x", cfg.llm.apiKey)
    }
}
