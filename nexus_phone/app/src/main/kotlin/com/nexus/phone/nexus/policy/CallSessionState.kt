package com.nexus.phone.nexus.policy

enum class PolicyAction {
    AnswerAi,
    Reject,
    ShowIncomingUi,
    StartBypass,
    EndBypass,
    None,
}

data class PolicyDecision(
    val actions: List<PolicyAction>,
    val aiMode: Boolean,
)

object CallSessionState {
    @Volatile
    var aiMode: Boolean = false

    @Volatile
    var wasAiMode: Boolean = false

    @Volatile
    var slot: Int = 0

    @Volatile
    var peerNumber: String = ""

    @Volatile
    var policyWire: String = "human"

    fun reset() {
        aiMode = false
        wasAiMode = false
        slot = 0
        peerNumber = ""
        policyWire = "human"
    }

    fun noteRinging(slot: Int, peer: String, policyWire: String) {
        this.slot = slot
        this.peerNumber = peer
        this.policyWire = policyWire
    }
}
