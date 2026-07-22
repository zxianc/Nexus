package com.nexus.phone.nexus.config

data class DeviceSimInfo(
    val slot: Int,
    val subscriptionId: Int,
    val carrier: String,
    val number: String,
)

/**
 * Merge device SIM metadata into config and resolve PhoneAccount → slot.
 * Slot is logical SIM index (0/1), not PhoneAccountHandle.id (often subId).
 */
object SimCatalog {
    fun merge(cfg: NexusConfig, device: List<DeviceSimInfo>): NexusConfig {
        val bySlot = device.filter { it.slot >= 0 }.associateBy { it.slot }
        val slots =
            (cfg.sims.map { it.slot } + bySlot.keys + listOf(0, 1))
                .distinct()
                .sorted()
                .filter { it in 0..1 }
        val sims =
            slots.map { slot ->
                val existing = cfg.sims.firstOrNull { it.slot == slot }
                val dev = bySlot[slot]
                val carrier = dev?.carrier?.ifBlank { null } ?: existing?.carrier.orEmpty()
                val number = dev?.number?.ifBlank { null } ?: existing?.number.orEmpty()
                val label =
                    when {
                        carrier.isNotBlank() -> carrier
                        else -> existing?.label?.ifBlank { null } ?: "卡${slot + 1}"
                    }
                SimConfig(
                    slot = slot,
                    label = label,
                    carrier = carrier,
                    number = number,
                    policy = existing?.policy ?: SimPolicy.HUMAN,
                )
            }
        return cfg.copy(sims = sims)
    }

    fun slotFromPhoneAccount(
        accountId: String?,
        sortOrder: Int?,
        subIdToSlot: Map<Int, Int>,
    ): Int {
        if (sortOrder != null && sortOrder >= 0) {
            return sortOrder
        }
        val subId = accountId?.toIntOrNull()
        if (subId != null) {
            subIdToSlot[subId]?.let { return it }
        }
        return 0
    }

    fun policyLabel(policy: SimPolicy): String =
        when (policy) {
            SimPolicy.HUMAN -> "人工"
            SimPolicy.AI -> "AI"
            SimPolicy.REJECT -> "拒接"
        }
}
