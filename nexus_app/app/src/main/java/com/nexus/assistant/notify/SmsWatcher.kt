package com.nexus.assistant.notify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import com.nexus.assistant.config.ConfigRepository
import com.nexus.assistant.config.LocalLineResolver
import kotlin.concurrent.thread

/**
 * Observes SMS inbox; forwards new messages via webhook when notify.sms_enabled.
 * Cursor watermark stored in SharedPreferences (survives process death).
 *
 * Registration alone is not enough when the process is dead — see [SmsReceiver].
 */
object SmsWatcher {
    private const val PREFS = "nexus_sms"
    private const val KEY_CURSOR = "last_sms_id"
    private const val TAG = "SmsWatcher"

    @Volatile
    private var registered = false

    private var observer: ContentObserver? = null

    fun ensureRegistered(context: Context) {
        val app = context.applicationContext
        if (registered) return
        if (app.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_SMS not granted")
            return
        }
        synchronized(this) {
            if (registered) return
            seedCursorIfNeeded(app)
            val obs =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        onChange(selfChange, null)
                    }

                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        thread(name = "SmsWatcher") { pollOnce(app) }
                    }
                }
            app.contentResolver.registerContentObserver(
                Telephony.Sms.Inbox.CONTENT_URI,
                true,
                obs,
            )
            observer = obs
            registered = true
            Log.i(TAG, "registered inbox observer")
        }
    }

    fun pollOnce(context: Context) {
        val app = context.applicationContext
        val cfg = ConfigRepository(app).load()
        if (!cfg.notify.enabled || !cfg.notify.smsEnabled) {
            Log.i(TAG, "poll skip: notify off (enabled=${cfg.notify.enabled} sms=${cfg.notify.smsEnabled})")
            return
        }
        if (cfg.notify.webhookUrl.isBlank()) {
            Log.w(TAG, "poll skip: empty webhook_url")
            return
        }
        if (app.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "poll skip: READ_SMS not granted")
            return
        }
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastId = prefs.getLong(KEY_CURSOR, 0L)
        val cr = app.contentResolver
        val projection =
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.SUBSCRIPTION_ID,
            )
        val selection = "${Telephony.Sms._ID} > ?"
        val args = arrayOf(lastId.toString())
        val sort = "${Telephony.Sms._ID} ASC"
        var advancedTo = lastId
        var seen = 0
        try {
            cr.query(Telephony.Sms.Inbox.CONTENT_URI, projection, selection, args, sort)?.use { c ->
                val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iSub = c.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
                while (c.moveToNext()) {
                    seen++
                    val id = c.getLong(iId)
                    val addr = c.getString(iAddr).orEmpty()
                    val body = c.getString(iBody).orEmpty()
                    val sub = if (iSub >= 0) c.getInt(iSub) else -1
                    val local = LocalLineResolver.forSubscriptionId(app, sub)
                    val text =
                        buildString {
                            appendLine("【Nexus 短信】")
                            appendLine("发件人: ${addr.ifBlank { "未知" }}")
                            appendLine("收件人: ${local.display()}")
                            append(body)
                        }
                    val r = WebhookNotifier.sendWithRetry(cfg.notify.webhookUrl, text)
                    if (r.status == WebhookDeliveryStatus.SENT) {
                        Log.i(TAG, "sms forwarded id=$id attempts=${r.attempts}")
                        if (id > advancedTo) advancedTo = id
                    } else {
                        // Do not advance past a failed id — next SMS_RECEIVED/poll will retry.
                        Log.w(TAG, "sms notify ${r.status} id=$id: ${r.error}")
                        break
                    }
                }
            }
            if (advancedTo > lastId) {
                prefs.edit().putLong(KEY_CURSOR, advancedTo).apply()
            }
            Log.i(TAG, "poll done lastId=$lastId advancedTo=$advancedTo newRows=$seen")
        } catch (e: Exception) {
            Log.e(TAG, "pollOnce", e)
        }
    }

    private fun seedCursorIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CURSOR)) return
        var maxId = 0L
        try {
            context.contentResolver
                .query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    arrayOf(Telephony.Sms._ID),
                    null,
                    null,
                    "${Telephony.Sms._ID} DESC",
                )?.use { c ->
                    if (c.moveToFirst()) {
                        maxId = c.getLong(0)
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "seed cursor", e)
        }
        prefs.edit().putLong(KEY_CURSOR, maxId).apply()
        Log.i(TAG, "seeded sms cursor=$maxId")
    }
}
