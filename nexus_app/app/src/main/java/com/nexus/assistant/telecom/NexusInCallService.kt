package com.nexus.assistant.telecom

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.nexus.assistant.config.ConfigRepository
import com.nexus.assistant.config.SimPolicy
import com.nexus.assistant.service.NexusBypassService

class NexusInCallService : InCallService() {
    private val repo by lazy { ConfigRepository(this) }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallStore.current = call
        call.registerCallback(
            object : Call.Callback() {
                override fun onStateChanged(call: Call, state: Int) {
                    handleState(call, state)
                }
            },
        )
        handleState(call, call.state)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (CallStore.current == call) {
            CallStore.current = null
        }
        NexusBypassService.endSession(this)
    }

    private fun handleState(call: Call, state: Int) {
        when (state) {
            Call.STATE_RINGING -> {
                when (policyFor(call)) {
                    SimPolicy.AI -> call.answer(VideoProfile.STATE_AUDIO_ONLY)
                    SimPolicy.REJECT -> call.reject(false, null)
                    SimPolicy.HUMAN -> {
                        startActivity(
                            IncomingCallActivity.intent(this, call).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
            Call.STATE_ACTIVE -> {
                startActivity(
                    InCallActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                val ai =
                    policyFor(call) == SimPolicy.AI || CallStore.aiMode
                if (ai) {
                    CallStore.aiMode = true
                    NexusBypassService.startSession(this)
                }
            }
            Call.STATE_DISCONNECTED -> {
                NexusBypassService.endSession(this)
                CallStore.aiMode = false
            }
        }
    }

    private fun policyFor(call: Call): SimPolicy {
        val slot = call.details?.accountHandle?.id?.toIntOrNull() ?: 0
        return repo.load().policyForSlot(slot)
    }
}

object CallStore {
    @Volatile
    var current: Call? = null

    @Volatile
    var aiMode: Boolean = false
}
