package com.nexus.phone.nexus.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlin.concurrent.thread

/**
 * Wakes the process on incoming SMS so forwarding works even when UI is not open.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        Log.i(TAG, "SMS_RECEIVED")
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "SmsReceiver") {
            try {
                // Brief delay so Inbox provider has inserted the row before we query.
                Thread.sleep(400)
                SmsWatcher.ensureRegistered(app)
                SmsWatcher.pollOnce(app)
            } catch (e: Exception) {
                Log.e(TAG, "handle", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
