package com.nexus.tim.hook

import android.app.AndroidAppHelper
import android.util.Log
import com.nexus.tim.hook.state.LoginProbe
import com.nexus.tim.hook.uds.BridgeUdsClient
import com.nexus.tim.hook.version.SupportedTim
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != SupportedTim.PACKAGE) return
        XposedBridge.log("nexus_tim_hook loaded process=${lpparam.processName}")
        Log.i(TAG, "loaded process=${lpparam.processName} versionPin=${SupportedTim.VERSION_NAME}")

        if (lpparam.processName != SupportedTim.PACKAGE) return

        val loginProbe = LoginProbe()
        // Application context is often null at LoadPackage; client refreshes each HELLO.
        val client = BridgeUdsClient(
            appContextProvider = {
                try {
                    AndroidAppHelper.currentApplication()?.applicationContext
                } catch (_: Throwable) {
                    null
                }
            },
            loginProbe = loginProbe,
        )
        Thread(client, "nexus-tim-uds").apply {
            isDaemon = true
            start()
        }
    }

    companion object {
        const val TAG = "NexusTimHook"
    }
}
