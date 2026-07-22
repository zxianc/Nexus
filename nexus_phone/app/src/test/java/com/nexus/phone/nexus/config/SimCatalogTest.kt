package com.nexus.phone.nexus.config

import org.junit.Assert.assertEquals
import org.junit.Test

class SimCatalogTest {
    @Test
    fun merge_preservesPolicyAndFillsCarrierNumber() {
        val cfg =
            NexusConfig.default().copy(
                sims =
                    listOf(
                        SimConfig(0, "卡1", policy = SimPolicy.AI),
                        SimConfig(1, "卡2", policy = SimPolicy.HUMAN),
                    ),
            )
        val device =
            listOf(
                DeviceSimInfo(0, 2, "CMCC", "+8613909355158"),
                DeviceSimInfo(1, 1, "CHN-UNICOM", "+85256347310"),
            )
        val merged = SimCatalog.merge(cfg, device)
        assertEquals(SimPolicy.AI, merged.sims[0].policy)
        assertEquals("CMCC", merged.sims[0].carrier)
        assertEquals("+8613909355158", merged.sims[0].number)
        assertEquals("CMCC", merged.sims[0].label)
        assertEquals(SimPolicy.HUMAN, merged.sims[1].policy)
        assertEquals("CHN-UNICOM", merged.sims[1].carrier)
        assertEquals("+85256347310", merged.sims[1].number)
    }

    @Test
    fun slotFromPhoneAccount_prefersSortOrder() {
        assertEquals(0, SimCatalog.slotFromPhoneAccount("2", sortOrder = 0, subIdToSlot = mapOf(2 to 0)))
        assertEquals(1, SimCatalog.slotFromPhoneAccount("1", sortOrder = 1, subIdToSlot = mapOf(1 to 1)))
    }

    @Test
    fun slotFromPhoneAccount_fallsBackToSubIdMap() {
        assertEquals(0, SimCatalog.slotFromPhoneAccount("2", sortOrder = -1, subIdToSlot = mapOf(2 to 0, 1 to 1)))
        assertEquals(1, SimCatalog.slotFromPhoneAccount("1", sortOrder = null, subIdToSlot = mapOf(2 to 0, 1 to 1)))
    }
}
