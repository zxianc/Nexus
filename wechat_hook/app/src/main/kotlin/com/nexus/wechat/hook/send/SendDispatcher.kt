package com.nexus.wechat.hook.send

import android.util.Log
import com.nexus.wechat.hook.MainHook
import com.nexus.wechat.protocol.WechatMsgFields
import org.json.JSONObject

/**
 * Real send path is filled in HOOK_NOTES.md after jadx on pinned 8.0.76.
 * Until then, returns a clear not_implemented error (no fake success).
 */
class SendDispatcher(
    @Suppress("unused") private val classLoader: ClassLoader,
) {
    fun sendText(req: JSONObject): JSONObject {
        val requestId = req.optString(WechatMsgFields.REQUEST_ID, "")
        Log.w(MainHook.TAG, "SEND_TEXT not implemented yet chat=${req.optString(WechatMsgFields.CHAT_ID)}")
        return JSONObject()
            .put(WechatMsgFields.REQUEST_ID, requestId)
            .put(WechatMsgFields.OK, false)
            .put(WechatMsgFields.ERROR, "send_not_implemented")
    }
}
