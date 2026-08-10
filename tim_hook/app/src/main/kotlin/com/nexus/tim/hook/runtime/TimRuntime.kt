package com.nexus.tim.hook.runtime

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nexus.tim.hook.MainHook
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object TimRuntime {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun classLoader(fallback: ClassLoader): ClassLoader = fallback

    fun qqAppInterface(cl: ClassLoader): Any? {
        return try {
            val mobileQQCl = cl.loadClass("mqq.app.MobileQQ")
            val sField = mobileQQCl.getDeclaredField("sMobileQQ").apply { isAccessible = true }
            val mobileQQ = sField.get(null) ?: return null
            val methods = mobileQQCl.methods.filter {
                it.parameterTypes.isEmpty() && (
                    it.name == "waitAppRuntime" ||
                        it.name == "getAppRuntime" ||
                        it.returnType.name.contains("AppRuntime")
                    )
            }
            for (m in methods) {
                try {
                    m.isAccessible = true
                    val runtime = m.invoke(mobileQQ) ?: continue
                    if (runtime.javaClass.name.contains("QQAppInterface") ||
                        cl.loadClass("com.tencent.mobileqq.app.QQAppInterface")
                            .isInstance(runtime)
                    ) {
                        return runtime
                    }
                    // Some builds return AppRuntime; cast/check subclass.
                    if (runtime.javaClass.name.contains("AppRuntime")) {
                        return runtime
                    }
                } catch (_: Throwable) {
                }
            }
            null
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "qqAppInterface failed: ${t.message}")
            null
        }
    }

    fun <T> onMain(timeoutMs: Long = 8_000, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        val latch = CountDownLatch(1)
        val box = AtomicReference<Any?>(SENTINEL)
        val err = AtomicReference<Throwable?>(null)
        mainHandler.post {
            try {
                box.set(block())
            } catch (t: Throwable) {
                err.set(t)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            error("main_timeout")
        }
        err.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return box.get() as T
    }

    private val SENTINEL = Any()
}
