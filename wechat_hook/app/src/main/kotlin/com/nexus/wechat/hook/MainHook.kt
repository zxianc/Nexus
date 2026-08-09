package com.nexus.wechat.hook

import android.util.Log
import com.nexus.wechat.hook.recv.RecvDispatcher
import com.nexus.wechat.hook.runtime.WeChatRuntime
import com.nexus.wechat.hook.state.ContactDirectory
import com.nexus.wechat.hook.state.LoginProbe
import com.nexus.wechat.hook.uds.BridgeUdsClient
import com.nexus.wechat.hook.version.SupportedWeChat
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != SupportedWeChat.PACKAGE) return
        XposedBridge.log("nexus_wechat_hook loaded process=${lpparam.processName}")
        Log.i(TAG, "loaded process=${lpparam.processName} versionPin=${SupportedWeChat.VERSION_NAME}")

        if (lpparam.processName != SupportedWeChat.PACKAGE) return

        WeChatRuntime.install(lpparam.classLoader)

        val loginProbe = LoginProbe()
        val contacts = ContactDirectory()
        val client = BridgeUdsClient(
            fallbackLoader = lpparam.classLoader,
            loginProbe = loginProbe,
            contacts = contacts,
        )
        RecvDispatcher(
            fallbackLoader = lpparam.classLoader,
            loginProbe = loginProbe,
            contacts = contacts,
        ) { type, payload ->
            client.emitFrame(type, payload)
        }.install()

        Thread(client, "nexus-wechat-uds").apply {
            isDaemon = true
            start()
        }
    }

    companion object {
        const val TAG = "NexusWeChatHook"
    }
}
