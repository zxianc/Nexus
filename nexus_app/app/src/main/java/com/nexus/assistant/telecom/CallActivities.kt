package com.nexus.assistant.telecom

import android.app.Activity
import java.lang.ref.WeakReference

/** Tracks call UI so InCallService can dismiss stale screens. */
object CallActivities {
    @Volatile
    private var incoming: WeakReference<Activity>? = null

    @Volatile
    private var inCall: WeakReference<Activity>? = null

    fun bindIncoming(activity: Activity) {
        incoming = WeakReference(activity)
    }

    fun unbindIncoming(activity: Activity) {
        if (incoming?.get() === activity) {
            incoming = null
        }
    }

    fun bindInCall(activity: Activity) {
        inCall = WeakReference(activity)
    }

    fun unbindInCall(activity: Activity) {
        if (inCall?.get() === activity) {
            inCall = null
        }
    }

    fun finishIncoming() {
        incoming?.get()?.finish()
        incoming = null
    }

    fun finishInCall() {
        inCall?.get()?.finish()
        inCall = null
    }

    fun finishAll() {
        finishIncoming()
        finishInCall()
    }
}
