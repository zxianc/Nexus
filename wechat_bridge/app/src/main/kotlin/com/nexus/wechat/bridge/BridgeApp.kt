package com.nexus.wechat.bridge

import android.app.Application
import com.nexus.wechat.bridge.queue.SendQueue
import com.nexus.wechat.bridge.state.BridgeState
import com.nexus.wechat.bridge.store.EventStore
import com.nexus.wechat.bridge.uds.HookSession
import com.nexus.wechat.bridge.uds.SendResult
import org.json.JSONObject

class BridgeApp : Application() {
    lateinit var eventStore: EventStore
        private set
    lateinit var bridgeState: BridgeState
        private set
    lateinit var hookSession: HookSession
        private set
    lateinit var sendQueue: SendQueue
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        eventStore = EventStore()
        bridgeState = BridgeState(supportedVersion = BridgeState.DEFAULT_SUPPORTED_VERSION)
        hookSession = HookSession(bridgeState, eventStore)
        sendQueue = SendQueue(hookSession)
    }

    fun sendTextHttp(chatId: String, text: String, ats: List<String>): Pair<Int, JSONObject> {
        val result: SendResult = try {
            sendQueue.enqueueText(chatId, text, ats).get()
        } catch (e: Exception) {
            SendResult(ok = false, error = e.message ?: "send_failed")
        }
        return when {
            result.ok -> 200 to JSONObject().put("ok", true).put("msg_id", result.msgId)
            result.error == "hook_unavailable" || result.error == "not_logged_in" ->
                503 to JSONObject().put("ok", false).put("error", result.error)
            result.error == "timeout" ->
                504 to JSONObject().put("ok", false).put("error", result.error)
            result.error == "version_mismatch" ->
                503 to JSONObject().put("ok", false).put("error", result.error)
            else -> 400 to JSONObject().put("ok", false).put("error", result.error ?: "send_failed")
        }
    }

    companion object {
        lateinit var instance: BridgeApp
            private set
    }
}
