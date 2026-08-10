package com.nexus.tim.hook.state

import android.content.Context
import android.util.Log
import com.nexus.tim.hook.MainHook
import java.io.File

/**
 * Best-effort TIM login probe from SharedPreferences (no root required inside TIM process).
 */
class LoginProbe {
    data class Snapshot(
        val loggedIn: Boolean,
        val userId: String,
        val nick: String,
    )

    fun probe(appContext: Context?): Snapshot {
        if (appContext == null) {
            return Snapshot(loggedIn = false, userId = "", nick = "")
        }
        return try {
            val uin = resolveUin(appContext)
            if (uin.isBlank()) {
                return Snapshot(loggedIn = false, userId = "", nick = "")
            }
            val nick = resolveNick(appContext, uin)
            Snapshot(loggedIn = true, userId = uin, nick = nick)
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "login probe failed: ${t.message}")
            Snapshot(loggedIn = false, userId = "", nick = "")
        }
    }

    private fun resolveUin(ctx: Context): String {
        // sp_login_auto keys are often just the uin digits.
        val auto = ctx.getSharedPreferences("sp_login_auto", Context.MODE_PRIVATE).all
        auto.keys
            .map { it.trim() }
            .firstOrNull { it.matches(UIN_RE) }
            ?.let { return it }

        val prefsDir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        val fromFiles = prefsDir.listFiles().orEmpty()
            .map { it.name.removeSuffix(".xml") }
            .filter { it.matches(UIN_RE) }
            .sortedByDescending { it.length }
        if (fromFiles.isNotEmpty()) {
            // Prefer uin that also appears as "${uin}.xml" account file.
            return fromFiles.first()
        }

        for (name in listOf("mobileQQ", "account_prefs", "Last_Login")) {
            val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
            for (key in listOf("uin", "uinstr", "currentUin", "login_uin", "account")) {
                val v = (sp.getString(key, "") ?: "").trim().trimStart('o')
                if (v.matches(UIN_RE)) return v
            }
        }
        return ""
    }

    private fun resolveNick(ctx: Context, uin: String): String {
        val account = ctx.getSharedPreferences(uin, Context.MODE_PRIVATE)
        for (key in listOf("nick", "nickname", "name", "rich_status")) {
            val v = account.getString(key, "")?.trim().orEmpty()
            if (v.isNotEmpty()) return v
        }
        val mobile = ctx.getSharedPreferences("mobileQQ", Context.MODE_PRIVATE)
        return mobile.getString("${uin}_nick", mobile.getString("nick", ""))?.trim().orEmpty()
    }

    companion object {
        private val UIN_RE = Regex("^\\d{5,12}$")
    }
}
