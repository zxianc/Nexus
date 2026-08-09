package com.nexus.wechat.hook.state

import android.util.Log
import com.nexus.wechat.hook.MainHook
import com.nexus.wechat.protocol.ChatSendPolicy
import com.nexus.wechat.protocol.WechatMsgFields
import de.robv.android.xposed.XposedHelpers
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolve chat list + display names from EnMicroMsg tables.
 */
class ContactDirectory {
    private val displayCache = ConcurrentHashMap<String, String>()

    fun displayOf(userId: String, selfId: String = "", selfNick: String = ""): String {
        if (userId.isEmpty()) return ""
        if (userId == "self" || (selfId.isNotEmpty() && userId == selfId)) {
            return selfNick.ifEmpty { selfId.ifEmpty { "self" } }
        }
        displayCache[userId]?.let { return it }
        val db = DbHolder.bestDb() ?: return userId
        val name = queryDisplay(db, userId)
        if (name.isNotEmpty()) {
            displayCache[userId] = name
            return name
        }
        return userId
    }

    /**
     * @return null if send is allowed; otherwise error code (`unknown_chat` / `not_friend` / …).
     */
    fun validateSendTarget(chatId: String): String? {
        val id = chatId.trim()
        if (id.isEmpty()) return "missing_fields"
        if (id == "filehelper") return null
        val db = DbHolder.bestDb()
        if (db == null) {
            // Without DB we cannot prove the target is safe — refuse to avoid stranger sessions.
            return "unknown_chat"
        }
        val contact = queryContactRow(db, id)
        val chatroomKnown = id.endsWith("@chatroom") && (
            contact != null ||
                existsScalar(db, "SELECT chatroomname FROM chatroom WHERE chatroomname=? LIMIT 1", id) ||
                existsScalar(db, "SELECT username FROM rconversation WHERE username=? LIMIT 1", id)
            )
        return ChatSendPolicy.validate(id, contact, chatroomKnown)
    }

