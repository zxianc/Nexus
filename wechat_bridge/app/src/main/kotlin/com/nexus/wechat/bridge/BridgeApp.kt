package com.nexus.wechat.bridge

import android.app.Application
import android.util.Log
import com.nexus.wechat.bridge.config.BridgeConfig
import com.nexus.wechat.bridge.config.BridgeConfigStore
import com.nexus.wechat.bridge.push.OutboundHub
import com.nexus.wechat.bridge.queue.SendQueue
import com.nexus.wechat.bridge.state.BridgeState
import com.nexus.wechat.bridge.store.EventStore
import com.nexus.wechat.bridge.store.MediaStore
import com.nexus.wechat.bridge.uds.HookSession
import com.nexus.wechat.bridge.uds.SendResult
import com.nexus.wechat.protocol.WechatMsgFields
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class BridgeApp : Application() {
    lateinit var eventStore: EventStore
        private set
    lateinit var bridgeState: BridgeState
        private set
    lateinit var mediaStore: MediaStore
        private set
    lateinit var hookSession: HookSession
        private set
    lateinit var sendQueue: SendQueue
        private set
    lateinit var configStore: BridgeConfigStore
        private set
    lateinit var outbound: OutboundHub
        private set

    private val configRef = AtomicReference(BridgeConfig())

    fun currentConfig(): BridgeConfig = configRef.get()

    fun reloadConfig(): BridgeConfig {
        val c = configStore.load()
        configRef.set(c)
        return c
    }

    fun saveConfig(config: BridgeConfig): BridgeConfig {
        val c = config.normalized()
        configStore.save(c)
        configRef.set(c)
        return c
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        configStore = BridgeConfigStore(this)
        configRef.set(configStore.load())
        eventStore = EventStore()
        bridgeState = BridgeState(supportedVersion = BridgeState.DEFAULT_SUPPORTED_VERSION)
        mediaStore = MediaStore(File(cacheDir, "wechat_media"))
        outbound = OutboundHub { configRef.get() }
        hookSession = HookSession(bridgeState, eventStore, outbound).also { session ->
            session.onMediaReady = { mediaId, path, kind, name ->
                try {
                    val registered = mediaStore.registerIncoming(path, kind, name, preferredId = mediaId)
                    Log.i(TAG, "MEDIA_READY id=$registered path=$path")
                } catch (e: Exception) {
                    Log.w(TAG, "MEDIA_READY register failed", e)
                    outbound.alert(
                        "media_register_failed",
                        e.message ?: "media register failed",
                        JSONObject().put("media_id", mediaId),
                    )
                }
            }
        }
        sendQueue = SendQueue(hookSession)
    }

    fun sendTextHttp(chatId: String, text: String, ats: List<String>): Pair<Int, JSONObject> {
        return mapSendResult(
            try {
                sendQueue.enqueueText(chatId, text, ats).get()
            } catch (e: Exception) {
                SendResult(ok = false, error = e.message ?: "send_failed")
            },
        )
    }

    fun sendMediaHttp(
        chatId: String,
        kind: String,
        path: String,
        name: String,
        mediaId: String,
        dataB64: String,
        original: Boolean = true,
    ): Pair<Int, JSONObject> {
        return mapSendResult(
            try {
                sendQueue.enqueueMedia(chatId, kind, path, name, mediaId, dataB64, original).get()
            } catch (e: Exception) {
                SendResult(ok = false, error = e.message ?: "send_failed")
            },
        )
    }

    private fun mapSendResult(result: SendResult): Pair<Int, JSONObject> {
        return when {
            result.ok -> 200 to JSONObject()
                .put("ok", true)
                .put(WechatMsgFields.MSG_ID, result.msgId)
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
        private const val TAG = "WeChatBridge"
        lateinit var instance: BridgeApp
            private set
    }
}
