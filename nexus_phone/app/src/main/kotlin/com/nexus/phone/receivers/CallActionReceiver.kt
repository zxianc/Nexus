package com.nexus.phone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nexus.phone.activities.CallActivity
import com.nexus.phone.helpers.ACCEPT_CALL
import com.nexus.phone.helpers.CallManager
import com.nexus.phone.helpers.DECLINE_CALL

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACCEPT_CALL -> {
                context.startActivity(CallActivity.getStartIntent(context))
                CallManager.accept()
            }

            DECLINE_CALL -> CallManager.reject()
        }
    }
}
