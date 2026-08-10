package com.nexus.tim.hook.recv

import android.util.Log
import com.nexus.tim.hook.MainHook
import com.nexus.tim.protocol.TimFrameTypes
import com.nexus.tim.protocol.TimMsgFields
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hook QQNT inbound: MsgService$getListener$1.onRecvMsg(ArrayList&lt;MsgRecord&gt;).
 */
class RecvDispatcher(
    private val classLoader: ClassLoader,
    private val selfUinProvider: () -> String,
    private val emit: (type: Int, payload: ByteArray) -> Unit,
) {
    private val hookedClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val recent = ConcurrentHashMap<String, Long>()

    fun install() {
        note("RecvDispatcher.install start")
        hookNow("immediate")
        Thread({
            repeat(20) {
                try {
                    Thread.sleep(1_000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                hookNow("retry-$it")
            }
        }, "nexus-tim-recv-rehook").apply {
            isDaemon = true
            start()
        }
    }

    private fun hookNow(label: String) {
        val targets = listOf(
            "com.tencent.qqnt.kernel.api.impl.MsgService\$getListener\$1",
            "com.tencent.qqnt.msg.MsgService\$c",
        )
        for (name in targets) {
            try {
                val clazz = XposedHelpers.findClass(name, classLoader)
                if (!hookedClasses.add(clazz)) continue
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "onRecvMsg",
                    java.util.ArrayList::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            @Suppress("UNCHECKED_CAST")
                            val list = param.args[0] as? List<Any?> ?: return
                            for (rec in list) {
                                if (rec != null) handleRecord(rec)
                            }
                        }
                    },
                )
                installedHooks.set(true)
                note("RecvDispatcher[$label] hooked $name.onRecvMsg")
            } catch (t: Throwable) {
                note("RecvDispatcher[$label] $name failed: ${t.javaClass.simpleName}:${t.message}")
            }
        }
    }

    private fun note(msg: String) {
        Log.e(MainHook.TAG, msg)
        XposedBridge.log("NexusTimHook: $msg")
    }

    private fun handleRecord(rec: Any) {
        try {
            val chatType = intField(rec, "chatType")
            if (chatType != CHAT_TYPE_C2C && chatType != CHAT_TYPE_GROUP) return
            val text = extractText(rec) ?: return
            if (text.isEmpty()) return

            val msgId = longField(rec, "msgId").toString()
            val peerUid = stringField(rec, "peerUid")
            val peerUin = longField(rec, "peerUin")
            val senderUin = longField(rec, "senderUin")
            val senderUid = stringField(rec, "senderUid")
            val msgTime = longField(rec, "msgTime").let { if (it > 0) it else System.currentTimeMillis() / 1000 }
            val peerName = stringField(rec, "peerName")
            val sendNick = stringField(rec, "sendNickName")
                .ifEmpty { stringField(rec, "sendMemberName") }
                .ifEmpty { stringField(rec, "sendRemarkName") }

            val chatId = toChatId(chatType, peerUid, peerUin)
            val fromId = when {
                senderUin > 0 -> senderUin.toString()
                senderUid.isNotEmpty() -> uinFromUid(senderUid).ifEmpty { senderUid }
                else -> ""
            }
            val selfUin = selfUinProvider()
            val isSelf = selfUin.isNotEmpty() && fromId == selfUin

            val dedupe = "$chatId|$msgId"
            val now = System.currentTimeMillis()
            val prev = recent.put(dedupe, now)
            if (prev != null && now - prev < 5_000) return
            if (recent.size > 300) {
                recent.entries.removeIf { now - it.value > 15_000 }
            }

            val payload = JSONObject()
                .put(TimMsgFields.CHAT_ID, chatId)
                .put(TimMsgFields.MSG_ID, msgId)
                .put(TimMsgFields.TEXT, text)
                .put(TimMsgFields.FROM_ID, fromId)
                .put(TimMsgFields.FROM_DISPLAY, sendNick)
                .put(TimMsgFields.IS_SELF, isSelf)
                .put(TimMsgFields.IS_GROUP, chatType == CHAT_TYPE_GROUP)
                .put(TimMsgFields.CHAT_TITLE, peerName)
                .put(TimMsgFields.TS, msgTime)

            note("MSG_IN chat=$chatId from=$fromId id=$msgId text=${text.take(40)}")
            emit(TimFrameTypes.MSG_IN, payload.toString().toByteArray(Charsets.UTF_8))
        } catch (t: Throwable) {
            note("handleRecord failed: ${t.javaClass.simpleName}:${t.message}")
        }
    }

    private fun toChatId(chatType: Int, peerUid: String, peerUin: Long): String {
        return if (chatType == CHAT_TYPE_GROUP) {
            val troop = when {
                peerUin > 0 -> peerUin.toString()
                peerUid.matches(Regex("\\d{5,12}")) -> peerUid
                else -> peerUid
            }
            "troop:$troop"
        } else {
            when {
                peerUin > 0 -> peerUin.toString()
                peerUid.matches(Regex("\\d{5,12}")) -> peerUid
                else -> uinFromUid(peerUid).ifEmpty { peerUid }
            }
        }
    }

    private fun uinFromUid(uid: String): String {
        if (uid.isEmpty() || uid.matches(Regex("\\d{5,12}"))) return uid
        return try {
            val qroute = classLoader.loadClass("com.tencent.mobileqq.qroute.QRoute")
            val apiCl = classLoader.loadClass(
                "com.tencent.relation.common.api.IRelationNTUinAndUidApi",
            )
            val api = qroute.methods.first {
                it.name == "api" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Class::class.java
            }.invoke(null, apiCl) ?: return ""
            for (name in listOf("getFriendUinFromUid", "getUinFromUid")) {
                val m = api.javaClass.methods.firstOrNull {
                    it.name == name &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.returnType == String::class.java
                } ?: continue
                val uin = (m.invoke(api, uid) as? String)?.trim().orEmpty()
                if (uin.matches(Regex("\\d{5,12}"))) return uin
            }
            ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun extractText(rec: Any): String? {
        val elements = field(rec, "elements") as? List<*> ?: return null
        val sb = StringBuilder()
        for (el in elements) {
            if (el == null) continue
            val textEl = field(el, "textElement") ?: continue
            val content = stringField(textEl, "content")
            if (content.isNotEmpty()) sb.append(content)
        }
        return sb.toString().ifEmpty { null }
    }

    private fun field(obj: Any, name: String): Any? {
        var cur: Class<*>? = obj.javaClass
        while (cur != null && cur != Any::class.java) {
            try {
                val f = cur.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            } catch (_: Throwable) {
            }
            cur = cur.superclass
        }
        return null
    }

    private fun stringField(obj: Any, name: String): String =
        field(obj, name)?.toString()?.trim().orEmpty()

    private fun intField(obj: Any, name: String): Int {
        val v = field(obj, name) ?: return 0
        return when (v) {
            is Int -> v
            is Number -> v.toInt()
            else -> 0
        }
    }

    private fun longField(obj: Any, name: String): Long {
        val v = field(obj, name) ?: return 0L
        return when (v) {
            is Long -> v
            is Number -> v.toLong()
            else -> 0L
        }
    }

    companion object {
        const val CHAT_TYPE_C2C = 1
        const val CHAT_TYPE_GROUP = 2
        @JvmField
        val installedHooks = AtomicBoolean(false)
    }
}