    fun listChats(limit: Int = 80): JSONArray {
        val arr = JSONArray()
        val db = DbHolder.bestDb() ?: return arr
        val rows = queryRecentTalkers(db, limit)
        for ((chatId, fallbackTitle) in rows) {
            if (chatId.isEmpty()) continue
            if (chatId == "filehelper" || chatId == "weixin" || chatId.startsWith("gh_") ||
                chatId.endsWith("@app") || chatId.contains("fake_")
            ) {
                // keep filehelper; skip obvious system noise below
            }
            if (chatId.startsWith("fake_") || chatId.endsWith("@stranger")) continue
            val isGroup = chatId.endsWith("@chatroom")
            val title = queryDisplay(db, chatId).ifEmpty { fallbackTitle }.ifEmpty { chatId }
            arr.put(
                JSONObject()
                    .put(WechatMsgFields.CHAT_ID, chatId)
                    .put(WechatMsgFields.TITLE, title)
                    .put(WechatMsgFields.IS_GROUP, isGroup)
                    .put(WechatMsgFields.MEMBERS, JSONArray()),
            )
            if (title.isNotEmpty() && title != chatId) {
                displayCache[chatId] = title
            }
        }
        // Always expose filehelper for easy testing.
        var hasHelper = false
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString(WechatMsgFields.CHAT_ID) == "filehelper") {
                hasHelper = true
                break
            }
        }
        if (!hasHelper) {
            arr.put(
                JSONObject()
                    .put(WechatMsgFields.CHAT_ID, "filehelper")
                    .put(WechatMsgFields.TITLE, "文件传输助手")
                    .put(WechatMsgFields.IS_GROUP, false)
                    .put(WechatMsgFields.MEMBERS, JSONArray()),
            )
        }
        return arr
    }

    private fun queryRecentTalkers(db: Any, limit: Int): List<Pair<String, String>> {
        val sqls = listOf(
            "SELECT username, '' FROM rconversation ORDER BY conversationTime DESC LIMIT $limit",
            "SELECT talker, '' FROM message GROUP BY talker ORDER BY max(createTime) DESC LIMIT $limit",
            "SELECT username, nickname FROM rcontact WHERE type & 1 != 0 AND username NOT LIKE '%@%' LIMIT $limit",
        )
        for (sql in sqls) {
            val rows = rawQueryPairs(db, sql)
            if (rows.isNotEmpty()) return rows
        }
        return emptyList()
    }

    private fun queryDisplay(db: Any, username: String): String {
        val sql = "SELECT conRemark, nickname, alias FROM rcontact WHERE username=? LIMIT 1"
        val cursor = rawQuery(db, sql, arrayOf(username)) ?: return ""
        try {
            if (!moveToFirst(cursor)) return ""
            val remark = getString(cursor, 0)
            val nick = getString(cursor, 1)
            val alias = getString(cursor, 2)
            return remark.ifEmpty { nick }.ifEmpty { alias }
        } finally {
            closeQuietly(cursor)
        }
    }

    private fun queryContactRow(db: Any, username: String): ChatSendPolicy.ContactRow? {
        val sqls = listOf(
            "SELECT type, deleteFlag FROM rcontact WHERE username=? LIMIT 1",
            "SELECT type, 0 FROM rcontact WHERE username=? LIMIT 1",
        )
        for (sql in sqls) {
            val cursor = rawQuery(db, sql, arrayOf(username)) ?: continue
            try {
                if (!moveToFirst(cursor)) return null
                val type = getInt(cursor, 0)
                val deleteFlag = if (columnCount(cursor) > 1) getInt(cursor, 1) else 0
                return ChatSendPolicy.ContactRow(type = type, deleteFlag = deleteFlag)
            } catch (t: Throwable) {
                Log.w(MainHook.TAG, "queryContactRow($sql): ${t.message}")
            } finally {
                closeQuietly(cursor)
            }
        }
        return null
    }

    private fun existsScalar(db: Any, sql: String, arg: String): Boolean {
        val cursor = rawQuery(db, sql, arrayOf(arg)) ?: return false
        try {
            return moveToFirst(cursor)
        } finally {
            closeQuietly(cursor)
        }
    }

    private fun rawQueryPairs(db: Any, sql: String): List<Pair<String, String>> {
        val cursor = rawQuery(db, sql, emptyArray()) ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        try {
            while (moveToNext(cursor)) {
                val a = getString(cursor, 0)
                val b = if (columnCount(cursor) > 1) getString(cursor, 1) else ""
                if (a.isNotEmpty()) out.add(a to b)
            }
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "rawQueryPairs failed: ${t.message}")
        } finally {
            closeQuietly(cursor)
        }
        return out
    }

    private fun rawQuery(db: Any, sql: String, args: Array<String>): Any? {
        return try {
            XposedHelpers.callMethod(db, "rawQuery", sql, args as Any)
        } catch (_: Throwable) {
            try {
                XposedHelpers.callMethod(db, "rawQuery", sql, args.map { it as Any }.toTypedArray())
            } catch (t: Throwable) {
                if (!t.message.orEmpty().contains("no such table")) {
                    Log.w(MainHook.TAG, "rawQuery: ${t.message}")
                }
                null
            }
        }
    }

    private fun moveToFirst(cursor: Any): Boolean =
        XposedHelpers.callMethod(cursor, "moveToFirst") as? Boolean ?: false

    private fun moveToNext(cursor: Any): Boolean =
        XposedHelpers.callMethod(cursor, "moveToNext") as? Boolean ?: false

    private fun columnCount(cursor: Any): Int =
        (XposedHelpers.callMethod(cursor, "getColumnCount") as? Int) ?: 0

    private fun getString(cursor: Any, idx: Int): String =
        (XposedHelpers.callMethod(cursor, "getString", idx) as? String).orEmpty()

    private fun getInt(cursor: Any, idx: Int): Int {
        return try {
            when (val v = XposedHelpers.callMethod(cursor, "getInt", idx)) {
                is Int -> v
                is Number -> v.toInt()
                else -> 0
            }
        } catch (_: Throwable) {
            getString(cursor, idx).toIntOrNull() ?: 0
        }
    }

    private fun closeQuietly(cursor: Any) {
        try {
            XposedHelpers.callMethod(cursor, "close")
        } catch (_: Throwable) {
        }
    }
}
