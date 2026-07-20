package com.nexus.assistant.config

import org.junit.Assert.assertEquals
import org.junit.Test

class NexusConfigTest {
    @Test
    fun default_roundTrip() {
        val json = NexusConfig.default().toJson()
        val back = NexusConfig.fromJson(json)
        assertEquals(SimPolicy.HUMAN, back.sims[0].policy)
        assertEquals(2, back.sims.size)
        assertEquals(true, back.llm.enabled)
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
}
