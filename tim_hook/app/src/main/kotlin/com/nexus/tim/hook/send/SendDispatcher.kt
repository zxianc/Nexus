package com.nexus.tim.hook.send

import android.content.Context
import android.util.Base64
import android.util.Log
import com.nexus.tim.hook.MainHook
import com.nexus.tim.hook.runtime.TimRuntime
import com.nexus.tim.hook.state.ContactDirectory
import com.nexus.tim.protocol.ImageSendOptions
import com.nexus.tim.protocol.TimGroupAt
import com.nexus.tim.protocol.TimMsgFields
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.HashMap
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * TIM 4.1.0 uses QQNT. ChatActivityFacade.H0 → J0 is a stub (returns empty [J]),
 * so classic Facade/MessageRecord paths never deliver.
 *
 * Real path:
 *   QRoute.api(IMsgUtilApi).createTextElement(text)
 *   QRoute.api(IMsgService).addSendMsg / sendMsg(Contact, elements, …)
 * Contact: chatType 1=C2C, 2=Group; C2C peerUid must be NT uid (via IRelationNTUinAndUidApi).
 *
 * Group @: createAtTextElement + patch TextElement.atUid (uin). NapCat uses
 * atNtUid/atUid="all" for @全体成员.
 */
class SendDispatcher(
    private val classLoader: ClassLoader,
    private val appContext: Context?,
    private val contacts: ContactDirectory? = null,
) {
    fun sendText(req: JSONObject): JSONObject {
        val requestId = req.optString(TimMsgFields.REQUEST_ID, "")
        val chatId = req.optString(TimMsgFields.CHAT_ID, "")
        val text = req.optString(TimMsgFields.TEXT, "")
        if (chatId.isEmpty() || text.isEmpty()) {
            return fail(requestId, "missing_fields")
        }
        val ats = parseAts(req.optJSONArray(TimMsgFields.ATS))
        val (uinType, peer) = parseChatId(chatId)
        if (peer.isEmpty()) {
            return fail(requestId, "bad_chat_id")
        }
        val ctx = appContext ?: return fail(requestId, "no_context")
        return try {
            TimRuntime.onMain {
                val errors = ArrayList<String>()
                try {
                    sendViaNt(peer, uinType, text, ats)
                    return@onMain ok(requestId, "nt")
                } catch (t: Throwable) {
                    errors += "nt:${root(t)}"
                    Log.w(MainHook.TAG, "NT send failed", t)
                }
                val selfUin = LoginUin.self(classLoader, ctx)
                val app = TimRuntime.qqAppInterface(classLoader)
                if (app != null && selfUin.isNotEmpty()) {
                    try {
                        sendViaChatActivityFacade(app, ctx, selfUin, peer, uinType, text)
                        return@onMain ok(requestId, "H0-stub")
                    } catch (t: Throwable) {
                        errors += "H0:${root(t)}"
                    }
                }
                fail(requestId, errors.joinToString(" | "))
            }
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "sendText failed", t)
            fail(requestId, "send_failed:${root(t)}")
        }
    }

    fun sendImage(req: JSONObject): JSONObject {
        val requestId = req.optString(TimMsgFields.REQUEST_ID, "")
        val chatId = req.optString(TimMsgFields.CHAT_ID, "")
        val path = req.optString(TimMsgFields.PATH, "")
        val name = req.optString(TimMsgFields.NAME, "image.jpg")
        val dataB64 = req.optString(TimMsgFields.DATA_B64, "")
        val original = if (req.has(TimMsgFields.ORIGINAL)) {
            val v = req.opt(TimMsgFields.ORIGINAL)
            if (v is Boolean) v else ImageSendOptions.parseOriginal(v?.toString())
        } else {
            true
        }
        if (chatId.isEmpty()) {
            return fail(requestId, "missing_fields")
        }
        val (uinType, peer) = parseChatId(chatId)
        if (peer.isEmpty()) {
            return fail(requestId, "bad_chat_id")
        }
        val localPath = materializePath(path, dataB64, name)
            ?: return fail(requestId, "file_missing")
        return try {
            TimRuntime.onMain {
                sendImageViaNt(peer, uinType, localPath, original)
                ok(requestId, "img")
            }
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "sendImage failed", t)
            fail(requestId, "send_failed:${root(t)}")
        }
    }

    private fun materializePath(path: String, dataB64: String, name: String): String? {
        if (path.isNotEmpty()) {
            val f = File(path)
            if (f.isFile && f.length() > 0L) return f.absolutePath
        }
        if (dataB64.isEmpty()) return null
        val safe = name.replace(Regex("[^A-Za-z0-9._\\-]"), "_").ifEmpty { "image.jpg" }
        val leaf = "${UUID.randomUUID()}_$safe"
        val dirs = mutableListOf<File>()
        appContext?.cacheDir?.let { dirs.add(File(it, "nexus_tim_out")) }
        dirs.add(File("/data/local/tmp/nexus_tim/out"))
        for (dir in dirs) {
            try {
                dir.mkdirs()
                val dest = File(dir, leaf)
                dest.writeBytes(Base64.decode(dataB64, Base64.DEFAULT))
                if (dest.isFile && dest.length() > 0) return dest.absolutePath
            } catch (t: Throwable) {
                Log.w(MainHook.TAG, "materialize failed dir=${dir.absolutePath}: ${t.message}")
            }
        }
        return null
    }

    private fun sendImageViaNt(peer: String, uinType: Int, path: String, original: Boolean) {
        val chatType = if (uinType == UIN_TYPE_TROOP) CHAT_TYPE_GROUP else CHAT_TYPE_C2C
        val peerUid = resolvePeerUid(peer, chatType)
        val compressType = ImageSendOptions.compressType(original)
        Log.i(
            MainHook.TAG,
            "NT sendImage chatType=$chatType peer=$peer peerUid=$peerUid " +
                "original=$original compressType=$compressType path=$path",
        )

        val contactCl = classLoader.loadClass("com.tencent.qqnt.kernelpublic.nativeinterface.Contact")
        val contact = contactCl
            .getConstructor(Int::class.javaPrimitiveType, String::class.java, String::class.java)
            .newInstance(chatType, peerUid, "")

        val util = qrouteApi("com.tencent.qqnt.msg.api.IMsgUtilApi")
            ?: error("no_IMsgUtilApi")
        val createPic = util.javaClass.methods.firstOrNull {
            it.name == "createPicElement" &&
                it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                it.parameterTypes[2] == Int::class.javaPrimitiveType
        } ?: error("no_createPicElement_String_ZI")
        // APK: createPicElement(Ljava/lang/String;ZI) — try original=true, compressType=0 first.
        val picEl = createPic.invoke(util, path, original, compressType)
            ?: error("createPicElement_null")
        val elements = ArrayList<Any>()
        elements.add(picEl)

        val msgService = qrouteApi("com.tencent.qqnt.msg.api.IMsgService")
            ?: error("no_IMsgService")
        val callback = operateCallback()
        val errors = ArrayList<String>()
        for (m in msgService.javaClass.methods.filter { it.name == "sendMsg" }) {
            val p = m.parameterTypes
            try {
                when {
                    p.size == 3 &&
                        p[0].name.contains("Contact") &&
                        MutableList::class.java.isAssignableFrom(p[1]) &&
                        p[2].name.contains("IOperateCallback") -> {
                        m.invoke(msgService, contact, elements, callback)
                        Log.i(MainHook.TAG, "sendImage via IMsgService.sendMsg(contact,list,cb)")
                        return
                    }
                    p.size == 4 &&
                        p[0].name.contains("Contact") &&
                        MutableList::class.java.isAssignableFrom(p[1]) &&
                        Map::class.java.isAssignableFrom(p[2]) -> {
                        m.invoke(msgService, contact, elements, HashMap<Any, Any>(), callback)
                        Log.i(MainHook.TAG, "sendImage via IMsgService.sendMsg(contact,list,map,cb)")
                        return
                    }
                    p.size == 4 &&
                        p[0].name.contains("Contact") &&
                        p[1] == Long::class.javaPrimitiveType &&
                        MutableList::class.java.isAssignableFrom(p[2]) -> {
                        m.invoke(msgService, contact, 0L, elements, callback)
                        Log.i(MainHook.TAG, "sendImage via IMsgService.sendMsg(contact,0,list,cb)")
                        return
                    }
                }
            } catch (t: Throwable) {
                errors += "${m.name}/${p.size}:${root(t)}"
            }
        }
        error(errors.joinToString(" | ").ifEmpty { "no_nt_send_method" })
    }

    private fun parseAts(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            out.add(arr.optString(i, ""))
        }
        return TimGroupAt.normalizeAts(out)
    }

    private fun sendViaNt(peer: String, uinType: Int, text: String, ats: List<String>) {
        val chatType = if (uinType == UIN_TYPE_TROOP) CHAT_TYPE_GROUP else CHAT_TYPE_C2C
        val peerUid = resolvePeerUid(peer, chatType)
        Log.i(MainHook.TAG, "NT send chatType=$chatType peer=$peer peerUid=$peerUid ats=${ats.size}")

        val contactCl = classLoader.loadClass("com.tencent.qqnt.kernelpublic.nativeinterface.Contact")
        val contact = contactCl
            .getConstructor(Int::class.javaPrimitiveType, String::class.java, String::class.java)
            .newInstance(chatType, peerUid, "")

        val util = qrouteApi("com.tencent.qqnt.msg.api.IMsgUtilApi")
            ?: error("no_IMsgUtilApi")
        val createText = util.javaClass.methods.first {
            it.name == "createTextElement" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java
        }
        val createAt = util.javaClass.methods.firstOrNull {
            it.name == "createAtTextElement" &&
                it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java &&
                it.parameterTypes[2] == Int::class.javaPrimitiveType
        }

        val elements = ArrayList<Any>()
        val groupAts = if (chatType == CHAT_TYPE_GROUP) ats else emptyList()
        if (groupAts.isNotEmpty() && createAt != null) {
            val (atAll, atOne) = loadAtTypes()
            val memberDisplays = memberDisplayMap(peer)
            for (at in groupAts) {
                val el = if (at.equals(TimGroupAt.NOTIFY_ALL, ignoreCase = true)) {
                    // NapCat: content=@全体成员, atUid/atNtUid="all"
                    buildAtElement(
                        createAt,
                        util,
                        content = "@全体成员",
                        atNtUid = "all",
                        atUin = 0L,
                        atType = atAll,
                    )
                } else {
                    val uid = resolveMemberUid(at, troopUin = peer)
                    if (uid.isEmpty()) {
                        Log.w(MainHook.TAG, "at uin2uid miss, skip uin=$at")
                        continue
                    }
                    val display = memberDisplays[at].orEmpty().ifEmpty { at }
                    val uinLong = at.toLongOrNull() ?: 0L
                    buildAtElement(
                        createAt,
                        util,
                        content = "@$display",
                        atNtUid = uid,
                        atUin = uinLong,
                        atType = atOne,
                    )
                } ?: continue
                elements.add(el)
                Log.i(MainHook.TAG, "at element added type=${at} el=${el.javaClass.simpleName}")
            }
            if (elements.isEmpty()) {
                Log.w(MainHook.TAG, "all ats skipped; sending plain text only")
            }
        } else if (groupAts.isNotEmpty() && createAt == null) {
            Log.w(MainHook.TAG, "createAtTextElement missing; sending plain text")
        }

        val textEl = createText.invoke(util, text)
            ?: error("createTextElement_null")
        elements.add(textEl)
        Log.i(MainHook.TAG, "NT elements=${elements.size} (ats=${groupAts.size})")

        val msgService = qrouteApi("com.tencent.qqnt.msg.api.IMsgService")
            ?: error("no_IMsgService")

        // addSendMsg only stages locally (UI spinner); real outbox is sendMsg → kernel.
        val callback = operateCallback()
        val errors = ArrayList<String>()
        for (m in msgService.javaClass.methods.filter { it.name == "sendMsg" }) {
            val p = m.parameterTypes
            try {
                when {
                    p.size == 3 &&
                        p[0].name.contains("Contact") &&
                        MutableList::class.java.isAssignableFrom(p[1]) &&
                        p[2].name.contains("IOperateCallback") -> {
                        m.invoke(msgService, contact, elements, callback)
                        Log.i(MainHook.TAG, "send via IMsgService.sendMsg(contact,list,cb)")
                        return
                    }
                    p.size == 4 &&
                        p[0].name.contains("Contact") &&
                        MutableList::class.java.isAssignableFrom(p[1]) &&
                        Map::class.java.isAssignableFrom(p[2]) -> {
                        m.invoke(msgService, contact, elements, HashMap<Any, Any>(), callback)
                        Log.i(MainHook.TAG, "send via IMsgService.sendMsg(contact,list,map,cb)")
                        return
                    }
                    p.size == 4 &&
                        p[0].name.contains("Contact") &&
                        p[1] == Long::class.javaPrimitiveType &&
                        MutableList::class.java.isAssignableFrom(p[2]) -> {
                        m.invoke(msgService, contact, 0L, elements, callback)
                        Log.i(MainHook.TAG, "send via IMsgService.sendMsg(contact,0,list,cb)")
                        return
                    }
                }
            } catch (t: Throwable) {
                errors += "${m.name}/${p.size}:${root(t)}"
            }
        }
        error(errors.joinToString(" | ").ifEmpty { "no_nt_send_method" })
    }

    private fun loadAtTypes(): Pair<Int, Int> {
        return try {
            val cl = classLoader.loadClass("com.tencent.qqnt.kernel.nativeinterface.MsgConstant")
            val all = intConst(cl, "ATTYPEALL", FALLBACK_ATTYPE_ALL)
            val one = intConst(cl, "ATTYPEONE", FALLBACK_ATTYPE_ONE)
            Log.i(MainHook.TAG, "MsgConstant ATTYPEALL=$all ATTYPEONE=$one")
            all to one
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "MsgConstant ATTYPE fallback: ${t.message}")
            FALLBACK_ATTYPE_ALL to FALLBACK_ATTYPE_ONE
        }
    }

    private fun intConst(cl: Class<*>, name: String, fallback: Int): Int {
        return try {
            val f = cl.getField(name)
            f.getInt(null)
        } catch (_: Throwable) {
            try {
                val f = cl.getDeclaredField(name)
                f.isAccessible = true
                f.getInt(null)
            } catch (_: Throwable) {
                fallback
            }
        }
    }

    /**
     * createAtTextElement only fills content/atNtUid/atType — NapCat also needs atUid=uin.
     */
    private fun buildAtElement(
        createAt: java.lang.reflect.Method,
        util: Any,
        content: String,
        atNtUid: String,
        atUin: Long,
        atType: Int,
    ): Any? {
        val el = createAt.invoke(util, content, atNtUid, atType) ?: return null
        try {
            val textEl = el.javaClass.methods.firstOrNull {
                it.name == "getTextElement" && it.parameterTypes.isEmpty()
            }?.invoke(el) ?: run {
                var cur: Class<*>? = el.javaClass
                var fieldVal: Any? = null
                while (cur != null && cur != Any::class.java) {
                    try {
                        val f = cur.getDeclaredField("textElement")
                        f.isAccessible = true
                        fieldVal = f.get(el)
                        break
                    } catch (_: Throwable) {
                    }
                    cur = cur.superclass
                }
                fieldVal
            }
            if (textEl != null) {
                // Ensure fields match NapCat SendTextElement.at shape.
                setStringProp(textEl, "content", content)
                setStringProp(textEl, "atNtUid", atNtUid)
                setIntProp(textEl, "atType", atType)
                setLongProp(textEl, "atUid", atUin)
                Log.i(
                    MainHook.TAG,
                    "at patched content=$content atNtUid=$atNtUid atUid=$atUin atType=$atType",
                )
            }
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "at patch failed: ${t.message}")
        }
        return el
    }

    private fun memberDisplayMap(troopUin: String): Map<String, String> {
        val dir = contacts ?: return emptyMap()
        return try {
            // Peek only — send path runs on main; must not call listMembers (kernel await).
            val arr = dir.peekMembersCached("troop:$troopUin")
            val out = HashMap<String, String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString(TimMsgFields.USER_ID, "")
                val display = o.optString(TimMsgFields.DISPLAY, "")
                if (id.isNotEmpty() && display.isNotEmpty()) out[id] = display
            }
            out
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "memberDisplayMap failed: ${t.message}")
            emptyMap()
        }
    }

    private fun resolveMemberUid(uin: String, troopUin: String = ""): String {
        if (uin.startsWith("u_")) return uin
        val api = qrouteApi("com.tencent.relation.common.api.IRelationNTUinAndUidApi")
        if (api != null) {
            for (name in listOf("getFriendUidFromUin", "getUidFromUin")) {
                try {
                    val m = api.javaClass.methods.firstOrNull {
                        it.name == name &&
                            it.parameterTypes.size == 1 &&
                            it.parameterTypes[0] == String::class.java &&
                            it.returnType == String::class.java
                    } ?: continue
                    val uid = (m.invoke(api, uin) as? String)?.trim().orEmpty()
                    if (uid.isNotEmpty()) {
                        Log.i(MainHook.TAG, "at uin2uid $name: $uin -> $uid")
                        return uid
                    }
                } catch (t: Throwable) {
                    Log.w(MainHook.TAG, "member uin2uid $name failed: ${t.message}")
                }
            }
        }
        // Group kernel: getUidByUins(ArrayList, callback) — needed when not in friend cache.
        if (troopUin.isNotEmpty()) {
            val viaGroup = resolveUidViaGroupService(uin)
            if (viaGroup.isNotEmpty()) {
                Log.i(MainHook.TAG, "at uin2uid groupService: $uin -> $viaGroup")
                return viaGroup
            }
        }
        Log.w(MainHook.TAG, "at uin2uid miss uin=$uin troop=$troopUin")
        return ""
    }

    private fun resolveUidViaGroupService(uin: String): String {
        return try {
            val kernel = runtimeService("com.tencent.qqnt.kernel.api.IKernelService") ?: return ""
            val groupSvc = kernel.javaClass.methods.firstOrNull {
                it.name == "getGroupService" && it.parameterTypes.isEmpty()
            }?.invoke(kernel) ?: return ""
            val native = unwrapBaseService(groupSvc)
            val target = if (hasMethod(native, "getUidByUins", 2)) native else groupSvc
            val m = target.javaClass.methods.firstOrNull {
                it.name == "getUidByUins" && it.parameterTypes.size == 2
            } ?: return ""
            val cbCl = classLoader.loadClass(
                "com.tencent.qqnt.kernel.nativeinterface.IGroupMemberUidCallback",
            )
            val box = AtomicReference<String>("")
            val latch = CountDownLatch(1)
            val cb = Proxy.newProxyInstance(classLoader, arrayOf(cbCl)) { _, method, args ->
                if (method.name.startsWith("on") && args != null) {
                    for (a in args) {
                        when (a) {
                            is Map<*, *> -> {
                                val v = a[uin]?.toString()?.trim().orEmpty()
                                    .ifEmpty { a.values.firstOrNull()?.toString()?.trim().orEmpty() }
                                if (v.startsWith("u_")) box.set(v)
                            }
                        }
                    }
                    latch.countDown()
                }
                null
            }
            val uins = ArrayList<Any>()
            // API may want Long or String uins
            val p0 = m.parameterTypes[0]
            if (List::class.java.isAssignableFrom(p0)) {
                uins.add(uin.toLongOrNull() ?: uin)
                m.invoke(target, uins, cb)
            } else {
                return ""
            }
            latch.await(3, TimeUnit.SECONDS)
            box.get()
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "resolveUidViaGroupService failed: ${t.message}")
            ""
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
                    p.size == 2 && p[0] == Class::class.java && p[1] == String::class.java ->
                        m.invoke(app, apiCl, "")
                    p.size == 1 && p[0] == Class::class.java -> m.invoke(app, apiCl)
                    else -> continue
                }
                if (v != null) return v
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun unwrapBaseService(wrapper: Any): Any {
        var cur: Class<*>? = wrapper.javaClass
        while (cur != null && cur != Any::class.java) {
            try {
                val f = cur.getDeclaredField("service")
                f.isAccessible = true
                val native = f.get(wrapper)
                if (native != null) return native
            } catch (_: Throwable) {
            }
            cur = cur.superclass
        }
        return wrapper
    }

    private fun hasMethod(obj: Any, name: String, paramCount: Int): Boolean =
        obj.javaClass.methods.any { it.name == name && it.parameterTypes.size == paramCount }

    private fun setStringProp(obj: Any, name: String, value: String) {
        try {
            val setter = obj.javaClass.methods.firstOrNull {
                it.name.equals("set${name.replaceFirstChar { c -> c.uppercase() }}", true) &&
                    it.parameterTypes.size == 1
            }
            if (setter != null) {
                setter.invoke(obj, value)
                return
            }
        } catch (_: Throwable) {
        }
        var cur: Class<*>? = obj.javaClass
        while (cur != null && cur != Any::class.java) {
            try {
                val f = cur.getDeclaredField(name)
                f.isAccessible = true
                f.set(obj, value)
                return
            } catch (_: Throwable) {
            }
            cur = cur.superclass
        }
    }

    private fun setIntProp(obj: Any, name: String, value: Int) {
        try {
            val setter = obj.javaClass.methods.firstOrNull {
                it.name.equals("set${name.replaceFirstChar { c -> c.uppercase() }}", true) &&
                    it.parameterTypes.size == 1
            }
            if (setter != null) {
                setter.invoke(obj, value)
                return
            }
        } catch (_: Throwable) {
        }
        var cur: Class<*>? = obj.javaClass
        while (cur != null && cur != Any::class.java) {
            try {
                val f = cur.getDeclaredField(name)
                f.isAccessible = true
                f.setInt(obj, value)
                return
            } catch (_: Throwable) {
            }
            cur = cur.superclass
        }
    }

    private fun setLongProp(obj: Any, name: String, value: Long) {
        try {
            val setter = obj.javaClass.methods.firstOrNull {
                it.name.equals("set${name.replaceFirstChar { c -> c.uppercase() }}", true) &&
                    it.parameterTypes.size == 1
            }
            if (setter != null) {
                val p = setter.parameterTypes[0]
                when {
                    p == Long::class.javaPrimitiveType || p == java.lang.Long::class.java ->
                        setter.invoke(obj, value)
                    p == String::class.java -> setter.invoke(obj, value.toString())
                    else -> setter.invoke(obj, value)
                }
                return
            }
        } catch (_: Throwable) {
        }
        var cur: Class<*>? = obj.javaClass
        while (cur != null && cur != Any::class.java) {
            try {
                val f = cur.getDeclaredField(name)
                f.isAccessible = true
                when (f.type) {
                    Long::class.javaPrimitiveType, java.lang.Long::class.java -> f.setLong(obj, value)
                    String::class.java -> f.set(obj, value.toString())
                    else -> f.set(obj, value)
                }
                return
            } catch (_: Throwable) {
            }
            cur = cur.superclass
        }
    }

    private fun resolvePeerUid(peer: String, chatType: Int): String {
        if (chatType == CHAT_TYPE_GROUP) return peer
        if (peer.startsWith("u_")) return peer
        val api = qrouteApi("com.tencent.relation.common.api.IRelationNTUinAndUidApi")
            ?: return peer
        for (name in listOf("getFriendUidFromUin", "getUidFromUin")) {
            try {
                val m = api.javaClass.methods.firstOrNull {
                    it.name == name &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.returnType == String::class.java
                } ?: continue
                val uid = (m.invoke(api, peer) as? String)?.trim().orEmpty()
                if (uid.isNotEmpty()) {
                    Log.i(MainHook.TAG, "uin2uid via $name: $peer -> $uid")
                    return uid
                }
            } catch (t: Throwable) {
                Log.w(MainHook.TAG, "uin2uid $name failed: ${t.message}")
            }
        }
        Log.w(MainHook.TAG, "uin2uid miss, fallback peer=$peer")
        return peer
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
            Log.i(MainHook.TAG, "IOperateCallback.${method.name} args=${args?.joinToString()}")
            null
        }
    }

    private fun sendViaChatActivityFacade(
        app: Any,
        ctx: Context,
        selfUin: String,
        peer: String,
        uinType: Int,
        text: String,
    ) {
        val session = buildSession(selfUin, peer, uinType) ?: error("session_failed")
        val facade = classLoader.loadClass("com.tencent.mobileqq.activity.ChatActivityFacade")
        for (name in listOf("H0", "I0")) {
            for (m in facade.declaredMethods.filter { it.name == name }) {
                try {
                    m.isAccessible = true
                    val params = m.parameterTypes
                    val args: Array<Any?> = when {
                        params.size == 4 &&
                            params[1].isAssignableFrom(Context::class.java) &&
                            params[3] == String::class.java ->
                            arrayOf(app, ctx, session, text)
                        params.size == 5 &&
                            params[1].isAssignableFrom(Context::class.java) &&
                            params[3] == String::class.java ->
                            arrayOf(app, ctx, session, text, ArrayList<Any>())
                        else -> continue
                    }
                    m.invoke(null, *args)
                    Log.i(MainHook.TAG, "send via ChatActivityFacade.$name (likely stub)")
                    return
                } catch (_: Throwable) {
                }
            }
        }
        error("no_caf_method")
    }

    private fun buildSession(selfUin: String, peer: String, uinType: Int): Any? {
        return try {
            val cl = classLoader.loadClass("com.tencent.mobileqq.activity.aio.SessionInfo")
            val session = cl.getDeclaredConstructor().newInstance()
            setIntField(session, listOf("d"), uinType)
            setStringField(session, listOf("e"), peer)
            setStringFieldOptional(session, listOf("A"), selfUin)
            session
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "buildSession failed: ${t.message}")
            null
        }
    }

    private fun parseChatId(chatId: String): Pair<Int, String> {
        val raw = chatId.trim()
        return when {
            raw.startsWith("troop:", ignoreCase = true) ->
                UIN_TYPE_TROOP to raw.substringAfter(':').trim()
            raw.startsWith("g:", ignoreCase = true) ->
                UIN_TYPE_TROOP to raw.substringAfter(':').trim()
            raw.endsWith("@chatroom", ignoreCase = true) ->
                UIN_TYPE_TROOP to raw.removeSuffix("@chatroom").trim()
            else -> UIN_TYPE_FRIEND to raw
        }
    }

    private fun setIntField(obj: Any, names: List<String>, value: Int) {
        for (name in names) {
            var cur: Class<*>? = obj.javaClass
            while (cur != null && cur != Any::class.java) {
                try {
                    val f = cur.getDeclaredField(name)
                    if (f.type == Int::class.javaPrimitiveType || f.type == Integer::class.java) {
                        f.isAccessible = true
                        f.setInt(obj, value)
                        return
                    }
                } catch (_: Throwable) {
                }
                cur = cur.superclass
            }
        }
        error("int_field_missing")
    }

    private fun setStringField(obj: Any, names: List<String>, value: String) {
        for (name in names) {
            var cur: Class<*>? = obj.javaClass
            while (cur != null && cur != Any::class.java) {
                try {
                    val f = cur.getDeclaredField(name)
                    if (f.type == String::class.java) {
                        f.isAccessible = true
                        f.set(obj, value)
                        return
                    }
                } catch (_: Throwable) {
                }
                cur = cur.superclass
            }
        }
        error("string_field_missing")
    }

    private fun setStringFieldOptional(obj: Any, names: List<String>, value: String) {
        try {
            setStringField(obj, names, value)
        } catch (_: Throwable) {
        }
    }

    private fun ok(requestId: String, via: String) = JSONObject()
        .put(TimMsgFields.REQUEST_ID, requestId)
        .put(TimMsgFields.OK, true)
        .put(TimMsgFields.MSG_ID, "tim-$via")

    private fun fail(requestId: String, error: String) = JSONObject()
        .put(TimMsgFields.REQUEST_ID, requestId)
        .put(TimMsgFields.OK, false)
        .put(TimMsgFields.ERROR, error)

    private fun root(t: Throwable): String =
        (generateSequence(t) { it.cause }.lastOrNull() ?: t).let {
            "${it.javaClass.simpleName}:${it.message}"
        }

    companion object {
        const val UIN_TYPE_FRIEND = 0
        const val UIN_TYPE_TROOP = 1
        const val CHAT_TYPE_C2C = 1
        const val CHAT_TYPE_GROUP = 2
        /** QQNT MsgConstant common values (verified via reflection when available). */
        const val FALLBACK_ATTYPE_ALL = 1
        const val FALLBACK_ATTYPE_ONE = 2
    }
}

/** Resolve self uin without depending on Bridge HELLO. */
object LoginUin {
    fun self(cl: ClassLoader, ctx: Context): String {
        try {
            val snap = com.nexus.tim.hook.state.LoginProbe().probe(ctx)
            if (snap.userId.isNotBlank()) return snap.userId
        } catch (_: Throwable) {
        }
        return try {
            val app = TimRuntime.qqAppInterface(cl) ?: return ""
            for (m in app.javaClass.methods) {
                if (m.parameterTypes.isEmpty() && m.returnType == String::class.java &&
                    (m.name.equals("getCurrentAccountUin", true) ||
                        m.name.equals("getLongAccountUin", true) ||
                        m.name == "getAccount" || m.name == "getUin")
                ) {
                    m.isAccessible = true
                    val v = m.invoke(app)?.toString()?.trim().orEmpty()
                    if (v.matches(Regex("\\d{5,12}"))) return v
                }
            }
            ""
        } catch (_: Throwable) {
            ""
        }
    }
}
