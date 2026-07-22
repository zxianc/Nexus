package com.nexus.phone.nexus.telecom

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import com.nexus.phone.nexus.config.ConfigRepository

/**
 * Nexus AI policy engine switch (prefs only).
 * Fossify [com.nexus.phone.services.CallService] remains the sole UI InCallService.
 */
object DialerTakeover {
    data class Status(
        val takeoverEnabled: Boolean,
        val isDefaultDialer: Boolean,
        val roleHolder: String?,
        val message: String,
    )

    fun isEnabled(context: Context): Boolean =
        ConfigRepository(context).load().dialerTakeover

    fun isDefaultDialer(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = context.getSystemService(RoleManager::class.java)
            return rm?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        }
        val tm = context.getSystemService(TelecomManager::class.java)
        return tm?.defaultDialerPackage == context.packageName
    }

    fun currentDialer(context: Context): String? {
        val tm = context.getSystemService(TelecomManager::class.java)
        return tm?.defaultDialerPackage
    }

    fun probe(context: Context): Status {
        val enabled = isEnabled(context)
        val def = isDefaultDialer(context)
        val holder = currentDialer(context)
        val msg =
            buildString {
                append(if (enabled) "Nexus 策略：开" else "Nexus 策略：关")
                append('\n')
                append(if (def) "已是默认电话" else "未设为默认电话")
                append("\n当前默认电话：${holder ?: "未知"}")
            }
        return Status(enabled, def, holder, msg)
    }

    fun setEnabled(context: Context, enable: Boolean): Result<String> {
        val repo = ConfigRepository(context)
        val cfg = repo.load()
        repo.save(cfg.copy(dialerTakeover = enable))
        return if (enable) {
            if (isDefaultDialer(context)) {
                Result.success("Nexus 策略已开启")
            } else {
                Result.failure(IllegalStateException("请先将本应用设为默认电话"))
            }
        } else {
            Result.success("Nexus 策略已关闭（仍可为默认电话，但不自动 AI 接听）")
        }
    }

    fun requestRoleUi(context: Context): Intent? {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = context.getSystemService(RoleManager::class.java) ?: return null
            if (!rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                return Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            }
            return rm.createRequestRoleIntent(RoleManager.ROLE_DIALER)
        }
        return Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(
            TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
            context.packageName,
        )
    }

    fun prepareRoleRequest(context: Context): Result<Unit> = Result.success(Unit)
}
