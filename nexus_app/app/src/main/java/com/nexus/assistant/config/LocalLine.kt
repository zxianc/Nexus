package com.nexus.assistant.config

import android.content.Context

/** This device's line (which SIM received the call/SMS). */
data class LocalLine(
    val slot: Int,
    val number: String,
    val carrier: String,
    val label: String,
) {
    /** Human-readable for webhook / archive. */
    fun display(): String {
        val card = "卡${slot + 1}"
        val carrierPart = carrier.ifBlank { label }.ifBlank { "" }
        val num = number.ifBlank { "号码未知" }
        return buildString {
            append(card)
            if (carrierPart.isNotBlank() && carrierPart != card) {
                append(" ")
                append(carrierPart)
            }
            append(" ")
            append(num)
        }
    }
}

object LocalLineResolver {
    fun forSlot(context: Context, slot: Int): LocalLine {
        val cfg = ConfigRepository(context).refreshSimMetadata()
        val sim = cfg.sims.firstOrNull { it.slot == slot }
        return LocalLine(
            slot = slot,
            number = sim?.number.orEmpty(),
            carrier = sim?.carrier.orEmpty(),
            label = sim?.label.orEmpty().ifBlank { "卡${slot + 1}" },
        )
    }

    fun forSubscriptionId(context: Context, subscriptionId: Int): LocalLine {
        if (subscriptionId < 0) {
            return forSlot(context, 0)
        }
        val map = SimInfoReader(context).subIdToSlot()
        val slot = map[subscriptionId] ?: 0
        return forSlot(context, slot)
    }
}
