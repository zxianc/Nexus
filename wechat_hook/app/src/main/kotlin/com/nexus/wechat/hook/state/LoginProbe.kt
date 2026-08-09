package com.nexus.wechat.hook.state

import android.util.Log
import com.nexus.wechat.hook.MainHook
import de.robv.android.xposed.XposedHelpers

/**
 * Conservative login probe. Prefer false negatives over false positives.
 * Real account fields are filled once a stable API for 8.0.76 is confirmed.
 */
class LoginProbe(
    private val classLoader: ClassLoader,
) {
    fun isLoggedIn(): Boolean {
        return try {
            // Common historical entry; may be renamed on this pin — fail closed.
            val clazz = XposedHelpers.findClassIfExists(
                "com.tencent.mm.model.z",
                classLoader,
            ) ?: return false
            val uin = XposedHelpers.callStaticMethod(clazz, "b") as? Int ?: return false
            uin != 0
        } catch (e: Throwable) {
            Log.w(MainHook.TAG, "LoginProbe failed closed: ${e.message}")
            false
        }
    }

    fun userIdOrEmpty(): String = ""

    fun nickOrEmpty(): String = ""
}
