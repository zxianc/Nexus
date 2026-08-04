package com.nexus.phone.nexus.policy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import com.nexus.phone.nexus.config.ConfigRepository
import com.nexus.phone.nexus.config.SimCatalog
import com.nexus.phone.nexus.config.SimInfoReader
import com.nexus.phone.nexus.telecom.DialerTakeover

object CallPolicyBindings {
    private const val TAG = "CallPolicyBindings"
    private const val EXTRA_SORT_ORDER = "android.telecom.extra.SORT_ORDER"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var pendingAnswer: Runnable? = null

    fun controller(context: Context): CallPolicyController {
        val app = context.applicationContext
        val repo = ConfigRepository(app)
        val simReader = SimInfoReader(app)
        return CallPolicyController(
            loadConfig = { repo.load() },
            takeoverEnabled = { DialerTakeover.isEnabled(app) },
            slotResolver = { accountId, sortOrder ->
                SimCatalog.slotFromPhoneAccount(
                    accountId = accountId,
                    sortOrder = sortOrder,
                    subIdToSlot = simReader.subIdToSlot(),
                )
            },
        )
    }

    fun sortOrderFor(context: Context, call: Call): Int? {
        val handle = call.details?.accountHandle ?: return null
        return try {
            val tm = context.getSystemService(TelecomManager::class.java)
            val account = tm?.getPhoneAccount(handle)
            val extras = account?.extras
            if (extras != null && extras.containsKey(EXTRA_SORT_ORDER)) {
                extras.getInt(EXTRA_SORT_ORDER)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "sortOrderFor", e)
            null
        }
    }

    fun peerNumber(call: Call): String =
        call.details?.handle?.schemeSpecificPart
            ?: call.details?.gatewayInfo?.originalAddress?.schemeSpecificPart
            ?: ""

    /**
     * Delay then answer while RINGING. Caller should [cancelScheduledAnswer] on disconnect.
     * [delayMs] is clamped by [ConfigRepository.clampAiAnswerDelayMs].
     */
    fun scheduleAnswerAi(call: Call, delayMs: Int) {
        cancelScheduledAnswer()
        val ms = ConfigRepository.clampAiAnswerDelayMs(delayMs)
        Log.i(TAG, "scheduleAnswerAi delayMs=$ms")
        val task =
            Runnable {
                pendingAnswer = null
                answerWithRetry(call)
            }
        pendingAnswer = task
        mainHandler.postDelayed(task, ms.toLong())
    }

    fun cancelScheduledAnswer() {
        val task = pendingAnswer
        if (task != null) {
            Log.i(TAG, "cancelScheduledAnswer")
            mainHandler.removeCallbacks(task)
            pendingAnswer = null
        }
    }

    fun answerWithRetry(call: Call) {
        val tryAnswer =
            Runnable {
                try {
                    if (call.state == Call.STATE_RINGING) {
                        call.answer(VideoProfile.STATE_AUDIO_ONLY)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "answer() failed", e)
                }
            }
        mainHandler.post(tryAnswer)
        mainHandler.postDelayed(tryAnswer, 400)
    }
}
