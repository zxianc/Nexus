package com.nexus.wechat.hook.send

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nexus.wechat.hook.MainHook
import com.nexus.wechat.hook.state.ContactDirectory
import com.nexus.wechat.protocol.GroupAtSend
import com.nexus.wechat.protocol.ImageSendOptions
import com.nexus.wechat.protocol.WechatMsgFields
import android.util.Base64
import de.robv.android.xposed.XposedHelpers
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Send text/image for WeChat 8.0.76.
 *
 * Text: qh3.t1.a() → kl5.s5.yj / y11.r0 / SendMsgEvent
 * Image: qh3.t1.a() → kl5.s5.rj(ctx, talker, path, ...)
 * (Gallery uses qj only for optional caption text: qj(caption, talker).)
 */
class SendDispatcher(
    private val classLoader: ClassLoader,
    private val contacts: ContactDirectory = ContactDirectory(),
) {
    fun sendText(req: JSONObject): JSONObject {
        val requestId = req.optString(WechatMsgFields.REQUEST_ID, "")
        val chatId = req.optString(WechatMsgFields.CHAT_ID, "")
        val text = req.optString(WechatMsgFields.TEXT, "")
        if (chatId.isEmpty() || text.isEmpty()) {
            return fail(requestId, "missing_fields")
        }
        contacts.validateSendTarget(chatId)?.let { return fail(requestId, it) }
        val ats = parseAts(req.optJSONArray(WechatMsgFields.ATS))
        val built = if (chatId.endsWith("@chatroom") && ats.isNotEmpty()) {
            GroupAtSend.build(text = text, ats = ats) { wxid ->
                contacts.displayOf(wxid)
            }
        } else {
            GroupAtSend.Built(content = text, atuserlist = "", hasAts = false)
        }
        if (built.hasAts) {
            Log.i(
                MainHook.TAG,
                "group at send chat=$chatId atuserlist=${built.atuserlist} " +
                    "mentions=${built.mentions.joinToString { "${it.display}->${it.wxid}" }}",
            )
        }
        return try {
            val msgId = runOnMain {
                sendOnMain(chatId, built.content, built.atuserlist, built.mentions)
            }
            JSONObject()
                .put(WechatMsgFields.REQUEST_ID, requestId)
                .put(WechatMsgFields.OK, true)
                .put(WechatMsgFields.MSG_ID, msgId)
        } catch (t: Throwable) {
            Log.e(MainHook.TAG, "sendText failed", t)
            fail(requestId, "send_failed:${rootCause(t)}")
        }
    }

    fun sendMedia(req: JSONObject): JSONObject {
        val requestId = req.optString(WechatMsgFields.REQUEST_ID, "")
        val chatId = req.optString(WechatMsgFields.CHAT_ID, "")
        val path = req.optString(WechatMsgFields.PATH, "")
        val name = req.optString(WechatMsgFields.NAME, "bin")
        val kind = req.optString(WechatMsgFields.KIND, "image")
        val mediaId = req.optString(WechatMsgFields.MEDIA_ID, "")
        val dataB64 = req.optString(WechatMsgFields.DATA_B64, "")
        val original = if (req.has(WechatMsgFields.ORIGINAL)) {
            req.optBoolean(WechatMsgFields.ORIGINAL, true)
        } else {
            ImageSendOptions.parseOriginal(req.optString(WechatMsgFields.ORIGINAL, ""))
        }
        if (chatId.isEmpty()) {
            return fail(requestId, "missing_fields")
        }
        contacts.validateSendTarget(chatId)?.let { return fail(requestId, it) }
        return try {
            val localPath = materializePath(path, dataB64, name)
                ?: return fail(requestId, "file_missing")
            val msgId = runOnMain {
                when (kind) {
                    "image" -> sendImageOnMain(chatId, localPath, original)
                    else -> sendFileOnMain(chatId, localPath, name)
                }
            }
            JSONObject()
                .put(WechatMsgFields.REQUEST_ID, requestId)
                .put(WechatMsgFields.OK, true)
                .put(WechatMsgFields.MSG_ID, msgId)
                .put(WechatMsgFields.MEDIA_ID, mediaId)
        } catch (t: Throwable) {
            Log.e(MainHook.TAG, "sendMedia failed kind=$kind", t)
            fail(requestId, "send_failed:${rootCause(t)}")
        }
    }

    private fun materializePath(path: String, dataB64: String, name: String): String? {
        if (path.isNotEmpty()) {
            val f = File(path)
            if (f.isFile && f.length() > 0L) return f.absolutePath
        }
        if (dataB64.isEmpty()) return null
        val ctx = currentContext() ?: return null
        val dir = File(ctx.cacheDir, "nexus_wechat_out").also { it.mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._\\-]"), "_").ifEmpty { "bin" }
        val dest = File(dir, "${UUID.randomUUID()}_$safe")
        dest.writeBytes(Base64.decode(dataB64, Base64.DEFAULT))
        return dest.absolutePath.takeIf { dest.isFile && dest.length() > 0 }
    }

    private fun sendOnMain(
        chatId: String,
        text: String,
        atuserlist: String = "",
        mentions: List<GroupAtSend.Mention> = emptyList(),
    ): String {
        val msgType = resolveMsgType(chatId)
        val errors = mutableListOf<String>()

        if (atuserlist.isNotEmpty()) {
            try {
                sendViaAtCgiFactory(chatId, text, msgType, atuserlist)
                Log.i(
                    MainHook.TAG,
                    "sent via y11.s1 at-map chat=$chatId type=$msgType atuserlist=$atuserlist",
                )
                return "at-${UUID.randomUUID()}"
            } catch (t: Throwable) {
                errors += "atcgi:${rootCause(t)}"
                Log.w(MainHook.TAG, "at cgi factory failed: ${rootCause(t)}")
            }
            try {
                sendViaSendMsgMgrWithAtHelper(chatId, text, msgType, atuserlist, mentions)
                Log.i(
                    MainHook.TAG,
                    "sent via kl5.s5.zj+i1 chat=$chatId type=$msgType atuserlist=$atuserlist",
                )
                return "mgr-${UUID.randomUUID()}"
            } catch (t: Throwable) {
                errors += "mgr:${rootCause(t)}"
                Log.w(MainHook.TAG, "SendMsgMgr at path failed: ${rootCause(t)}")
                throw IllegalStateException(errors.joinToString(" | "))
            }
        }

        try {
            sendViaSendMsgMgr(chatId, text, msgType)
            Log.i(MainHook.TAG, "sent via kl5.s5.yj chat=$chatId type=$msgType")
            return "mgr-${UUID.randomUUID()}"
        } catch (t: Throwable) {
            errors += "mgr:${rootCause(t)}"
            Log.w(MainHook.TAG, "SendMsgMgr path failed: ${rootCause(t)}")
        }

        try {
            val id = sendViaNetScene(chatId, text, msgType)
            Log.i(MainHook.TAG, "sent via y11.r0 NetScene chat=$chatId msgId=$id")
            return if (id > 0) id.toString() else "net-${UUID.randomUUID()}"
        } catch (t: Throwable) {
            errors += "net:${rootCause(t)}"
            Log.w(MainHook.TAG, "NetScene path failed: ${rootCause(t)}")
        }

        try {
            val ok = sendViaSendMsgEvent(chatId, text, msgType)
            if (!ok) throw IllegalStateException("SendMsgEvent had no listener")
            Log.i(MainHook.TAG, "sent via SendMsgEvent chat=$chatId")
            return "evt-${UUID.randomUUID()}"
        } catch (t: Throwable) {
            errors += "evt:${rootCause(t)}"
            Log.w(MainHook.TAG, "SendMsgEvent path failed: ${rootCause(t)}")
        }

        throw IllegalStateException(errors.joinToString(" | "))
    }

    /**
     * ChatUI non-PPC path: y11.s1 → r1.h = {atuserlist: CDATA csv} → execute.
     * Also sets r1.m/n for PPC devices.
     */
    private fun sendViaAtCgiFactory(
        chatId: String,
        text: String,
        msgType: Int,
        atuserlist: String,
    ) {
        val s1 = XposedHelpers.findClass("y11.s1", classLoader)
        val r1 = XposedHelpers.callStaticMethod(s1, "a", chatId)
            ?: throw IllegalStateException("y11.s1.a returned null")
        XposedHelpers.setIntField(r1, "e", msgType)
        XposedHelpers.setIntField(r1, "f", 1)
        XposedHelpers.callMethod(r1, "e", text)
        XposedHelpers.callMethod(r1, "g", chatId)
        val map = HashMap<String, String>(1)
        map["atuserlist"] = GroupAtSend.atuserlistCdata(atuserlist)
        XposedHelpers.setObjectField(r1, "h", map)
        XposedHelpers.setObjectField(r1, "o", "")
        XposedHelpers.setIntField(r1, "i", 5)
        XposedHelpers.setBooleanField(r1, "m", true)
        XposedHelpers.setObjectField(r1, "n", atuserlist)
        val n1 = XposedHelpers.callMethod(r1, "a")
            ?: throw IllegalStateException("y11.r1.a returned null")
        val ok = XposedHelpers.callMethod(n1, "a")
        if (ok is Boolean && !ok) {
            throw IllegalStateException("y11.n1.a returned false")
        }
    }

    /** Populate AtSomeOneHelper map then zj (covers Q4 group parse of @display). */
    private fun sendViaSendMsgMgrWithAtHelper(
        chatId: String,
        text: String,
        msgType: Int,
        atuserlist: String,
        mentions: List<GroupAtSend.Mention>,
    ) {
        prepareAtSomebodyMap(mentions)
        try {
            val service = sendMsgService()
            XposedHelpers.callMethod(
                service,
                "zj",
                chatId,
                text,
                msgType,
                1,
                0L,
                "",
                atuserlist,
                "",
            )
        } finally {
            clearAtSomebodyMap()
        }
    }

    private fun sendViaSendMsgMgr(chatId: String, text: String, msgType: Int) {
        val service = sendMsgService()
        XposedHelpers.callMethod(service, "yj", chatId, text, msgType, 0)
    }

    private fun prepareAtSomebodyMap(mentions: List<GroupAtSend.Mention>) {
        if (mentions.isEmpty()) return
        val i1 = XposedHelpers.findClass("com.tencent.mm.ui.i1", classLoader)
        val raw = XposedHelpers.getStaticObjectField(i1, "d")
        val map: MutableMap<Any?, Any?> = when (raw) {
            is MutableMap<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                raw as MutableMap<Any?, Any?>
            }
            else -> {
                val linked = LinkedHashMap<Any?, Any?>()
                XposedHelpers.setStaticObjectField(i1, "d", linked)
                linked
            }
        }
        map.clear()
        for (m in mentions) {
            map[m.display] = m.wxid
        }
        Log.i(MainHook.TAG, "AtSomeOneHelper map size=${map.size} keys=${map.keys}")
    }

    private fun clearAtSomebodyMap() {
        try {
            val i1 = XposedHelpers.findClass("com.tencent.mm.ui.i1", classLoader)
            val raw = XposedHelpers.getStaticObjectField(i1, "d")
            if (raw is MutableMap<*, *>) raw.clear()
        } catch (_: Throwable) {
        }
    }

    private fun parseAts(arr: JSONArray?): List<String> {
        if (arr == null || arr.length() == 0) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "").trim()
            if (v.isNotEmpty()) out.add(v)
        }
        return out
    }

    private fun sendImageOnMain(chatId: String, path: String, original: Boolean): String {
        val service = sendMsgService()
        val ctx = currentContext()
            ?: throw IllegalStateException("no_application_context")
        val callback = XposedHelpers.newInstance(
            XposedHelpers.findClass("e01.h7", classLoader),
        )
        // 8.0.76 gallery: compressType 0=compressed, 1=original (WITHOUT_COMPRESS / 原图)
        val compressType = ImageSendOptions.compressType(original)
        XposedHelpers.callMethod(
            service,
            "rj",
            ctx,
            chatId,
            path,
            compressType,
            "",
            "",
            callback,
        )
        Log.i(
            MainHook.TAG,
            "sent image via kl5.s5.rj chat=$chatId original=$original compressType=$compressType path=$path",
        )
        return "img-${UUID.randomUUID()}"
    }

    private fun sendFileOnMain(chatId: String, path: String, name: String): String {
        // Best-effort: reuse image pipeline for common binary attachments is unsafe.
        // Prefer AppMsg-style later; for MVP try qj after copying as "file" fails → error.
        throw IllegalStateException("file_send_not_implemented_use_image")
    }

    private fun sendMsgService(): Any {
        val accessor = XposedHelpers.findClass("qh3.t1", classLoader)
        return XposedHelpers.callStaticMethod(accessor, "a")
            ?: throw IllegalStateException("qh3.t1.a returned null")
    }

    private fun currentContext(): Context? {
        return try {
            val at = Class.forName("android.app.ActivityThread")
            XposedHelpers.callStaticMethod(at, "currentApplication") as? Application
        } catch (_: Throwable) {
            null
        }
    }

    private fun sendViaNetScene(chatId: String, text: String, msgType: Int): Long {
        val netSceneClass = XposedHelpers.findClass("y11.r0", classLoader)
        val scene = XposedHelpers.newInstance(
            netSceneClass,
            chatId,
            text,
            msgType,
            4,
            0L,
        )
        val msgId = readLongField(scene, "f", "f459357f")
        if (msgId == -1L) {
            throw IllegalStateException("y11.r0 inserted local msg failed")
        }

        val runCgi = XposedHelpers.findClass("com.tencent.mm.modelbase.z2", classLoader)
        val ready = XposedHelpers.callStaticMethod(runCgi, "c")
        if (ready is Boolean && !ready) {
            throw IllegalStateException("NetSceneQueue is not ready")
        }
        val sceneBase = XposedHelpers.findClass("com.tencent.mm.modelbase.m1", classLoader)
        XposedHelpers.callStaticMethod(runCgi, "b", sceneBase.cast(scene))
        return msgId
    }

    private fun sendViaSendMsgEvent(chatId: String, text: String, msgType: Int): Boolean {
        val eventClass = XposedHelpers.findClass(
            "com.tencent.mm.autogen.events.SendMsgEvent",
            classLoader,
        )
        val event = eventClass.getDeclaredConstructor().newInstance()
        val payload = resolvePayload(event)
            ?: throw IllegalStateException("SendMsgEvent payload field not found")

        setStringFields(payload, chatId, text)
        setIntFields(payload, msgType = msgType, flag = 0)

        val publish = eventClass.methods.firstOrNull {
            it.parameterTypes.isEmpty() &&
                (it.returnType == Boolean::class.javaPrimitiveType ||
                    it.returnType == Boolean::class.javaObjectType) &&
                it.name.length <= 2
        } ?: throw IllegalStateException("SendMsgEvent publish method not found")
        publish.isAccessible = true
        return publish.invoke(event) == true
    }

    private fun resolveMsgType(chatId: String): Int {
        return try {
            val clazz = XposedHelpers.findClass("e01.e2", classLoader)
            val v = XposedHelpers.callStaticMethod(clazz, "C", chatId)
            when (v) {
                is Int -> v
                is Number -> v.toInt()
                else -> 1
            }
        } catch (_: Throwable) {
            1
        }
    }

    private fun resolvePayload(event: Any): Any? {
        for (name in listOf("g", "f71992g")) {
            try {
                val f = event.javaClass.getDeclaredField(name)
                f.isAccessible = true
                val v = f.get(event)
                if (v != null) return v
            } catch (_: Throwable) {
            }
        }
        for (f in event.javaClass.declaredFields) {
            if (f.type.isPrimitive || f.type == String::class.java) continue
            f.isAccessible = true
            val v = f.get(event) ?: continue
            if (v.javaClass.name.startsWith("com.tencent.mm")) return v
        }
        return null
    }

    private fun setStringFields(payload: Any, chatId: String, text: String) {
        val stringFields = payload.javaClass.declaredFields
            .filter { it.type == String::class.java }
            .sortedBy { it.name }
        if (stringFields.size >= 2) {
            stringFields[0].isAccessible = true
            stringFields[0].set(payload, chatId)
            stringFields[1].isAccessible = true
            stringFields[1].set(payload, text)
            return
        }
        setField(payload, chatId, "a", "f7337a")
        setField(payload, text, "b", "f7338b")
    }

    private fun setIntFields(payload: Any, msgType: Int, flag: Int) {
        val intFields = payload.javaClass.declaredFields
            .filter {
                it.type == Int::class.javaPrimitiveType || it.type == Integer::class.java
            }
            .sortedBy { it.name }
        if (intFields.size >= 2) {
            intFields[0].isAccessible = true
            intFields[0].set(payload, msgType)
            intFields[1].isAccessible = true
            intFields[1].set(payload, flag)
            return
        }
        setField(payload, msgType, "c", "f7339c")
        setField(payload, flag, "d", "f7340d")
    }

    private fun setField(target: Any, value: Any, vararg names: String) {
        for (name in names) {
            try {
                val f = target.javaClass.getDeclaredField(name)
                f.isAccessible = true
                f.set(target, value)
                return
            } catch (_: Throwable) {
            }
        }
    }

    private fun readLongField(target: Any, vararg names: String): Long {
        for (name in names) {
            try {
                val f = target.javaClass.getDeclaredField(name)
                f.isAccessible = true
                val v = f.get(target)
                when (v) {
                    is Long -> return v
                    is Number -> return v.toLong()
                }
            } catch (_: Throwable) {
            }
        }
        for (f in target.javaClass.declaredFields) {
            if (f.type != Long::class.javaPrimitiveType && f.type != java.lang.Long::class.java) {
                continue
            }
            f.isAccessible = true
            val v = f.get(target)
            if (v is Number && v.toLong() > 0) return v.toLong()
        }
        return 0L
    }

    private fun <T> runOnMain(block: Callable<T>): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block.call()
        }
        val task = FutureTask(block)
        Handler(Looper.getMainLooper()).post(task)
        return task.get(15, TimeUnit.SECONDS)
    }

    private fun rootCause(t: Throwable): String {
        var cur = t
        while (cur.cause != null && cur.cause !== cur) cur = cur.cause!!
        return "${cur.javaClass.simpleName}:${cur.message}"
    }

    private fun fail(requestId: String, error: String) = JSONObject()
        .put(WechatMsgFields.REQUEST_ID, requestId)
        .put(WechatMsgFields.OK, false)
        .put(WechatMsgFields.ERROR, error)
}
