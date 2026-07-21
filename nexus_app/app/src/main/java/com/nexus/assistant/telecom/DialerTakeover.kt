package com.nexus.assistant.telecom

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import com.nexus.assistant.config.ConfigRepository
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Switch between Nexus call takeover and stock Dialer.
 *
 * ON:  enable Nexus InCall + disable stock Dialer InCall + ROLE_DIALER=Nexus
 * OFF: enable stock Dialer InCall + disable Nexus InCall + ROLE_DIALER=system
 *
 * Disabling the unused side's InCallService is required so Telecom's
 * Default-Dialer BindingConnection cannot keep pointing at a stale component.
 */
object DialerTakeover {
    const val SYSTEM_DIALER = "com.android.dialer"
    const val SYSTEM_INCALL =
        "com.android.dialer/com.android.incallui.InCallServiceImpl"

    data class Status(
        val takeoverEnabled: Boolean,
        val roleHolder: String?,
        val stockInCallDisabled: Boolean?,
        val nexusInCallDisabled: Boolean?,
        val message: String,
    )

    fun isEnabled(context: Context): Boolean =
        ConfigRepository(context).load().dialerTakeover

    fun probe(context: Context): Status {
        val enabled = isEnabled(context)
        val holder = currentDialer(context)
        val stockOff = componentDisabled(SYSTEM_DIALER, "com.android.incallui.InCallServiceImpl")
        val nexusOff =
            componentDisabled(context.packageName, "com.nexus.assistant.telecom.NexusInCallService")
        val msg =
            buildString {
                append(if (enabled) "Nexus 接管：开" else "Nexus 接管：关（系统电话）")
                append("\n当前默认电话：${holder ?: "未知"}")
                append(
                    "\n系统 Dialer InCall：" +
                        when (stockOff) {
                            true -> "禁用"
                            false -> "启用"
                            null -> "未知"
                        },
                )
                append(
                    "\nNexus InCall：" +
                        when (nexusOff) {
                            true -> "禁用"
                            false -> "启用"
                            null -> "未知"
                        },
                )
                if (!enabled && (stockOff == true || nexusOff == false)) {
                    append("\n⚠ 交回不完整，请再点一次交回")
                }
                if (enabled && (stockOff != true || nexusOff == true)) {
                    append("\n⚠ 接管不完整，请再点一次开启")
                }
            }
        return Status(enabled, holder, stockOff, nexusOff, msg)
    }

    fun setEnabled(context: Context, enable: Boolean): Result<String> {
        val repo = ConfigRepository(context)
        val cfg = repo.load()
        return try {
            if (enable) {
                enableNexus(context)
            } else {
                restoreSystem(context)
            }
            repo.save(cfg.copy(dialerTakeover = enable))
            val st = probe(context)
            val ok =
                if (enable) {
                    st.stockInCallDisabled == true && st.nexusInCallDisabled != true
                } else {
                    st.stockInCallDisabled != true && st.nexusInCallDisabled == true
                }
            if (!ok) {
                Result.failure(IllegalStateException("组件状态未对齐：\n${st.message}"))
            } else {
                Result.success(if (enable) "已切换为 Nexus 接管" else "已交回系统电话")
            }
        } catch (e: Exception) {
            Log.e(TAG, "setEnabled enable=$enable", e)
            Result.failure(e)
        }
    }

