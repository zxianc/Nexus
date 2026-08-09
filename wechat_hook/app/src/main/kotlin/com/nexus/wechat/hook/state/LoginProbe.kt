package com.nexus.wechat.hook.state

import android.util.Log
import com.nexus.wechat.hook.MainHook
import de.robv.android.xposed.XposedHelpers

/**
 * Read wxid/nickname from EnMicroMsg `userinfo` table.
 */
class LoginProbe {
    @Volatile private var wxid: String = ""
    @Volatile private var nick: String = ""

    fun isLoggedIn(): Boolean {
        if (wxid.isNotEmpty()) return true
        refreshIdentity()
        return wxid.isNotEmpty()
    }

    fun userIdOrEmpty(): String {
        if (wxid.isEmpty()) refreshIdentity()
        return wxid
    }

    fun nickOrEmpty(): String {
        if (nick.isEmpty()) refreshIdentity()
        return nick
    }

    @Synchronized
    fun refreshIdentity() {
        val candidates = listOfNotNull(DbHolder.accountDb.get(), DbHolder.lastDb.get()).distinct()
        for (db in candidates) {
            try {
                val id2 = queryUserInfo(db, 2) ?: continue
                if (looksLikeWxid(id2)) {
                    wxid = id2
                    DbHolder.markAccountDb(db)
                }
                val n = (queryUserInfo(db, 4) ?: "").ifEmpty { queryUserInfo(db, 5) ?: "" }
                if (n.isNotEmpty()) nick = n
                if (wxid.isNotEmpty()) {
                    Log.i(MainHook.TAG, "LoginProbe wxid=$wxid nick=$nick")
                    return
                }
            } catch (t: Throwable) {
                val msg = t.message.orEmpty()
                if (!msg.contains("no such table")) {
                    Log.w(MainHook.TAG, "refreshIdentity: $msg")
                }
            }
        }
    }

    /** null = wrong db / missing table; empty = row missing */
    private fun queryUserInfo(db: Any, id: Int): String? {
        val cursor = try {
            XposedHelpers.callMethod(
                db,
                "rawQuery",
                "SELECT value FROM userinfo WHERE id=? LIMIT 1",
                arrayOf<Any>(id.toString()),
            )
        } catch (t: Throwable) {
            if (t.message.orEmpty().contains("no such table")) return null
            try {
                XposedHelpers.callMethod(
                    db,
                    "rawQuery",
                    "SELECT value FROM userinfo WHERE id=? LIMIT 1",
                    arrayOf(id.toString()),
                )
            } catch (t2: Throwable) {
                if (t2.message.orEmpty().contains("no such table")) return null
                throw t2
            }
        } ?: return ""
        try {
            val move = XposedHelpers.callMethod(cursor, "moveToFirst") as? Boolean ?: false
            if (!move) return ""
            return (XposedHelpers.callMethod(cursor, "getString", 0) as? String).orEmpty()
        } finally {
            try {
                XposedHelpers.callMethod(cursor, "close")
            } catch (_: Throwable) {
            }
        }
    }

    private fun looksLikeWxid(v: String): Boolean {
        val s = v.trim()
        if (s.isEmpty()) return false
        return s.startsWith("wxid_") || (s.length in 5..32 && !s.contains(' ') && !s.contains('\n'))
    }
}
