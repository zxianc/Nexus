package com.nexus.assistant.service

import android.content.Context
import android.content.Intent

/**
 * Lightweight intents for InCallService — avoids loading sherpa/LLM classes at bind time.
 */
object BypassCommands {
    private const val CLS = "com.nexus.assistant.service.NexusBypassService"
    private const val ACTION_START = "com.nexus.assistant.action.START_SESSION"
    private const val ACTION_END = "com.nexus.assistant.action.END_SESSION"
    private const val ACTION_HUMAN = "com.nexus.assistant.action.HUMAN_MODE"

    fun startSession(context: Context) {
        val i =
            Intent()
                .setClassName(context, CLS)
                .setAction(ACTION_START)
        context.startForegroundService(i)
    }

    fun endSession(context: Context) {
        context.startService(
            Intent()
                .setClassName(context, CLS)
                .setAction(ACTION_END),
        )
    }

    fun setHumanMode(context: Context) {
        context.startService(
            Intent()
                .setClassName(context, CLS)
                .setAction(ACTION_HUMAN),
        )
    }
}
