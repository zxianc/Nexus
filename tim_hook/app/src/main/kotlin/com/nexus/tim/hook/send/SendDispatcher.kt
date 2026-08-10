package com.nexus.tim.hook.send

import android.content.Context
import android.util.Log
import com.nexus.tim.hook.MainHook
import com.nexus.tim.hook.runtime.TimRuntime
import com.nexus.tim.protocol.TimMsgFields
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.HashMap

/**
 * TIM 4.1.0 uses QQNT. ChatActivityFacade.H0 → J0 is a stub (returns empty [J]),
 * so classic Facade/MessageRecord paths never deliver.
 *
 * Real path:
 *   QRoute.api(IMsgUtilApi).createTextElement(text)
 *   QRoute.api(IMsgService).addSendMsg / sendMsg(Contact, elements, …)
 * Contact: chatType 1=C2C, 2=Group; C2C peerUid must be NT uid (via IRelationNTUinAndUidApi).
 */
class SendDispatcher(
    private val classLoader: ClassLoader,
    private val appContext: Context?,
) {
    fun sendText(req: JSONObject): JSONObject {
        val requestId = req.optString(TimMsgFields.REQUEST_ID, "")
        val chatId = req.optString(TimMsgFields.CHAT_ID, "")
        val text = req.optString(TimMsgFields.TEXT, "")
        if (chatId.isEmpty() || text.isEmpty()) {
            return fail(requestId, "missing_fields")
        }
        val (uinType, peer) = parseChatId(chatId)
        if (peer.isEmpty()) {
            return fail(requestId, "bad_chat_id")
        }
        val ctx = appContext ?: return fail(requestId, "no_context")
        return try {
            TimRuntime.onMain {
                val errors = ArrayList<String>()
                try {
                    sendViaNt(peer, uinType, text)
                    return@onMain ok(requestId, "nt")
                } catch (t: Throwable) {
                    errors += "nt:${root(t)}"
                    Log.w(MainHook.TAG, "NT send failed", t)
                }
                val selfUin = LoginUin.self(classLoader, ctx)
                val app = TimRuntime.qqAppInterface(classLoader)
                if (app != null && selfUin.isNotEmpty()) {
                    try {
                        sendViaChatActivityFacade(app, ctx, selfUin, peer, uinType, text)
                        return@onMain ok(requestId, "H0-stub")
                    } catch (t: Throwable) {
                        errors += "H0:${root(t)}"
                    }
                }
                fail(requestId, errors.joinToString(" | "))
            }
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "sendText failed", t)
            fail(requestId, "send_failed:${root(t)}")
        }
    }

    private fun sendViaNt(peer: String, uinType: Int, text: String) {
        val chatType = if (uinType == UIN_TYPE_TROOP) CHAT_TYPE_GROUP else CHAT_TYPE_C2C
        val peerUid = resolvePeerUid(peer, chatType)
        Log.i(MainHook.TAG, "NT send chatType=$chatType peer=$peer peerUid=$peerUid")

        val contactCl = classLoader.loadClass("com.tencent.qqnt.kernelpublic.nativeinterface.Contact")
        val contact = contactCl
            .getConstructor(Int::class.javaPrimitiveType, String::class.java, String::class.java)
            .newInstance(chatType, peerUid, "")

        val util = qrouteApi("com.tencent.qqnt.msg.api.IMsgUtilApi")
            ?: error("no_IMsgUtilApi")
        val createText = util.javaClass.methods.first {
            it.name == "createTextElement" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java
        }
        val element = createText.invoke(util, text)
            ?: error("createTextElement_null")

        val elements = ArrayList<Any>()
        elements.add(element)

        val msgService = qrouteApi("com.tencent.qqnt.msg.api.IMsgService")
            ?: error("no_IMsgService")

        // addSendMsg only stages locally (UI spinner); real outbox is sendMsg → kernel.
        val callback = operateCallback()
        val errors = ArrayList<String>()
        for (m in msgService.javaClass.methods.filter { it.name == "sendMsg" }) {
            val p = m.parameterTypes
            try {
                when {
                    p.size == 3 &&
                        p[0].name.contains("Contact") &&
                        MutableList::class.java.isAssignableFrom(p[1]) &&
                        p[2].name.contains("IOperateCallback") -> {
                        m.invoke(msgService, contact, elements, callback)
                        Log.i(MainHook.TAG, "send via IMsgService.sendMsg(contact,list,cb)")
                        return
                    }
                    p.size == 4 &&
                        p[0].name.contains("Contact") &&
                        MutableList::class.java.isAssignableFrom(p[1]) &&
                        Map::class.java.isAssignableFrom(p[2]) -> {
                        m.invoke(msgService, contact, elements, HashMap<Any, Any>(), callback)
                        Log.i(MainHook.TAG, "send via IMsgService.sendMsg(contact,list,map,cb)")
                        return
                    }
                    p.size == 4 &&
                        p[0].name.contains("Contact") &&
                        p[1] == Long::class.javaPrimitiveType &&
                        MutableList::class.java.isAssignableFrom(p[2]) -> {
                        m.invoke(msgService, contact, 0L, elements, callback)
                        Log.i(MainHook.TAG, "send via IMsgService.sendMsg(contact,0,list,cb)")
                        return
                    }
                }
            } catch (t: Throwable) {
                errors += "${m.name}/${p.size}:${root(t)}"
            }
        }
        error(errors.joinToString(" | ").ifEmpty { "no_nt_send_method" })
    }

    private fun resolvePeerUid(peer: String, chatType: Int): String {
        if (chatType == CHAT_TYPE_GROUP) return peer
        if (peer.startsWith("u_")) return peer
        val api = qrouteApi("com.tencent.relation.common.api.IRelationNTUinAndUidApi")
            ?: return peer
        for (name in listOf("getFriendUidFromUin", "getUidFromUin")) {
            try {
                val m = api.javaClass.methods.firstOrNull {
                    it.name == name &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.returnType == String::class.java
                } ?: continue
                val uid = (m.invoke(api, peer) as? String)?.trim().orEmpty()
                if (uid.isNotEmpty()) {
                    Log.i(MainHook.TAG, "uin2uid via $name: $peer -> $uid")
                    return uid
                }
            } catch (t: Throwable) {
                Log.w(MainHook.TAG, "uin2uid $name failed: ${t.message}")
            }
        }
        Log.w(MainHook.TAG, "uin2uid miss, fallback peer=$peer")
        return peer
    }

    private fun qrouteApi(apiClassName: String): Any? {
        return try {
            val qroute = classLoader.loadClass("com.tencent.mobileqq.qroute.QRoute")
            val apiCl = classLoader.loadClass(apiClassName)
            val m = qroute.methods.first {
                it.name == "api" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Class::class.java
            }
            m.invoke(null, apiCl)
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "QRoute.api($apiClassName) failed: ${t.message}")
            null
        }
    }

    private fun operateCallback(): Any {
        val cbCl = classLoader.loadClass("com.tencent.qqnt.kernel.nativeinterface.IOperateCallback")
        return Proxy.newProxyInstance(classLoader, arrayOf(cbCl)) { _, method, args ->
            Log.i(MainHook.TAG, "IOperateCallback.${method.name} args=${args?.joinToString()}")
            null
        }
    }

    private fun sendViaChatActivityFacade(
        app: Any,
        ctx: Context,
        selfUin: String,
        peer: String,
        uinType: Int,
        text: String,
    ) {
        val session = buildSession(selfUin, peer, uinType) ?: error("session_failed")
        val facade = classLoader.loadClass("com.tencent.mobileqq.activity.ChatActivityFacade")
        for (name in listOf("H0", "I0")) {
            for (m in facade.declaredMethods.filter { it.name == name }) {
                try {
                    m.isAccessible = true
                    val params = m.parameterTypes
                    val args: Array<Any?> = when {
                        params.size == 4 &&
                            params[1].isAssignableFrom(Context::class.java) &&
                            params[3] == String::class.java ->
                            arrayOf(app, ctx, session, text)
                        params.size == 5 &&
                            params[1].isAssignableFrom(Context::class.java) &&
                            params[3] == String::class.java ->
                            arrayOf(app, ctx, session, text, ArrayList<Any>())
                        else -> continue
                    }
                    m.invoke(null, *args)
                    Log.i(MainHook.TAG, "send via ChatActivityFacade.$name (likely stub)")
                    return
                } catch (_: Throwable) {
                }
            }
        }
        error("no_caf_method")
    }

    private fun buildSession(selfUin: String, peer: String, uinType: Int): Any? {
        return try {
            val cl = classLoader.loadClass("com.tencent.mobileqq.activity.aio.SessionInfo")
            val session = cl.getDeclaredConstructor().newInstance()
            setIntField(session, listOf("d"), uinType)
            setStringField(session, listOf("e"), peer)
            setStringFieldOptional(session, listOf("A"), selfUin)
            session
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "buildSession failed: ${t.message}")
            null
        }
    }

    private fun parseChatId(chatId: String): Pair<Int, String> {
        val raw = chatId.trim()
        return when {
            raw.startsWith("troop:", ignoreCase = true) ->
                UIN_TYPE_TROOP to raw.substringAfter(':').trim()
            raw.startsWith("g:", ignoreCase = true) ->
                UIN_TYPE_TROOP to raw.substringAfter(':').trim()
            raw.endsWith("@chatroom", ignoreCase = true) ->
                UIN_TYPE_TROOP to raw.removeSuffix("@chatroom").trim()
            else -> UIN_TYPE_FRIEND to raw
        }
    }

    private fun setIntField(obj: Any, names: List<String>, value: Int) {
        for (name in names) {
            var cur: Class<*>? = obj.javaClass
            while (cur != null && cur != Any::class.java) {
                try {
                    val f = cur.getDeclaredField(name)
                    if (f.type == Int::class.javaPrimitiveType || f.type == Integer::class.java) {
                        f.isAccessible = true
                        f.setInt(obj, value)
                        return
                    }
                } catch (_: Throwable) {
                }
                cur = cur.superclass
            }
        }
        error("int_field_missing")
    }

    private fun setStringField(obj: Any, names: List<String>, value: String) {
        for (name in names) {
            var cur: Class<*>? = obj.javaClass
            while (cur != null && cur != Any::class.java) {
                try {
                    val f = cur.getDeclaredField(name)
                    if (f.type == String::class.java) {
                        f.isAccessible = true
                        f.set(obj, value)
                        return
                    }
                } catch (_: Throwable) {
                }
                cur = cur.superclass
            }
        }
        error("string_field_missing")
    }

    private fun setStringFieldOptional(obj: Any, names: List<String>, value: String) {
        try {
            setStringField(obj, names, value)
        } catch (_: Throwable) {
        }
    }

    private fun ok(requestId: String, via: String) = JSONObject()
        .put(TimMsgFields.REQUEST_ID, requestId)
        .put(TimMsgFields.OK, true)
        .put(TimMsgFields.MSG_ID, "tim-$via")

    private fun fail(requestId: String, error: String) = JSONObject()
        .put(TimMsgFields.REQUEST_ID, requestId)
        .put(TimMsgFields.OK, false)
        .put(TimMsgFields.ERROR, error)

    private fun root(t: Throwable): String =
        (generateSequence(t) { it.cause }.lastOrNull() ?: t).let {
            "${it.javaClass.simpleName}:${it.message}"
        }

    companion object {
        const val UIN_TYPE_FRIEND = 0
        const val UIN_TYPE_TROOP = 1
        const val CHAT_TYPE_C2C = 1
        const val CHAT_TYPE_GROUP = 2
    }
}

/** Resolve self uin without depending on Bridge HELLO. */
object LoginUin {
    fun self(cl: ClassLoader, ctx: Context): String {
        try {
            val snap = com.nexus.tim.hook.state.LoginProbe().probe(ctx)
            if (snap.userId.isNotBlank()) return snap.userId
        } catch (_: Throwable) {
        }
        return try {
            val app = TimRuntime.qqAppInterface(cl) ?: return ""
            for (m in app.javaClass.methods) {
                if (m.parameterTypes.isEmpty() && m.returnType == String::class.java &&
                    (m.name.equals("getCurrentAccountUin", true) ||
                        m.name.equals("getLongAccountUin", true) ||
                        m.name == "getAccount" || m.name == "getUin")
                ) {
                    m.isAccessible = true
                    val v = m.invoke(app)?.toString()?.trim().orEmpty()
                    if (v.matches(Regex("\\d{5,12}"))) return v
                }
            }
            ""
        } catch (_: Throwable) {
            ""
        }
    }
}
