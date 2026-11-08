package io.github.simbridge

import android.app.AppOpsManager
import android.content.ContentValues
import android.content.Context
import android.os.Process
import android.provider.Telephony

/**
 * Copies relayed texts into Android's own SMS database, so they also appear in the phone's
 * default Messages app (e.g. Google Messages) next to everything else.
 *
 * Android only lets the default SMS app write there. Either make SimBridge the default SMS app,
 * or grant it the WRITE_SMS app-op once over adb:
 *     adb shell appops set io.github.simbridge WRITE_SMS allow
 * Without it, texts simply stay in SimBridge.
 */
object SystemSms {
    fun permitted(context: Context): Boolean = isDefaultSmsApp(context) || runCatching {
        context.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow("android:write_sms", Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Returns the system row id, or null if Android refused (or silently ignored) the write. */
    fun insert(context: Context, address: String, body: String, time: Long, outgoing: Boolean): Long? {
        if (!permitted(context)) return null
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, time)
            put(Telephony.Sms.DATE_SENT, time)
            put(Telephony.Sms.READ, if (outgoing) 1 else 0)
            put(Telephony.Sms.SEEN, if (outgoing) 1 else 0)
            put(Telephony.Sms.TYPE, if (outgoing) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_INBOX)
        }
        val uri = if (outgoing) Telephony.Sms.Sent.CONTENT_URI else Telephony.Sms.Inbox.CONTENT_URI
        return runCatching { context.contentResolver.insert(uri, values) }
            .onFailure { Log.w("system sms: write refused", it) }
            .getOrNull()?.lastPathSegment?.toLongOrNull()?.takeIf { it > 0 }
    }

    /** Mirrors a delivery result onto the system copy of an outgoing text. */
    fun setStatus(context: Context, m: Msg) {
        val sys = context.let { LinkApp.core(it).store.systemId(m.id) } ?: return
        if (!permitted(context)) return
        val values = ContentValues().apply {
            when (m.status) {
                Msg.DELIVERED -> put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_COMPLETE)
                Msg.FAILED -> { put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_FAILED); put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED) }
                else -> return
            }
        }
        runCatching { context.contentResolver.update(Telephony.Sms.CONTENT_URI, values, "${Telephony.Sms._ID}=?", arrayOf(sys.toString())) }
    }

    fun markRead(context: Context, ids: List<Long>) {
        if (ids.isEmpty() || !permitted(context)) return
        val values = ContentValues().apply { put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1) }
        runCatching {
            context.contentResolver.update(Telephony.Sms.CONTENT_URI, values,
                "${Telephony.Sms._ID} IN (${ids.joinToString(",")})", null)
        }.onFailure { Log.w("system sms: mark read failed", it) }
    }

    fun delete(context: Context, ids: List<Long>) {
        if (ids.isEmpty() || !permitted(context)) return
        runCatching {
            context.contentResolver.delete(Telephony.Sms.CONTENT_URI, "${Telephony.Sms._ID} IN (${ids.joinToString(",")})", null)
        }.onFailure { Log.w("system sms: delete failed", it) }
    }
}
