package com.nexus.phone.services

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import org.fossify.commons.extensions.canUseFullScreenIntent
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_POST_NOTIFICATIONS
import com.nexus.phone.activities.CallActivity
import com.nexus.phone.extensions.config
import com.nexus.phone.extensions.isOutgoing
import com.nexus.phone.extensions.keyguardManager
import com.nexus.phone.extensions.powerManager
import com.nexus.phone.helpers.CallManager
import com.nexus.phone.helpers.CallNotificationManager
import com.nexus.phone.helpers.NoCall
import com.nexus.phone.models.Events
import com.nexus.phone.nexus.config.ConfigRepository
import com.nexus.phone.nexus.policy.CallPolicyBindings
import com.nexus.phone.nexus.policy.PolicyAction
import com.nexus.phone.nexus.service.BypassCommands
import org.greenrobot.eventbus.EventBus

class CallService : InCallService() {
    private val callNotificationManager by lazy { CallNotificationManager(this) }

    private val callListener =
        object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                super.onStateChanged(call, state)
                when (state) {
                    Call.STATE_ACTIVE -> {
                        val decision = CallPolicyBindings.controller(this@CallService).onActive()
                        if (decision.actions.contains(PolicyAction.StartBypass)) {
                            BypassCommands.startSession(this@CallService)
                            try {
                                startActivity(CallActivity.getStartIntent(this@CallService))
                            } catch (e: Exception) {
                                Log.w(TAG, "start CallActivity on ACTIVE", e)
                            }
                        }
                        callNotificationManager.setupNotification()
                    }
                    Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> {
                        CallPolicyBindings.cancelScheduledAnswer()
                        CallPolicyBindings.controller(this@CallService).onDisconnected()
                        BypassCommands.endSession(this@CallService)
                        callNotificationManager.cancelNotification()
                    }
                    else -> callNotificationManager.setupNotification()
                }
            }
        }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.onCallAdded(call)
        CallManager.inCallService = this
        call.registerCallback(callListener)

        val isIncoming = !call.isOutgoing()
        if (isIncoming) {
            val accountId = call.details?.accountHandle?.id
            val sortOrder = CallPolicyBindings.sortOrderFor(this, call)
            val peer = CallPolicyBindings.peerNumber(call)
            val decision =
                CallPolicyBindings.controller(this).onRinging(accountId, sortOrder, peer)
            com.nexus.phone.nexus.telecom.CallStore.noteRinging(
                com.nexus.phone.nexus.policy.CallSessionState.slot,
                com.nexus.phone.nexus.policy.CallSessionState.peerNumber,
                com.nexus.phone.nexus.policy.CallSessionState.policyWire,
            )

            when {
                decision.actions.contains(PolicyAction.Reject) -> {
                    Log.i(TAG, "policy REJECT")
                    call.reject(false, null)
                    return
                }
                decision.actions.contains(PolicyAction.AnswerAi) -> {
                    Log.i(TAG, "policy AI — prewarm then delayed answer")
                    callNotificationManager.setupNotification(true)
                    val delayMs = ConfigRepository(this).load().aiAnswerDelayMs
                    BypassCommands.warmAi(this)
                    CallPolicyBindings.scheduleAnswerAi(call, delayMs)
                    return
                }
                else -> {
                    // HUMAN / takeover off — Fossify default incoming UI path below
                }
            }
        }

        val isDeviceLocked = !powerManager.isInteractive || keyguardManager.isDeviceLocked
        val lowPriority =
            when {
                isIncoming && isDeviceLocked -> false
                !isIncoming && isDeviceLocked -> false
                isIncoming && !isDeviceLocked -> config.alwaysShowFullscreen
                else -> true
            }

        callNotificationManager.setupNotification(lowPriority)
        if (
            lowPriority ||
                !hasPermission(PERMISSION_POST_NOTIFICATIONS) ||
                !canUseFullScreenIntent()
        ) {
            try {
                startActivity(CallActivity.getStartIntent(this))
            } catch (_: Exception) {
                callNotificationManager.setupNotification()
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callListener)
        val wasPrimaryCall = call == CallManager.getPrimaryCall()
        CallManager.onCallRemoved(call)
        if (CallManager.getPhoneState() == NoCall) {
            CallManager.inCallService = null
            callNotificationManager.cancelNotification()
            CallPolicyBindings.cancelScheduledAnswer()
            CallPolicyBindings.controller(this).onDisconnected()
        } else {
            callNotificationManager.setupNotification()
            if (wasPrimaryCall) {
                startActivity(CallActivity.getStartIntent(this))
            }
        }

        EventBus.getDefault().post(Events.RefreshCallLog)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        if (audioState != null) {
            CallManager.onAudioStateChanged(audioState)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        callNotificationManager.cancelNotification()
    }

    companion object {
        private const val TAG = "NexusCallService"
    }
}
