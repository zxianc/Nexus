package com.nexus.assistant.config

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

class SimInfoReader(private val context: Context) {
    fun read(): List<DeviceSimInfo> {
        val sm =
            context.getSystemService(SubscriptionManager::class.java)
                ?: return emptyList()
        val list =
            try {
                sm.activeSubscriptionInfoList
            } catch (_: SecurityException) {
                null
            } ?: return emptyList()

        return list
            .map { info ->
                val carrier =
                    sequenceOf(info.carrierName, info.displayName)
                        .mapNotNull { it?.toString()?.trim() }
                        .firstOrNull { it.isNotEmpty() }
                        .orEmpty()
                val number = readNumber(sm, info.subscriptionId, info.number)
                DeviceSimInfo(
                    slot = info.simSlotIndex,
                    subscriptionId = info.subscriptionId,
                    carrier = carrier,
                    number = number,
                )
            }.sortedBy { it.slot }
    }

    fun subIdToSlot(): Map<Int, Int> = read().associate { it.subscriptionId to it.slot }

    private fun readNumber(
        sm: SubscriptionManager,
        subId: Int,
        infoNumber: String?,
    ): String {
        val candidates = ArrayList<String>()
        infoNumber?.trim()?.takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                sm.getPhoneNumber(subId)?.trim()?.takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
            } catch (_: Exception) {
            }
        }
        try {
            val tm =
                context
                    .getSystemService(TelephonyManager::class.java)
                    ?.createForSubscriptionId(subId)
            tm?.line1Number?.trim()?.takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
        } catch (_: Exception) {
        }
        return candidates.firstOrNull().orEmpty()
    }
}
