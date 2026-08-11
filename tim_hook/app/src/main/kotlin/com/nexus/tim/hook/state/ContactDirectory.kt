package com.nexus.tim.hook.state

import android.util.Log
import com.nexus.tim.hook.MainHook
import com.nexus.tim.hook.runtime.TimRuntime
import com.nexus.tim.protocol.TimMsgFields
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Friend + group directory for HELLO / LAN API.
 *
 * Friends: [IFriendDataService.getAllFriends] (uin / remark / name).
 * Groups: Kernel [IKernelGroupService.getGroupList] → [onGroupListUpdate]
 *         (GroupSimpleInfo.groupCode / groupName / remarkName).
 */
class ContactDirectory(
    private val classLoader: ClassLoader,
) {
    @Volatile
    private var lastContacts: JSONArray = JSONArray()

    @Volatile
    private var lastGroups: JSONArray = JSONArray()

    @Volatile
    private var lastContactsAtMs: Long = 0L

    @Volatile
    private var lastGroupsAtMs: Long = 0L

    fun listContacts(selfId: String = "", limit: Int = 2000): JSONArray {
        val now = System.currentTimeMillis()
        if (lastContacts.length() > 0 && now - lastContactsAtMs < REFRESH_TTL_MS) {
            return lastContacts
        }
        return try {
            // Friend cache reads are sync; run on main in case RuntimeService is UI-bound.
            val arr = TimRuntime.onMain(timeoutMs = 6_000) {
                loadContacts(selfId.trim(), limit)
            }
            if (arr.length() > 0) {
                lastContacts = arr
                lastContactsAtMs = now
                arr
            } else {
                lastContacts
            }
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "listContacts failed: ${t.message}")
            lastContacts
        }
    }

    fun listGroups(limit: Int = 500): JSONArray {
        val now = System.currentTimeMillis()
        if (lastGroups.length() > 0 && now - lastGroupsAtMs < REFRESH_TTL_MS) {
            return lastGroups
        }
        return try {
            // Must NOT await kernel callbacks on the main thread (deadlock).
            val arr = loadGroups(limit)
            if (arr.length() > 0) {
                lastGroups = arr
                lastGroupsAtMs = now
                arr
            } else {
                lastGroups
            }
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "listGroups failed: ${t.message}")
            lastGroups
        }
    }

    private fun loadContacts(selfId: String, limit: Int): JSONArray {
        val errors = ArrayList<String>()
        try {
            val viaFriends = contactsFromFriendDataService(selfId, limit)
            if (viaFriends.length() > 0) {
                Log.i(MainHook.TAG, "contacts via IFriendDataService n=${viaFriends.length()}")
                return viaFriends
            }
            errors += "FriendDataService:empty"
        } catch (t: Throwable) {
            errors += "FriendDataService:${t.message}"
        }
        try {
            val viaNt = contactsFromNtFriendsApi(selfId, limit)
            if (viaNt.length() > 0) {
                Log.i(MainHook.TAG, "contacts via IQQFriendsInfoApi n=${viaNt.length()}")
                return viaNt
            }
            errors += "IQQFriendsInfoApi:empty"
        } catch (t: Throwable) {
            errors += "IQQFriendsInfoApi:${t.message}"
        }
        Log.w(MainHook.TAG, "contacts miss: ${errors.joinToString(" | ")}")
        return JSONArray()
    }

    private fun loadGroups(limit: Int): JSONArray {
        val errors = ArrayList<String>()
        try {
            val viaKernel = groupsFromKernel(limit)
            if (viaKernel.length() > 0) {
                Log.i(MainHook.TAG, "groups via IKernelGroupService n=${viaKernel.length()}")
                return viaKernel
            }
            errors += "kernel:empty"
        } catch (t: Throwable) {
            errors += "kernel:${t.message}"
        }
        try {
            val viaDb = groupsFromTroopEntity(limit)
            if (viaDb.length() > 0) {
                Log.i(MainHook.TAG, "groups via TroopInfo entity n=${viaDb.length()}")
                return viaDb
            }
            errors += "TroopInfo:empty"
        } catch (t: Throwable) {
            errors += "TroopInfo:${t.message}"
        }
        Log.w(MainHook.TAG, "groups miss: ${errors.joinToString(" | ")}")
        return JSONArray()
    }

    private fun contactsFromFriendDataService(selfId: String, limit: Int): JSONArray {
        val svc = runtimeService("com.tencent.mobileqq.friend.api.IFriendDataService")
            ?: error("no_IFriendDataService")
        val list = invokeNoArgList(svc, "getAllFriends")
            ?: invokeBoolArgList(svc, "getAllFriends", false)
            ?: error("getAllFriends_null")
        val arr = JSONArray()
        val seen = HashSet<String>()
        for (item in list) {
            if (arr.length() >= limit) break
            if (item == null) continue
            val uin = stringField(item, "uin").ifEmpty { stringGetter(item, "getUin") }
            if (!uin.matches(UIN_RE)) continue
            if (selfId.isNotEmpty() && uin == selfId) continue
            if (!seen.add(uin)) continue
            val remark = stringField(item, "remark").ifEmpty { stringGetter(item, "getRemark") }
            val name = stringField(item, "name").ifEmpty { stringGetter(item, "getName") }
            val display = remark.ifEmpty { name }.ifEmpty { uin }
            arr.put(
                JSONObject()
                    .put(TimMsgFields.USER_ID, uin)
                    .put(TimMsgFields.DISPLAY, display),
            )
        }
        return arr
    }

    private fun contactsFromNtFriendsApi(selfId: String, limit: Int): JSONArray {
        val api = qrouteApi("com.tencent.qqnt.ntrelation.friendsinfo.api.IQQFriendsInfoApi")
            ?: error("no_IQQFriendsInfoApi")
        val getAll = api.javaClass.methods.firstOrNull {
            it.name == "getAllFriend" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java
        } ?: error("no_getAllFriend")
        @Suppress("UNCHECKED_CAST")
        val list = (getAll.invoke(api, "nexus") as? List<*>).orEmpty()
        val relation = qrouteApi("com.tencent.relation.common.api.IRelationNTUinAndUidApi")
        val arr = JSONArray()
        val seen = HashSet<String>()
        for (item in list) {
            if (arr.length() >= limit) break
            if (item == null) continue
            val uid = extractUid(item)
            val uin = uidToUin(relation, uid)
                .ifEmpty { extractUin(item) }
            if (!uin.matches(UIN_RE)) continue
            if (selfId.isNotEmpty() && uin == selfId) continue
            if (!seen.add(uin)) continue
            val display = nickOrRemark(api, uid)
                .ifEmpty { extractDisplay(item) }
                .ifEmpty { uin }
            arr.put(
                JSONObject()
                    .put(TimMsgFields.USER_ID, uin)
                    .put(TimMsgFields.DISPLAY, display),
            )
        }
        return arr
    }

    private fun groupsFromKernel(limit: Int): JSONArray {
        val kernel = runtimeService("com.tencent.qqnt.kernel.api.IKernelService")
            ?: error("no_IKernelService")
        val getGroupService = kernel.javaClass.methods.firstOrNull {
            it.name == "getGroupService" && it.parameterTypes.isEmpty()
        } ?: error("no_getGroupService")
        val groupWrapper = getGroupService.invoke(kernel) ?: error("groupService_null")
        // Java wrapper (kernel.api.q / GroupService) has getGroupList but NOT listeners.
        // Native IKernelGroupService lives in BaseService.service.
        val nativeSvc = unwrapBaseService(groupWrapper)
        val callSvc = if (hasMethod(nativeSvc, "addKernelGroupListener", 1)) nativeSvc else groupWrapper

        val listenerCl = classLoader.loadClass(
            "com.tencent.qqnt.kernel.nativeinterface.IKernelGroupListener",
        )
        val box = AtomicReference<List<*>?>(null)
        val latch = CountDownLatch(1)
        val listener = Proxy.newProxyInstance(classLoader, arrayOf(listenerCl)) { _, method, args ->
            if (method.name == "onGroupListUpdate" && args != null && args.size >= 2) {
                val list = args[1] as? List<*>
                if (list != null) {
                    box.set(list)
                    latch.countDown()
                }
            }
            null
        }
        val add = findMethod(callSvc, "addKernelGroupListener", 1)
            ?: error("no_addKernelGroupListener on ${callSvc.javaClass.name}")
        val getListTarget =
            if (findGetGroupList(groupWrapper) != null) groupWrapper else callSvc
        val getList = findGetGroupList(getListTarget) ?: error("no_getGroupList")
        val remove = findMethod(callSvc, "removeKernelGroupListener", 1)
        val cookieBox = AtomicReference(0L)
        // Register + request on main; await callback on this (IPC) thread.
        TimRuntime.onMain(timeoutMs = 3_000) {
            cookieBox.set((add.invoke(callSvc, listener) as? Number)?.toLong() ?: 0L)
            getList.invoke(getListTarget, false, operateCallback())
        }
        if (!latch.await(4, TimeUnit.SECONDS)) {
            try {
                TimRuntime.onMain(timeoutMs = 3_000) {
                    getList.invoke(getListTarget, true, operateCallback())
                }
            } catch (_: Throwable) {
            }
            latch.await(4, TimeUnit.SECONDS)
        }
        try {
            TimRuntime.onMain(timeoutMs = 2_000) {
                remove?.invoke(callSvc, cookieBox.get())
            }
        } catch (_: Throwable) {
        }
        val raw = box.get().orEmpty()
        val arr = JSONArray()
        val seen = HashSet<String>()
        for (item in raw) {
            if (arr.length() >= limit) break
            if (item == null) continue
            val code = longField(item, "groupCode")
                .ifEmpty { longField(item, "groupUin") }
                .ifEmpty { stringGetter(item, "getGroupCode") }
                .ifEmpty { stringGetter(item, "getGroupUin") }
            if (!code.matches(UIN_RE)) continue
            if (!seen.add(code)) continue
            val remark = stringField(item, "remarkName")
                .ifEmpty { stringGetter(item, "getRemarkName") }
            val name = stringField(item, "groupName")
                .ifEmpty { stringGetter(item, "getGroupName") }
            val title = remark.ifEmpty { name }.ifEmpty { code }
            arr.put(
                JSONObject()
                    .put(TimMsgFields.CHAT_ID, "troop:$code")
                    .put(TimMsgFields.TITLE, title)
                    .put(TimMsgFields.IS_GROUP, true),
            )
        }
        return arr
    }

    private fun groupsFromTroopEntity(limit: Int): JSONArray {
        val manage = runtimeService("com.tencent.mobileqq.troopmanage.api.ITroopManageService")
            ?: error("no_ITroopManageService")
        val factory = manage.javaClass.methods.firstOrNull {
            it.name == "getQQEntityManagerFactory" && it.parameterTypes.isEmpty()
        }?.invoke(manage) ?: error("no_EntityManagerFactory")
        val createEm = factory.javaClass.methods.first {
            it.name == "createEntityManager" && it.parameterTypes.isEmpty()
        }
        val em = createEm.invoke(factory) ?: error("em_null")
        val troopCl = classLoader.loadClass("com.tencent.mobileqq.data.troop.TroopInfo")
        val query = em.javaClass.methods.firstOrNull {
            it.name == "query" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Class::class.java
        } ?: error("no_em_query")
        @Suppress("UNCHECKED_CAST")
        val list = (query.invoke(em, troopCl) as? List<*>).orEmpty()
        try {
            em.javaClass.methods.firstOrNull {
                it.name == "close" && it.parameterTypes.isEmpty()
            }?.invoke(em)
        } catch (_: Throwable) {
        }
        val arr = JSONArray()
        val seen = HashSet<String>()
        for (item in list) {
            if (arr.length() >= limit) break
            if (item == null) continue
            val uin = stringField(item, "troopuin")
                .ifEmpty { stringField(item, "troopcode") }
            if (!uin.matches(UIN_RE)) continue
            if (!seen.add(uin)) continue
            val remark = stringField(item, "troopRemark")
            val name = stringField(item, "troopname")
                .ifEmpty { stringField(item, "troopNameFromNT") }
            val title = remark.ifEmpty { name }.ifEmpty { uin }
            arr.put(
                JSONObject()
                    .put(TimMsgFields.CHAT_ID, "troop:$uin")
                    .put(TimMsgFields.TITLE, title)
                    .put(TimMsgFields.IS_GROUP, true),
            )
        }
        return arr
    }

    /** Pull native kernel service out of QQNT BaseService wrapper. */
    private fun unwrapBaseService(wrapper: Any): Any {
        var cur: Class<*>? = wrapper.javaClass
        while (cur != null && cur != Any::class.java) {
            try {
                val f = cur.getDeclaredField("service")
                f.isAccessible = true
                val native = f.get(wrapper)
                if (native != null) {
                    Log.i(
                        MainHook.TAG,
                        "unwrap group service wrapper=${wrapper.javaClass.name} native=${native.javaClass.name}",
                    )
                    return native
                }
            } catch (_: Throwable) {
            }
            cur = cur.superclass
        }
        return wrapper
    }

    private fun hasMethod(obj: Any, name: String, paramCount: Int): Boolean =
        findMethod(obj, name, paramCount) != null

    private fun findMethod(obj: Any, name: String, paramCount: Int): java.lang.reflect.Method? {
        return obj.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == paramCount
        }
    }

    private fun findGetGroupList(obj: Any): java.lang.reflect.Method? {
        return obj.javaClass.methods.firstOrNull { m ->
            if (m.name != "getGroupList" || m.parameterTypes.size != 2) return@firstOrNull false
            val p0 = m.parameterTypes[0]
            p0 == Boolean::class.javaPrimitiveType || p0 == java.lang.Boolean::class.java
        }
    }

    private fun runtimeService(apiClassName: String): Any? {
        val app = TimRuntime.qqAppInterface(classLoader) ?: return null
        val apiCl = classLoader.loadClass(apiClassName)
        for (m in app.javaClass.methods) {
            if (m.name != "getRuntimeService") continue
            try {
                m.isAccessible = true
                val p = m.parameterTypes
                val v = when {
                    p.size == 2 &&
                        p[0] == Class::class.java &&
                        p[1] == String::class.java -> m.invoke(app, apiCl, "")
                    p.size == 1 && p[0] == Class::class.java -> m.invoke(app, apiCl)
                    else -> continue
                }
                if (v != null) return v
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun qrouteApi(apiClassName: String): Any? {
        return try {
            val qroute = classLoader.loadClass("com.tencent.mobileqq.qroute.QRoute")
            val apiCl = classLoader.loadClass(apiClassName)
            val m = qroute.methods.first {
                it.name == "api" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Class::class.java
            }
            m.invoke(null, apiCl)
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "QRoute.api($apiClassName) failed: ${t.message}")
            null
        }
    }

    private fun operateCallback(): Any {
        val cbCl = classLoader.loadClass("com.tencent.qqnt.kernel.nativeinterface.IOperateCallback")
        return Proxy.newProxyInstance(classLoader, arrayOf(cbCl)) { _, method, args ->
            Log.i(MainHook.TAG, "dir IOperateCallback.${method.name} args=${args?.joinToString()}")
            null
        }
    }

    private fun invokeNoArgList(svc: Any, name: String): List<*>? {
        val m = svc.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.isEmpty()
        } ?: return null
        @Suppress("UNCHECKED_CAST")
        return m.invoke(svc) as? List<*>
    }

    private fun invokeBoolArgList(svc: Any, name: String, arg: Boolean): List<*>? {
        val m = svc.javaClass.methods.firstOrNull {
            it.name == name &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        } ?: return null
        @Suppress("UNCHECKED_CAST")
        return m.invoke(svc, arg) as? List<*>
    }

    private fun uidToUin(relation: Any?, uid: String): String {
        if (relation == null || uid.isEmpty()) return ""
        for (name in listOf("getFriendUinFromUid", "getUinFromUid")) {
            try {
                val m = relation.javaClass.methods.firstOrNull {
                    it.name == name &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.returnType == String::class.java
                } ?: continue
                val uin = (m.invoke(relation, uid) as? String)?.trim().orEmpty()
                if (uin.matches(UIN_RE)) return uin
            } catch (_: Throwable) {
            }
        }
        return ""
    }

    private fun nickOrRemark(api: Any, uid: String): String {
        if (uid.isEmpty()) return ""
        for (name in listOf("getRemarkWithUid", "getNickWithUid")) {
            try {
                val m = api.javaClass.methods.firstOrNull {
                    it.name == name &&
                        it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.parameterTypes[1] == String::class.java &&
                        it.returnType == String::class.java
                } ?: continue
                val v = (m.invoke(api, uid, "nexus") as? String)?.trim().orEmpty()
                if (v.isNotEmpty()) return v
            } catch (_: Throwable) {
            }
        }
        return ""
    }

    private fun extractUid(item: Any): String {
        for (name in listOf("uid", "mUid", "buddyUid")) {
            val v = stringField(item, name)
            if (v.startsWith("u_")) return v
        }
        for (m in item.javaClass.methods) {
            if (m.parameterTypes.isNotEmpty() || m.returnType != String::class.java) continue
            try {
                val v = (m.invoke(item) as? String)?.trim().orEmpty()
                if (v.startsWith("u_")) return v
            } catch (_: Throwable) {
            }
        }
        // obfuscated FriendsSimpleInfo: scan String fields
        var cur: Class<*>? = item.javaClass
        while (cur != null && cur != Any::class.java) {
            for (f in cur.declaredFields) {
                if (f.type != String::class.java) continue
                try {
                    f.isAccessible = true
                    val v = (f.get(item) as? String)?.trim().orEmpty()
                    if (v.startsWith("u_")) return v
                } catch (_: Throwable) {
                }
            }
            cur = cur.superclass
        }
        return ""
    }

    private fun extractUin(item: Any): String {
        for (name in listOf("uin", "mUin", "buddyUin")) {
            val v = stringField(item, name)
            if (v.matches(UIN_RE)) return v
        }
        var cur: Class<*>? = item.javaClass
        while (cur != null && cur != Any::class.java) {
            for (f in cur.declaredFields) {
                if (f.type != String::class.java) continue
                try {
                    f.isAccessible = true
                    val v = (f.get(item) as? String)?.trim().orEmpty()
                    if (v.matches(UIN_RE)) return v
                } catch (_: Throwable) {
                }
            }
            cur = cur.superclass
        }
        return ""
    }

    private fun extractDisplay(item: Any): String {
        for (name in listOf("remark", "nick", "name", "nickname")) {
            val v = stringField(item, name)
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    private fun stringField(obj: Any, name: String): String {
        var cur: Class<*>? = obj.javaClass
        while (cur != null && cur != Any::class.java) {
            try {
                val f = cur.getDeclaredField(name)
                f.isAccessible = true
                return when (val v = f.get(obj)) {
                    null -> ""
                    is String -> v.trim()
                    is Number -> v.toString()
                    else -> v.toString().trim()
                }
            } catch (_: Throwable) {
            }
            cur = cur.superclass
        }
        return ""
    }

    private fun longField(obj: Any, name: String): String {
        var cur: Class<*>? = obj.javaClass
        while (cur != null && cur != Any::class.java) {
            try {
                val f = cur.getDeclaredField(name)
                f.isAccessible = true
                val v = f.get(obj) ?: return ""
                val s = when (v) {
                    is Long -> v.toString()
                    is Int -> v.toString()
                    is Number -> v.toLong().toString()
                    else -> v.toString()
                }.trim()
                return if (s == "0") "" else s
            } catch (_: Throwable) {
            }
            cur = cur.superclass
        }
        return ""
    }

    private fun stringGetter(obj: Any, name: String): String {
        return try {
            val m = obj.javaClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            } ?: return ""
            when (val v = m.invoke(obj)) {
                null -> ""
                is String -> v.trim()
                is Number -> {
                    val s = v.toLong().toString()
                    if (s == "0") "" else s
                }
                else -> v.toString().trim()
            }
        } catch (_: Throwable) {
            ""
        }
    }

    companion object {
        private val UIN_RE = Regex("\\d{5,12}")
        /** Avoid blocking HELLO refresh every few seconds on slow kernel group fetch. */
        private const val REFRESH_TTL_MS = 60_000L
    }
}
