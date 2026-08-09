package com.nexus.wechat.hook.state

import java.util.concurrent.atomic.AtomicReference

object DbHolder {
    /** Last seen WCDB handle (any). */
    val lastDb = AtomicReference<Any?>(null)

    /** Prefer EnMicroMsg-like handle that has userinfo/rcontact. */
    val accountDb = AtomicReference<Any?>(null)

    fun preferAccount(db: Any?) {
        if (db == null) return
        lastDb.set(db)
    }

    fun markAccountDb(db: Any?) {
        if (db == null) return
        accountDb.set(db)
        lastDb.set(db)
    }

    fun bestDb(): Any? = accountDb.get() ?: lastDb.get()
}
