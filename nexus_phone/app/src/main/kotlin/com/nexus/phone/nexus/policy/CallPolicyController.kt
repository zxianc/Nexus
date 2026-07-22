package com.nexus.phone.nexus.policy

import com.nexus.phone.nexus.config.NexusConfig
import com.nexus.phone.nexus.config.SimPolicy

class CallPolicyController(
    private val loadConfig: () -> NexusConfig,
    private val takeoverEnabled: () -> Boolean,
    private val slotResolver: (accountId: String?, sortOrder: Int?) -> Int,
) {
    fun onRinging(
        accountId: String?,
        sortOrder: Int?,
        peerNumber: String,
    ): PolicyDecision {
        if (!takeoverEnabled()) {
            return PolicyDecision(listOf(PolicyAction.ShowIncomingUi), aiMode = false)
        }
        val slot = slotResolver(accountId, sortOrder)
        val policy = loadConfig().policyForSlot(slot)
        CallSessionState.noteRinging(slot, peerNumber, policy.toWire())
        return when (policy) {
            SimPolicy.AI -> {
                CallSessionState.aiMode = true
                CallSessionState.wasAiMode = true
                PolicyDecision(listOf(PolicyAction.AnswerAi), aiMode = true)
            }
            SimPolicy.REJECT -> {
                CallSessionState.aiMode = false
                PolicyDecision(listOf(PolicyAction.Reject), aiMode = false)
            }
            SimPolicy.HUMAN -> {
                CallSessionState.aiMode = false
                PolicyDecision(listOf(PolicyAction.ShowIncomingUi), aiMode = false)
            }
        }
    }

    fun onActive(): PolicyDecision {
        val ai = CallSessionState.aiMode || CallSessionState.wasAiMode
        return if (ai) {
            CallSessionState.aiMode = true
            PolicyDecision(listOf(PolicyAction.StartBypass), aiMode = true)
        } else {
            PolicyDecision(listOf(PolicyAction.None), aiMode = false)
        }
    }

    fun onDisconnected(): PolicyDecision {
        CallSessionState.aiMode = false
        return PolicyDecision(listOf(PolicyAction.EndBypass), aiMode = false)
    }
}
