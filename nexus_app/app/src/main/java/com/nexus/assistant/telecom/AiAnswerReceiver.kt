package com.nexus.assistant.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import com.nexus.assistant.config.ConfigRepository
import com.nexus.assistant.config.SimPolicy
import com.nexus.assistant.service.BypassCommands

/**
 * Fallback when Telecom keeps binding stock Dialer InCallService instead of Nexus.
 * Uses ANSWER_PHONE_CALLS + PHONE_STATE to auto-answer AI-policy SIMs.
 */
class AiAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state != TelephonyManager.EXTRA_STATE_RINGING) {
            if (state == TelephonyManager.EXTRA_STATE_IDLE) {
                answering = false
                if (DialerTakeover.isEnabled(context) && CallStore.archivePending) {
                    BypassCommands.endSession(context.applicationContext)
                }
            }
            return
        }
        if (answering) return
        // Takeover off → leave stock Dialer alone.
        if (!DialerTakeover.isEnabled(context)) {
            Log.i(TAG, "takeover off, ignore RINGING")
            return
        }

        val slot = resolveSlot(intent)
        val policy = ConfigRepository(context).load().policyForSlot(slot)
        Log.i(TAG, "PHONE_STATE RINGING slot=$slot policy=${policy.toWire()}")
        when (policy) {
            SimPolicy.AI -> {
                answering = true
                CallStore.aiMode = true
                CallStore.wasAiMode = true
                CallStore.noteRinging(slot, "", policy.toWire())
                answerAndStart(context)
            }
            SimPolicy.REJECT -> {
                try {
                    val tm = context.getSystemService(TelecomManager::class.java)
                    if (Build.VERSION.SDK_INT >= 28) {
                        tm?.endCall()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "endCall", e)
                }
            }
            SimPolicy.HUMAN -> Unit
        }
    }

    private fun answerAndStart(context: Context) {
        val app = context.applicationContext
        val tm = app.getSystemService(TelecomManager::class.java)
        try {
            @Suppress("DEPRECATION")
            tm?.acceptRingingCall()
            Log.i(TAG, "acceptRingingCall() ok")
        } catch (e: Exception) {
            Log.e(TAG, "acceptRingingCall failed", e)
            answering = false
            return
        }
        // Give Telecom a moment to go ACTIVE, then start PCM session.
        Handler(Looper.getMainLooper()).postDelayed(
            {
                BypassCommands.startSession(app)
            },
            800,
        )
    }

    private fun resolveSlot(intent: Intent): Int {
        val keys = arrayOf("slot", "slotId", "android.telephony.extra.SLOT_INDEX", "phone")
        for (k in keys) {
            if (intent.hasExtra(k)) {
                val v = intent.getIntExtra(k, -1)
                if (v in 0..1) return v
            }
        }
        // Default to SIM1 (CMCC AI) when OEM omits slot — dual-SIM reject card rarely rings alone.
        return 0
    }

    companion object {
        private const val TAG = "AiAnswer"

        @Volatile
        private var answering = false
    }
}
