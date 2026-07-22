package com.nexus.phone.nexus.telecom

import com.nexus.phone.nexus.policy.CallSessionState

/**
 * Session meta for archive / fallback answer. Mirrors former nexus_app CallStore.
 */
object CallStore {
    var current: Any?
        get() = null
        set(@Suppress("UNUSED_PARAMETER") value) {}

    var aiMode: Boolean
        get() = CallSessionState.aiMode
        set(value) {
            CallSessionState.aiMode = value
        }

    var wasAiMode: Boolean
        get() = CallSessionState.wasAiMode
        set(value) {
            CallSessionState.wasAiMode = value
        }

    var slot: Int
        get() = CallSessionState.slot
        set(value) {
            CallSessionState.slot = value
        }

    var peerNumber: String
        get() = CallSessionState.peerNumber
        set(value) {
            CallSessionState.peerNumber = value
        }

    var policy: String
        get() = CallSessionState.policyWire
        set(value) {
            CallSessionState.policyWire = value
        }

    @Volatile
    var startedAtMs: Long = 0L

    @Volatile
    var archivePending: Boolean = false

    fun noteRinging(slot: Int, number: String, policy: String) {
        CallSessionState.noteRinging(slot, number, policy)
        if (startedAtMs <= 0L) {
            startedAtMs = System.currentTimeMillis()
        }
        archivePending = true
    }

    fun clearCallMeta() {
        CallSessionState.reset()
        startedAtMs = 0L
        archivePending = false
    }
}
