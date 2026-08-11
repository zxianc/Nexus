package com.nexus.tim.bridge

import android.app.Application
import com.nexus.tim.bridge.config.BridgeConfig
import com.nexus.tim.bridge.config.BridgeConfigStore
import com.nexus.tim.bridge.push.OutboundHub
import com.nexus.tim.bridge.queue.SendQueue
import com.nexus.tim.bridge.state.BridgeState
import com.nexus.tim.bridge.store.EventStore
import com.nexus.tim.bridge.store.MediaStore
import com.nexus.tim.bridge.uds.HookSession
import com.nexus.tim.bridge.uds.MembersResult
import com.nexus.tim.bridge.uds.SendResult
import com.nexus.tim.protocol.TimMsgFields
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class TimApp : Application() {
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

    override fun onCreate() {
        super.onCreate()
        instance = this
        configStore = BridgeConfigStore(this)
        configRef.set(configStore.load())
        outbound = OutboundHub { configRef.get() }
        eventStore = EventStore()
        bridgeState = BridgeState(supportedVersion = BridgeState.DEFAULT_SUPPORTED_VERSION)
        mediaStore = MediaStore(File(cacheDir, "tim_media"))
        hookSession = HookSession(bridgeState, eventStore, outbound)
        sendQueue = SendQueue(hookSession)
    }

    fun currentConfig(): BridgeConfig = configRef.get()

    fun saveConfig(config: BridgeConfig): BridgeConfig {
        val n = config.normalized()
        configStore.save(n)
        configRef.set(n)
        return n
    }

    fun sendTextHttp(chatId: String, text: String, ats: List<String> = emptyList()): Pair<Int, JSONObject> {
        val result = try {
            sendQueue.enqueueText(chatId, text, ats).get()
        } catch (t: Throwable) {
            SendResult(ok = false, error = t.message ?: "send_failed")
        }
        return mapSendResult(result)
    }

    fun sendImageHttp(
        chatId: String,
        kind: String,
        path: String,
        name: String,
        mediaId: String,
        dataB64: String,
        original: Boolean = true,
    ): Pair<Int, JSONObject> {
        val result = try {
            sendQueue.enqueueMedia(chatId, kind, path, name, mediaId, dataB64, original).get()
        } catch (t: Throwable) {
            SendResult(ok = false, error = t.message ?: "send_failed")
        }
        return mapSendResult(result)
    }

    fun fetchMembersHttp(chatId: String): Pair<Int, JSONObject> {
        val result = try {
            hookSession.requestMembers(chatId)
        } catch (t: Throwable) {
            MembersResult(ok = false, error = t.message ?: "members_failed")
        }
        return mapMembersResult(chatId, result)
    }

    private fun mapSendResult(result: SendResult): Pair<Int, JSONObject> {
        if (result.ok) {
            return 200 to JSONObject()
                .put("ok", true)
                .put("msg_id", result.msgId)
        }
        val err = result.error ?: "send_failed"
        val status = when (err) {
            "hook_disconnected" -> 503
            "version_mismatch" -> 409
            "timeout" -> 504
            else -> 400
        }
        return status to JSONObject().put("ok", false).put("error", err)
    }

    private fun mapMembersResult(chatId: String, result: MembersResult): Pair<Int, JSONObject> {
        if (result.ok) {
            val arr = JSONArray()
            for (m in result.members) {
                arr.put(
                    JSONObject()
                        .put(TimMsgFields.USER_ID, m.userId)
                        .put(TimMsgFields.DISPLAY, m.display),
                )
            }
            return 200 to JSONObject()
                .put("ok", true)
                .put(TimMsgFields.CHAT_ID, chatId)
                .put(TimMsgFields.MEMBERS, arr)
        }
        val err = result.error ?: "members_failed"
        val status = when (err) {
            "hook_disconnected" -> 503
            "timeout" -> 504
            else -> 400
        }
        return status to JSONObject().put("ok", false).put("error", err)
    }

    companion object {
        lateinit var instance: TimApp
            private set
    }
}
