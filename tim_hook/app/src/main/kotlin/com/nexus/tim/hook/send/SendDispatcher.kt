package com.nexus.tim.hook.send

import android.content.Context
import android.util.Log
import com.nexus.tim.hook.MainHook
import com.nexus.tim.hook.runtime.TimRuntime
import com.nexus.tim.protocol.TimMsgFields
import org.json.JSONObject

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
                val app = TimRuntime.qqAppInterface(classLoader)
                    ?: return@onMain fail(requestId, "no_app_runtime")
                val session = buildSession(peer, uinType)
                    ?: return@onMain fail(requestId, "session_failed")
                invokeSend(app, ctx, session, text)
                JSONObject()
                    .put(TimMsgFields.REQUEST_ID, requestId)
                    .put(TimMsgFields.OK, true)
                    .put(TimMsgFields.MSG_ID, "tim-send")
            }
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "sendText failed", t)
            fail(requestId, "send_failed:${t.javaClass.simpleName}:${t.message}")
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

    private fun buildSession(peer: String, uinType: Int): Any? {
        return try {
            val cl = classLoader.loadClass("com.tencent.mobileqq.activity.aio.SessionInfo")
            val session = cl.getDeclaredConstructor().newInstance()
            // Prefer fields on SessionInfo; fall back to superclass aio.q
            setIntField(session, listOf("d", "a", "x", "y", "z", "B", "H", "I"), uinType)
            setStringField(session, listOf("e", "a", "A", "f", "g", "h", "i", "m", "S"), peer)
            session
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "buildSession failed: ${t.message}")
            null
        }
    }

    private fun invokeSend(app: Any, ctx: Context, session: Any, text: String) {
        val facade = classLoader.loadClass("com.tencent.mobileqq.activity.ChatActivityFacade")
        val errors = ArrayList<String>()
        // H0(QQAppInterface, Context, SessionInfo, String)
        for (name in listOf("H0", "I0", "c0", "G", "U", "m", "q", "S0", "T0")) {
            for (m in facade.declaredMethods.filter { it.name == name }) {
                try {
                    m.isAccessible = true
                    val params = m.parameterTypes
                    val args = when {
                        params.size == 4 &&
                            params[1].isAssignableFrom(Context::class.java) &&
                            params[3] == String::class.java ->
                            arrayOf(app, ctx, session, text)
                        params.size == 5 &&
                            params[1].isAssignableFrom(Context::class.java) &&
                            params[3] == String::class.java &&
                            params[4].name.contains("ArrayList") ->
                            arrayOf(app, ctx, session, text, ArrayList<Any>())
                        params.size == 3 && params[2] == String::class.java ->
                            arrayOf(app, session, text)
                        else -> continue
                    }
                    m.invoke(null, *args)
                    Log.i(MainHook.TAG, "send via ChatActivityFacade.$name")
                    return
                } catch (t: Throwable) {
                    errors += "$name:${t.message}"
                }
            }
        }
        error(errors.joinToString(" | ").ifEmpty { "no_send_method" })
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
                        Log.i(MainHook.TAG, "session int ${cur.simpleName}.$name=$value")
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
                        Log.i(MainHook.TAG, "session str ${cur.simpleName}.$name=$value")
                        return
                    }
                } catch (_: Throwable) {
                }
                cur = cur.superclass
            }
        }
        error("string_field_missing")
    }

    private fun fail(requestId: String, error: String) = JSONObject()
        .put(TimMsgFields.REQUEST_ID, requestId)
        .put(TimMsgFields.OK, false)
        .put(TimMsgFields.ERROR, error)

    companion object {
        const val UIN_TYPE_FRIEND = 0
        const val UIN_TYPE_TROOP = 1
    }
}
