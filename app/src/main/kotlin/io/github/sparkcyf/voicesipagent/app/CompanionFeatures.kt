package io.github.sparkcyf.voicesipagent.app

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.ContactsContract
import android.telephony.SmsManager

/** Number -> contact display name on this phone (needs READ_CONTACTS; returns null otherwise). */
object ContactNames {
    fun lookup(ctx: Context, number: String?): String? {
        if (number.isNullOrBlank()) return null
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()
    }
}

/**
 * Sends an SMS from this phone's SIM on behalf of the main phone. [onResult] fires once when the
 * radio has sent every part; [onDelivered] fires once if the network returns delivery reports
 * (true only when every part was delivered). [subId] picks the SIM on dual-SIM phones.
 */
object SmsSender {
    private const val ACTION_SENT = "io.github.sparkcyf.voicesipagent.SMS_SENT"
    private const val ACTION_DELIVERED = "io.github.sparkcyf.voicesipagent.SMS_DELIVERED"

    /** Returns false immediately if sending cannot even be attempted. */
    fun send(ctx: Context, to: String, text: String, subId: Int? = null,
             onDelivered: ((Boolean) -> Unit)? = null, onResult: (Boolean, String) -> Unit): Boolean {
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            onResult(false, "SEND_SMS not granted")
            return false
        }
        val dest = to.filter { it.isDigit() || it == '+' }
        if (dest.length < 3) { onResult(false, "invalid number"); return false }
        val base = ctx.getSystemService(SmsManager::class.java) ?: run { onResult(false, "no SmsManager"); return false }
        val sms = if (subId != null) runCatching { base.createForSubscriptionId(subId) }.getOrDefault(base) else base
        val parts = sms.divideMessage(text)
        val token = System.nanoTime().toString()
        val app = ctx.applicationContext
        val sentReceiver = partsReceiver(token, parts.size) { ok, code ->
            onResult(ok, if (ok) "sent" else "radio error $code")
        }
        app.registerReceiver(sentReceiver, IntentFilter(ACTION_SENT), Context.RECEIVER_NOT_EXPORTED)
        val deliveredReceiver = onDelivered?.let { cb ->
            partsReceiver(token, parts.size, timeoutMs = 6 * 3600_000L, app) { ok, _ -> cb(ok) }
                .also { app.registerReceiver(it, IntentFilter(ACTION_DELIVERED), Context.RECEIVER_NOT_EXPORTED) }
        }
        fun pending(action: String, i: Int, salt: Int) = PendingIntent.getBroadcast(ctx, token.hashCode() * 31 + i * 2 + salt,
            Intent(action).setPackage(ctx.packageName).putExtra("token", token),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        val sent = ArrayList(parts.indices.map { pending(ACTION_SENT, it, 0) })
        val delivered = if (onDelivered != null) ArrayList(parts.indices.map { pending(ACTION_DELIVERED, it, 1) }) else null
        return runCatching {
            if (parts.size == 1) sms.sendTextMessage(dest, null, text, sent[0], delivered?.get(0))
            else sms.sendMultipartTextMessage(dest, null, parts, sent, delivered)
            true
        }.getOrElse {
            runCatching { app.unregisterReceiver(sentReceiver) }
            deliveredReceiver?.let { r -> runCatching { app.unregisterReceiver(r) } }
            onResult(false, it.message ?: it.toString())
            false
        }
    }

    /**
     * Counts the per-part results for one message. A delivery report is "delivered" when the
     * status PDU says so; Android reports it with RESULT_OK and a "format"/"pdu" extra.
     */
    private fun partsReceiver(token: String, parts: Int, timeoutMs: Long = 0, app: Context? = null,
                              done: (Boolean, Int) -> Unit) = object : BroadcastReceiver() {
        var remaining = parts
        var failed = false
        var lastCode = 0
        init {
            if (timeoutMs > 0 && app != null) android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ if (remaining > 0) { remaining = 0; runCatching { app.unregisterReceiver(this) } } }, timeoutMs)
        }
        override fun onReceive(c: Context, i: Intent) {
            if (i.getStringExtra("token") != token || remaining <= 0) return
            if (resultCode != android.app.Activity.RESULT_OK || !deliveredOk(i)) { failed = true; lastCode = resultCode }
            if (--remaining <= 0) {
                runCatching { c.applicationContext.unregisterReceiver(this) }
                done(!failed, lastCode)
            }
        }
    }

    /** For delivery reports: the SMS-STATUS-REPORT status (0x00..0x1F = delivered). Sent results have no PDU. */
    private fun deliveredOk(i: Intent): Boolean {
        val pdu = i.getByteArrayExtra("pdu") ?: return true
        val sm = runCatching { android.telephony.SmsMessage.createFromPdu(pdu, i.getStringExtra("format")) }.getOrNull() ?: return true
        val st = sm.status
        return st == android.provider.Telephony.Sms.STATUS_COMPLETE || st in 0..0x1F
    }
}

object BatteryInfo {
    data class State(val percent: Int, val charging: Boolean)
    fun read(ctx: Context): State? {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level < 0 || scale <= 0) return null
        return State(level * 100 / scale, plugged)
    }
}

/**
 * Status alerts to the main phone (as chat messages from "SimBridge Gateway").
 * Queued while no phone is registered and delivered on the next registration.
 */
class GatewayAlerts(private val send: (String) -> Boolean) {
    private val queue = ArrayDeque<String>()
    private var lowBatteryWarned = false
    private var lastPlugged: Boolean? = null
    private var shizukuWasUp = false
    private var shizukuDownSince = 0L
    private var shizukuAlerted = false

    @Synchronized fun post(text: String) {
        if (!send(text)) { queue.addLast(text); while (queue.size > 10) queue.removeFirst() }
    }

    @Synchronized fun flush() {
        while (queue.isNotEmpty()) { if (!send(queue.first())) return; queue.removeFirst() }
    }

    /** Call on every Shizuku state change and periodically; [up] = helper connected. */
    @Synchronized fun shizuku(up: Boolean, now: Long) {
        if (up) {
            if (shizukuAlerted) post("✅ Call bridging is working again (Shizuku is back).")
            shizukuAlerted = false; shizukuDownSince = 0; shizukuWasUp = true
        } else {
            if (shizukuDownSince == 0L) shizukuDownSince = now
            // After a reboot it may legitimately take a while (Wi-Fi + wireless debugging), so allow 3 min.
            val grace = if (shizukuWasUp) 45_000L else 180_000L
            if (!shizukuAlerted && now - shizukuDownSince > grace) {
                shizukuAlerted = true
                post("⚠️ Shizuku stopped on the SIM phone — calls can't be bridged. Texts still work. " +
                    "It should restart by itself; if not, open Shizuku on the SIM phone and tap Start.")
            }
        }
    }

    @Synchronized fun battery(b: BatteryInfo.State?) {
        b ?: return
        if (lastPlugged == true && !b.charging) post("🔌 The SIM phone was unplugged (battery ${b.percent}%).")
        lastPlugged = b.charging
        if (!b.charging && b.percent <= 20 && !lowBatteryWarned) {
            lowBatteryWarned = true
            post("🪫 SIM phone battery is low (${b.percent}%). Plug it in to keep calls and texts working.")
        }
        if (b.percent >= 30 || b.charging) lowBatteryWarned = false
    }
}
