package com.nexus.assistant

import android.app.Application
import android.util.Log
import com.nexus.assistant.notify.SmsWatcher

class NexusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            SmsWatcher.ensureRegistered(this)
        } catch (e: Exception) {
            Log.w(TAG, "SmsWatcher register onCreate", e)
        }
    }

    companion object {
        private const val TAG = "NexusApp"
    }
}
