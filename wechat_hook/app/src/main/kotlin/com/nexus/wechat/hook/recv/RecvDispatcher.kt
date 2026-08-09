package com.nexus.wechat.hook.recv

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.nexus.wechat.hook.MainHook
import com.nexus.wechat.hook.runtime.WeChatRuntime
import com.nexus.wechat.hook.state.ContactDirectory
import com.nexus.wechat.hook.state.DbHolder
import com.nexus.wechat.hook.state.LoginProbe
import com.nexus.wechat.protocol.GroupAtParser
import com.nexus.wechat.protocol.WechatFrameTypes
import com.nexus.wechat.protocol.WechatMsgFields
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Observe inbound/outbound chat rows via WCDB access to `message`.
 * Must hook the Tinker runtime ClassLoader's WCDB class, not only lpparam's.
 */
class RecvDispatcher(
    private val fallbackLoader: ClassLoader,
    private val loginProbe: LoginProbe,
    private val contacts: ContactDirectory,
    private val emit: (type: Int, payload: ByteArray) -> Unit,
) {
    private val installed = AtomicBoolean(false)

    fun install() {
        installOn(fallbackLoader, label = "fallback")
        Thread({
            repeat(30) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val runtime = WeChatRuntime.classLoader(fallbackLoader)
                if (runtime !== fallbackLoader) {
                    installOn(runtime, label = "runtime")
                    return@Thread
                }
            }
        }, "nexus-wechat-recv-rehook").apply {
            isDaemon = true
            start()
        }
    }

    private fun installOn(loader: ClassLoader, label: String) {
        val clazz = XposedHelpers.findClassIfExists(
            "com.tencent.wcdb.database.SQLiteDatabase",
            loader,
        )
        if (clazz == null) {
            Log.w(MainHook.TAG, "RecvDispatcher[$label]: WCDB class missing")
            return
        }
        // Avoid double-hooking the exact same Class object.
        synchronized(this) {
            if (hookedClasses.contains(clazz)) return
            hookedClasses.add(clazz)
        }
        hookInsertPaths(clazz, label)
        hookCapturePaths(clazz, label)
        installed.set(true)
    }

    private fun hookInsertPaths(clazz: Class<*>, label: String) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "insertWithOnConflict",
                String::class.java,
                String::class.java,
                ContentValues::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        maybeCapture(param.thisObject, param.args[0] as? String)
                        onInsert(
                            param.thisObject,
                            param.args[0] as? String,
                            param.args[2] as? ContentValues,
                        )
                    }
                },
            )
            Log.i(MainHook.TAG, "RecvDispatcher[$label] hooked insertWithOnConflict on $clazz")
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "RecvDispatcher[$label] insertWithOnConflict: ${t.message}")
        }
        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "insert",
                String::class.java,
                String::class.java,
                ContentValues::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        maybeCapture(param.thisObject, param.args[0] as? String)
                        onInsert(
                            param.thisObject,
                            param.args[0] as? String,
                            param.args[2] as? ContentValues,
                        )
                    }
                },
            )
            Log.i(MainHook.TAG, "RecvDispatcher[$label] hooked insert on $clazz")
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "RecvDispatcher[$label] insert: ${t.message}")
        }
    }

    private fun hookCapturePaths(clazz: Class<*>, label: String) {
        var n = 0
        for (method in clazz.declaredMethods) {
            if (Modifier.isStatic(method.modifiers)) continue
            if (!shouldCapture(method.name)) continue
            try {
                XposedBridge.hookMethod(
                    method,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            captureFromArgs(param.thisObject, param.args)
                        }
                    },
                )
                n++
            } catch (_: Throwable) {
            }
        }
        Log.i(MainHook.TAG, "RecvDispatcher[$label] hooked $n WCDB capture methods")
    }

    private fun shouldCapture(name: String): Boolean = name in setOf(
        "rawQuery",
        "rawQueryWithFactory",
        "query",
        "queryWithFactory",
        "insert",
        "insertOrThrow",
        "replace",
        "replaceOrThrow",
        "update",
        "updateWithOnConflict",
        "delete",
    )

    private fun captureFromArgs(db: Any?, args: Array<Any?>?) {
        if (db == null || args == null) return
        for (arg in args) {
            if (arg is String && looksLikeContactOrMessage(arg)) {
                maybeCapture(db, arg)
                return
            }
        }
    }

    private fun looksLikeContactOrMessage(value: String): Boolean {
        val n = value.trim().lowercase()
        if (n == "message" || n == "rcontact" || n == "userinfo") return true
        if (n.startsWith("select ") && (n.contains("message") || n.contains("rcontact") || n.contains("userinfo"))) {
            return true
        }
        return n.contains(" from message") || n.contains(" from rcontact") || n.contains(" from userinfo")
    }

    private fun maybeCapture(db: Any?, hint: String?) {
        if (db == null) return
        val prev = DbHolder.lastDb.get()
        if (prev !== db) {
            DbHolder.preferAccount(db)
            Log.i(MainHook.TAG, "captured WCDB handle via ${hint?.take(60)}")
        }
        val h = hint?.lowercase().orEmpty()
        if (h.contains("userinfo") || h.contains("rcontact") || h == "message" ||
            h.contains(" from message") || h.contains(" from rcontact")
        ) {
            // Likely EnMicroMsg; LoginProbe will confirm via userinfo.
            DbHolder.preferAccount(db)
        }
    }

    private fun onInsert(db: Any?, table: String?, values: ContentValues?) {
        if (table != "message" || values == null) return
        DbHolder.markAccountDb(db)
        val talker = values.getAsString("talker") ?: return
        val content = values.getAsString("content") ?: ""
        val type = values.getAsInteger("type") ?: 1
        val isText = type == 1
        val isMedia = type == 3 || type == 49
        if (!isText && !isMedia) return
        if (isText && content.isEmpty()) return
        val isSend = values.getAsInteger("isSend") ?: 0
        val msgId = values.getAsLong("msgId")?.toString()
            ?: values.getAsString("msgId")
            ?: "db-${System.currentTimeMillis()}"
        val createTime = values.getAsLong("createTime") ?: System.currentTimeMillis()
        val isGroup = talker.endsWith("@chatroom")
        val selfId = loginProbe.userIdOrEmpty()
        val selfNick = loginProbe.nickOrEmpty()
        val isSelf = isSend == 1
        val fromId = when {
            isSelf -> selfId.ifEmpty { "self" }
            isGroup && content.contains(":\n") -> content.substringBefore(":\n")
            else -> talker
        }
        val text = when {
            isMedia -> ""
            isGroup && !isSelf && content.contains(":\n") -> content.substringAfter(":\n")
            else -> content
        }
        val fromDisplay = contacts.displayOf(fromId, selfId, selfNick)
        val chatTitle = contacts.displayOf(talker, selfId, selfNick)
        val at = if (isGroup) {
            resolveGroupAt(db, values, msgId, selfId)
        } else {
            GroupAtParser.Result(ats = emptyList(), atAll = false, atMe = false)
        }
        if (isGroup && (at.atMe || at.ats.isNotEmpty())) {
            Log.i(
                MainHook.TAG,
                "group at talker=$talker at_me=${at.atMe} at_all=${at.atAll} ats=${at.ats}",
            )
        }
        val payload = JSONObject()
            .put(WechatMsgFields.MSG_ID, msgId)
            .put(WechatMsgFields.CHAT_ID, talker)
            .put(WechatMsgFields.CHAT_TITLE, chatTitle)
            .put(WechatMsgFields.FROM_ID, fromId)
            .put(WechatMsgFields.FROM_DISPLAY, fromDisplay)
            .put(WechatMsgFields.IS_SELF, isSelf)
            .put(WechatMsgFields.IS_GROUP, isGroup)
            .put(WechatMsgFields.TEXT, text)
            .put(WechatMsgFields.ATS, JSONArray(at.ats))
            .put(WechatMsgFields.AT_ME, at.atMe)
            .put(WechatMsgFields.AT_ALL, at.atAll)
            .put(WechatMsgFields.TS, createTime / 1000)

        if (isMedia) {
            val hint = values.getAsString("imgPath")
                ?: values.getAsString("imgpath")
                ?: values.getAsString("content")
            val exportKey = "$talker|$msgId"
            if (recentExports.putIfAbsent(exportKey, System.currentTimeMillis()) != null) {
                return
            }
            // Retry: compress / CDN write may lag behind the DB insert.
            Thread({
                val delaysMs = if (isSelf) {
                    longArrayOf(400, 1000, 2000, 4000)
                } else {
                    longArrayOf(800, 1500, 3000, 6000)
                }
                var exported: MediaExporter.ExportResult? = null
                for (delay in delaysMs) {
                    try {
                        Thread.sleep(delay)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    exported = MediaExporter.export(
                        context = currentContext(),
                        msgType = type,
                        mediaHint = hint,
                        preferredName = if (type == 3) "image.jpg" else "file.bin",
                    )
                    if (exported != null) break
                }
                if (exported != null) {
                    val ready = JSONObject()
                        .put(WechatMsgFields.MEDIA_ID, exported.mediaId)
                        .put(WechatMsgFields.PATH, exported.path)
                        .put(WechatMsgFields.KIND, exported.kind)
                        .put(WechatMsgFields.NAME, exported.name)
                    emit(WechatFrameTypes.MEDIA_READY, ready.toString().toByteArray(Charsets.UTF_8))
                    payload.put(WechatMsgFields.MEDIA_ID, exported.mediaId)
                    payload.put(WechatMsgFields.MEDIA_KIND, exported.kind)
                    payload.put(WechatMsgFields.MEDIA_NAME, exported.name)
                } else {
                    Log.w(MainHook.TAG, "media export failed type=$type hint=$hint")
                }
                emitMessage(talker, msgId, payload)
            }, "nexus-wechat-media-export").apply {
                isDaemon = true
                start()
            }
            return
        }

        emitMessage(talker, msgId, payload)
    }

    private fun emitMessage(talker: String, msgId: String, payload: JSONObject) {
        // Dedupe by talker+msgId only: dual ClassLoader hooks and media retries
        // must not emit the same DB row twice.
        val dedupeKey = "$talker|$msgId"
        val now = System.currentTimeMillis()
        val prev = recentEmits.put(dedupeKey, now)
        if (prev != null && now - prev < 5_000) return
        if (recentEmits.size > 200) {
            recentEmits.entries.removeIf { now - it.value > 10_000 }
        }
        val mediaId = payload.optString(WechatMsgFields.MEDIA_ID, "")
        Log.i(
            MainHook.TAG,
            "MSG_IN talker=$talker id=$msgId media=${mediaId.ifEmpty { "-" }}",
        )
        emit(WechatFrameTypes.MSG_IN, payload.toString().toByteArray(Charsets.UTF_8))
    }

    private fun currentContext(): Context? {
        return try {
            val at = Class.forName("android.app.ActivityThread")
            XposedHelpers.callStaticMethod(at, "currentApplication") as? Application
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveGroupAt(
        db: Any?,
        values: ContentValues,
        msgId: String,
        selfId: String,
    ): GroupAtParser.Result {
        var msgSource = extractMsgSource(values)
        var lvbuffer = extractLvBuffer(values)
        var reserved = values.getAsString("reserved")
        var parsed = GroupAtParser.parse(msgSource, lvbuffer, selfId)
        if (parsed.ats.isNotEmpty() || parsed.atAll) return parsed
        // msgSource is often not a column; atuserlist lives in lvbuffer — re-read row if needed.
        if (db != null) {
            val row = queryMessageAtFields(db, msgId)
            if (row != null) {
                msgSource = row.first ?: msgSource
                lvbuffer = row.second ?: lvbuffer
                reserved = row.third ?: reserved
                parsed = GroupAtParser.parse(msgSource, lvbuffer, selfId)
                if (parsed.ats.isEmpty() && !reserved.isNullOrBlank()) {
                    parsed = GroupAtParser.parse(reserved, null, selfId)
                }
            }
        }
        if (parsed.ats.isEmpty() && !reserved.isNullOrBlank()) {
            parsed = GroupAtParser.parse(reserved, null, selfId)
        }
        return parsed
    }

    private fun extractMsgSource(values: ContentValues): String? {
        for (key in listOf("msgSource", "msgsource", "MsgSource", "reserved")) {
            val v = values.get(key) ?: continue
            when (v) {
                is String -> if (v.isNotBlank()) return v
                is ByteArray -> {
                    val s = v.toString(Charsets.UTF_8)
                    if (s.contains("atuserlist", ignoreCase = true)) return s
                }
            }
        }
        return null
    }

    private fun extractLvBuffer(values: ContentValues): ByteArray? {
        for (key in listOf("lvbuffer", "lvBuffer", "LVBUFFER")) {
            when (val v = values.get(key)) {
                is ByteArray -> if (v.isNotEmpty()) return v
                is String -> if (v.isNotEmpty()) return v.toByteArray(Charsets.UTF_8)
            }
        }
        return null
    }

    private fun queryMessageAtFields(
        db: Any,
        msgId: String,
    ): Triple<String?, ByteArray?, String?>? {
        val sqls = listOf(
            "SELECT lvbuffer, reserved FROM message WHERE msgId=? LIMIT 1",
            "SELECT lvbuffer FROM message WHERE msgId=? LIMIT 1",
        )
        for (sql in sqls) {
            val cursor = try {
                XposedHelpers.callMethod(db, "rawQuery", sql, arrayOf(msgId) as Any)
            } catch (_: Throwable) {
                try {
                    XposedHelpers.callMethod(
                        db,
                        "rawQuery",
                        sql,
                        arrayOf(msgId).map { it as Any }.toTypedArray(),
                    )
                } catch (_: Throwable) {
                    null
                }
            } ?: continue
            try {
                val moved = XposedHelpers.callMethod(cursor, "moveToFirst") as? Boolean ?: false
                if (!moved) continue
                val lv = try {
                    XposedHelpers.callMethod(cursor, "getBlob", 0) as? ByteArray
                } catch (_: Throwable) {
                    null
                }
                val reserved = if ((XposedHelpers.callMethod(cursor, "getColumnCount") as? Int) ?: 0 > 1) {
                    (XposedHelpers.callMethod(cursor, "getString", 1) as? String)
                } else {
                    null
                }
                return Triple(null, lv, reserved)
            } catch (t: Throwable) {
                Log.w(MainHook.TAG, "queryMessageAtFields: ${t.message}")
            } finally {
                try {
                    XposedHelpers.callMethod(cursor, "close")
                } catch (_: Throwable) {
                }
            }
        }
        return null
    }

    companion object {
        private val hookedClasses = mutableSetOf<Class<*>>()
        private val recentEmits = ConcurrentHashMap<String, Long>()
        private val recentExports = ConcurrentHashMap<String, Long>()
    }
}
