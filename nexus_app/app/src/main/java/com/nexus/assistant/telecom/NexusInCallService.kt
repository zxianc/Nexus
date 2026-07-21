package com.nexus.assistant.telecom

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import com.nexus.assistant.config.ConfigRepository
import com.nexus.assistant.config.SimCatalog
import com.nexus.assistant.config.SimInfoReader
import com.nexus.assistant.config.SimPolicy
import com.nexus.assistant.service.BypassCommands

class NexusInCallService : InCallService() {
    private val repo by lazy { ConfigRepository(this) }
    private val simReader by lazy { SimInfoReader(this) }
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
    }

    override fun onBind(intent: Intent?): android.os.IBinder? {
        Log.i(TAG, "onBind action=${intent?.action}")
        return super.onBind(intent)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.i(TAG, "onCallAdded state=${call.state}")
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
        Log.i(TAG, "onCallRemoved")
        if (CallStore.current == call) {
            CallStore.current = null
        }
        CallStore.aiMode = false
        CallActivities.finishAll()
        BypassCommands.endSession(this)
    }

    private fun handleState(call: Call, state: Int) {
        try {
            if (!DialerTakeover.isEnabled(this)) {
                Log.i(TAG, "takeover off, ignore state=$state")
                return
            }
            when (state) {
                Call.STATE_RINGING -> {
                    val slot = slotFor(call)
                    val policy = repo.load().policyForSlot(slot)
                    val number =
                        call.details?.handle?.schemeSpecificPart
                            ?: call.details?.gatewayInfo?.originalAddress?.schemeSpecificPart
                            ?: ""
                    CallStore.noteRinging(slot, number, policy.toWire())
                    Log.i(
                        TAG,
                        "RINGING slot=$slot policy=${policy.toWire()} " +
                            "acct=${call.details?.accountHandle?.id}",
                    )
                    when (policy) {
                        SimPolicy.AI -> {
                            CallStore.aiMode = true
                            CallStore.wasAiMode = true
                            answerAi(call)
                        }
                        SimPolicy.REJECT -> {
                            Log.i(TAG, "reject()")
                            call.reject(false, null)
                        }
                        SimPolicy.HUMAN -> {
                            CallStore.aiMode = false
                            startActivity(IncomingCallActivity.intent(this, call))
                        }
                    }
                }
                Call.STATE_ACTIVE -> {
                    CallActivities.finishIncoming()
                    startActivity(InCallActivity.intent(this))
                    val ai =
                        policyFor(call) == SimPolicy.AI || CallStore.aiMode
                    if (ai) {
                        CallStore.aiMode = true
                        CallStore.wasAiMode = true
                        BypassCommands.startSession(this)
                    }
                }
                Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> {
                    BypassCommands.endSession(this)
                    CallStore.aiMode = false
                    CallActivities.finishAll()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleState state=$state", e)
        }
    }

    private fun answerAi(call: Call) {
        val tryAnswer =
            Runnable {
                try {
                    if (call.state == Call.STATE_RINGING) {
                        Log.i(TAG, "answer() state=${call.state}")
                        call.answer(VideoProfile.STATE_AUDIO_ONLY)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "answer() failed", e)
                }
            }
        // Immediate + one retry — some builds drop the first answer during bind.
        mainHandler.post(tryAnswer)
        mainHandler.postDelayed(tryAnswer, 400)
    }

    private fun policyFor(call: Call): SimPolicy = repo.load().policyForSlot(slotFor(call))

    private fun slotFor(call: Call): Int {
        val handle = call.details?.accountHandle
        var sortOrder: Int? = null
        if (handle != null) {
            try {
                val tm = getSystemService(TelecomManager::class.java)
                val account = tm?.getPhoneAccount(handle)
                val extras = account?.extras
                if (extras != null && extras.containsKey(EXTRA_SORT_ORDER)) {
                    sortOrder = extras.getInt(EXTRA_SORT_ORDER)
                }
            } catch (e: Exception) {
                Log.w(TAG, "resolve PhoneAccount sortOrder", e)
            }
        }
        val slot =
            SimCatalog.slotFromPhoneAccount(
                accountId = handle?.id,
                sortOrder = sortOrder,
                subIdToSlot = simReader.subIdToSlot(),
            )
        Log.i(TAG, "slotFor id=${handle?.id} sortOrder=$sortOrder -> $slot")
        return slot
    }

    companion object {
        private const val TAG = "NexusInCall"
        private const val EXTRA_SORT_ORDER = "android.telecom.extra.SORT_ORDER"
    }
}

object CallStore {
    @Volatile
    var current: Call? = null

    @Volatile
    var aiMode: Boolean = false

    @Volatile
    var wasAiMode: Boolean = false

    @Volatile
    var slot: Int = 0

    @Volatile
    var peerNumber: String = ""

    @Volatile
    var policy: String = ""

    @Volatile
    var startedAtMs: Long = 0L

    @Volatile
    var archivePending: Boolean = false

    fun noteRinging(slot: Int, number: String, policy: String) {
        this.slot = slot
        this.peerNumber = number
        this.policy = policy
        if (startedAtMs <= 0L) {
            startedAtMs = System.currentTimeMillis()
        }
        archivePending = true
    }

    fun clearCallMeta() {
        wasAiMode = false
        slot = 0
        peerNumber = ""
        policy = ""
        startedAtMs = 0L
        archivePending = false
    }
}
