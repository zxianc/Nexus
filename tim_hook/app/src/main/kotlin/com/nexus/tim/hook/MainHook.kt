package com.nexus.tim.hook

import android.app.AndroidAppHelper
import android.util.Log
import com.nexus.tim.hook.recv.RecvDispatcher
import com.nexus.tim.hook.send.LoginUin
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
        val appContextProvider = {
            try {
                AndroidAppHelper.currentApplication()?.applicationContext
            } catch (_: Throwable) {
                null
            }
        }
        val client = BridgeUdsClient(
            appContextProvider = appContextProvider,
            loginProbe = loginProbe,
            hostClassLoader = lpparam.classLoader,
        )
        RecvDispatcher(
            classLoader = lpparam.classLoader,
            selfUinProvider = {
                val ctx = appContextProvider()
                if (ctx != null) LoginUin.self(lpparam.classLoader, ctx) else ""
            },
            emit = { type, payload -> client.emit(type, payload) },
        ).install()

        Thread(client, "nexus-tim-uds").apply {
            isDaemon = true
            start()
        }
    }

    companion object {
        const val TAG = "NexusTimHook"
    }
}
