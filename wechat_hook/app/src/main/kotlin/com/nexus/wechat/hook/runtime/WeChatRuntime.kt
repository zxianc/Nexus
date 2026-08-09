package com.nexus.wechat.hook.runtime

import android.app.Application
import android.content.Context
import android.util.Log
import com.nexus.wechat.hook.MainHook
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicReference

/**
 * Resolve WeChat's patched runtime ClassLoader (Tinker / DelegateLast).
 * Using [XC_LoadPackage] classLoader alone often sees uninitialized statics
 * (ServiceManager / EventCenter "call init first").
 */
object WeChatRuntime {
    private val appContext = AtomicReference<Context?>(null)
    private val cachedLoader = AtomicReference<ClassLoader?>(null)

    fun install(fallback: ClassLoader) {
        cachedLoader.compareAndSet(null, fallback)
        try {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = param.args.firstOrNull() as? Context
                        if (ctx != null) appContext.set(ctx)
                        val loader = param.thisObject?.javaClass?.classLoader
                        remember(loader)
                        Log.i(MainHook.TAG, "Application.attach loader=${loader}")
                    }
                },
            )
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "hook Application.attach failed: ${t.message}")
        }
        try {
            XposedHelpers.findAndHookMethod(
                "com.tencent.mm.app.MMApplicationLike",
                fallback,
                "onBaseContextAttached",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = param.args.firstOrNull() as? Context
                        if (ctx != null) appContext.set(ctx)
                        remember(param.thisObject?.javaClass?.classLoader)
                        Log.i(MainHook.TAG, "MMApplicationLike.onBaseContextAttached")
                    }
                },
            )
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "hook MMApplicationLike failed: ${t.message}")
        }
    }

    fun classLoader(fallback: ClassLoader): ClassLoader {
        val candidates = LinkedHashSet<ClassLoader>()
        cachedLoader.get()?.let { candidates.add(it) }
        Thread.currentThread().contextClassLoader?.let { candidates.add(it) }
        val app = currentApplication()
        if (app is Application) {
            app.classLoader?.let { candidates.add(it) }
        }
        app?.javaClass?.classLoader?.let { candidates.add(it) }
        appContext.get()?.classLoader?.let { candidates.add(it) }
        candidates.add(fallback)

        for (c in candidates) {
            if (isPatched(c) && canLoadKernel(c)) {
                remember(c)
                return c
            }
        }
        for (c in candidates) {
            if (canLoadKernel(c)) {
                remember(c)
                return c
            }
        }
        return fallback
    }

    fun ensureKernelReady(fallback: ClassLoader) {
        val cl = classLoader(fallback)
        try {
            val n0 = XposedHelpers.findClass("pa5.n0", cl)
            val f = XposedHelpers.getStaticObjectField(n0, "f") as? BooleanArray
            if (f != null && f.isNotEmpty() && f[0]) {
                Log.i(MainHook.TAG, "ServiceManager already ready via $cl")
                return
            }
            Log.w(MainHook.TAG, "ServiceManager not ready (f=${f?.getOrNull(0)}); trying bootstrap")
            bootstrap(cl)
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "ensureKernelReady: ${t.message}")
        }
    }

    private fun bootstrap(cl: ClassLoader) {
        val app = currentApplication() as? Application
            ?: throw IllegalStateException("Application is null")
        val o0 = XposedHelpers.findClass("com.tencent.mm.app.o0", cl)
        val processType = XposedHelpers.getStaticObjectField(o0, "d")
        val providerHolder = XposedHelpers.getStaticObjectField(
            XposedHelpers.findClass("com.tencent.mm.app.n0", cl),
            "d",
        )
        val y = XposedHelpers.callMethod(providerHolder, "b")
            ?: throw IllegalStateException("com.tencent.mm.app.n0.d.b() null")

        val isG = XposedHelpers.findClass("is.g", cl)
        val slot = XposedHelpers.getStaticObjectField(isG, "a") as Array<Any?>
        if (slot.isNotEmpty() && slot[0] == null) {
            XposedHelpers.setStaticObjectField(isG, "c", app)
            slot[0] = XposedHelpers.getStaticObjectField(
                XposedHelpers.findClass("is.k2", cl),
                "e",
            )
            XposedHelpers.setStaticObjectField(isG, "b", processType)
            Log.i(MainHook.TAG, "bootstrapped is.g EventCenter")
        }

        val n0 = XposedHelpers.findClass("pa5.n0", cl)
        val f = XposedHelpers.getStaticObjectField(n0, "f") as BooleanArray
        if (!f[0]) {
            XposedHelpers.callStaticMethod(n0, "d", app, y, processType)
            Log.i(MainHook.TAG, "bootstrapped pa5.n0 ServiceManager")
        }
    }

    private fun remember(loader: ClassLoader?) {
        if (loader == null) return
        cachedLoader.set(loader)
    }

    private fun isPatched(loader: ClassLoader): Boolean {
        val s = loader.toString()
        return s.contains("DelegateLastClassLoader") ||
            s.contains("tinker_classN") ||
            s.contains("/tinker/")
    }

    private fun canLoadKernel(loader: ClassLoader): Boolean {
        return try {
            Class.forName("pa5.n0", false, loader)
            Class.forName("qh3.t1", false, loader)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun currentApplication(): Context? {
        appContext.get()?.let { return it }
        return try {
            val at = Class.forName("android.app.ActivityThread")
            XposedHelpers.callStaticMethod(at, "currentApplication") as? Context
        } catch (_: Throwable) {
            null
        }
    }
}
