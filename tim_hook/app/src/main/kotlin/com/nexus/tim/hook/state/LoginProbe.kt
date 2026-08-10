package com.nexus.tim.hook.state

import android.content.Context
import android.util.Log
import com.nexus.tim.hook.MainHook

/**
 * Best-effort TIM login probe. P0 may report logged_in=false until reverse notes land.
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
            // Common QQ/TIM preference names; may be empty on some builds.
            val prefsNames = listOf("account_prefs", "Last_Login", "mobileQQ")
            var uin = ""
            var nick = ""
            for (name in prefsNames) {
                val sp = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
                val cand = sequenceOf("uin", "uinstr", "currentUin", "login_uin")
                    .map { sp.getString(it, "") ?: "" }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
                if (cand.isNotBlank()) {
                    uin = cand.trimStart('o').trim()
                    nick = sp.getString("nick", sp.getString("nickname", "")) ?: ""
                    break
                }
            }
            Snapshot(loggedIn = uin.isNotBlank(), userId = uin, nick = nick)
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "login probe failed: ${t.message}")
            Snapshot(loggedIn = false, userId = "", nick = "")
        }
    }
}