    fun requestRoleUi(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < 29) return null
        val rm = context.getSystemService(RoleManager::class.java) ?: return null
        if (!rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
            return Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        }
        return rm.createRequestRoleIntent(RoleManager.ROLE_DIALER)
    }

    /**
     * Re-enable Nexus InCall **before** showing ROLE_DIALER UI.
     * If Nexus ICS stays disabled (OFF path), RoleManager treats the app as
     * ineligible and the confirmation dialog never appears.
     * Does not disable stock Dialer yet — that waits for user confirm.
     */
    fun prepareRoleRequest(context: Context): Result<Unit> {
        return try {
            val nexusIcs = "${context.packageName}/.telecom.NexusInCallService"
            setOwnComponentEnabled(context, NexusInCallService::class.java, true)
            suQuiet("pm enable $nexusIcs")
            if (componentDisabled(context.packageName, "com.nexus.assistant.telecom.NexusInCallService") == true) {
                Result.failure(IllegalStateException("无法启用 Nexus InCall，无法申请默认电话"))
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "prepareRoleRequest", e)
            Result.failure(e)
        }
    }

    private fun enableNexus(context: Context) {
        val nexusIcs = "${context.packageName}/.telecom.NexusInCallService"
        // App-level enable (no root needed for own component).
        setOwnComponentEnabled(context, NexusInCallService::class.java, true)
        suOrThrow("pm enable $nexusIcs")
        suOrThrow("pm disable $SYSTEM_INCALL")
        if (componentDisabled(SYSTEM_DIALER, "com.android.incallui.InCallServiceImpl") != true) {
            throw IllegalStateException("未能禁用系统 Dialer InCall")
        }
        suQuiet("cmd role add-role-holder android.app.role.DIALER ${context.packageName}")
        suQuiet("cmd telecom set-default-dialer ${context.packageName}")
        suQuiet("am force-stop $SYSTEM_DIALER")
    }

    private fun restoreSystem(context: Context) {
        val nexusIcs = "${context.packageName}/.telecom.NexusInCallService"
        // 1) Bring stock Dialer ICS back.
        suOrThrow("pm enable $SYSTEM_INCALL")
        suQuiet("pm enable $SYSTEM_DIALER")
        if (componentDisabled(SYSTEM_DIALER, "com.android.incallui.InCallServiceImpl") == true) {
            throw IllegalStateException("pm enable 后系统 Dialer InCall 仍禁用")
        }
        // 2) Disable Nexus ICS so Telecom cannot keep Default-Dialer → Nexus.
        setOwnComponentEnabled(context, NexusInCallService::class.java, false)
        suQuiet("pm disable $nexusIcs")
        // 3) Hand back role / default dialer.
        suQuiet("cmd role remove-role-holder android.app.role.DIALER ${context.packageName}")
        suQuiet("cmd role add-role-holder android.app.role.DIALER $SYSTEM_DIALER")
        suQuiet("cmd telecom set-default-dialer $SYSTEM_DIALER")
        // 4) Do NOT am-start Dialer UI here — that pops the stock dialpad over Settings.
        //    We never force-stop Dialer on restore, so stopped=true should not recur.
        // 5) Bounce Telephony so Telecom drops any stale Default-Dialer → Nexus bind.
        suQuiet("cmd telecom cleanup-stuck-calls")
        suQuiet("kill \$(pidof com.android.phone)")
    }

    private fun setOwnComponentEnabled(
        context: Context,
        cls: Class<*>,
        enabled: Boolean,
    ) {
        val pm = context.packageManager
        val cn = ComponentName(context, cls)
        val state =
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
        pm.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP)
    }

    private fun currentDialer(context: Context): String? {
        return try {
            if (Build.VERSION.SDK_INT >= 23) {
                context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun componentDisabled(pkg: String, classSuffix: String): Boolean? {
        val out = suCapture("dumpsys package $pkg") ?: return null
        val disIdx = out.indexOf("disabledComponents:")
        if (disIdx >= 0) {
            val window = out.substring(disIdx, (disIdx + 500).coerceAtMost(out.length))
            val lines =
                window.lines().drop(1).takeWhile {
                    it.startsWith(" ") || it.startsWith("\t") || it.isBlank()
                }
            if (lines.any { it.contains(classSuffix.substringAfterLast('.')) || it.contains(classSuffix) }) {
                return true
            }
        }
        // Also treat explicit enabledComponents listing as enabled when present.
        return false
    }

    private fun suOrThrow(cmd: String) {
        val out = suCapture(cmd) ?: throw IllegalStateException("su 不可用，无法切换拨号接管")
        if (out.contains("Exception occurred") || out.contains("SecurityException")) {
            throw IllegalStateException(out.take(200))
        }
    }

    private fun suQuiet(cmd: String) {
        try {
            suCapture(cmd)
        } catch (e: Exception) {
            Log.w(TAG, "suQuiet: $cmd", e)
        }
    }

    private fun suCapture(cmd: String): String? {
        return try {
            val proc =
                ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true)
                    .start()
            val text =
                BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            proc.waitFor()
            text
        } catch (e: Exception) {
            Log.e(TAG, "suCapture", e)
            null
        }
    }

    private const val TAG = "DialerTakeover"
}
