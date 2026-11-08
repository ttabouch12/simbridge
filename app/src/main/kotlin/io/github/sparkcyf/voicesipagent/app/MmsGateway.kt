package io.github.sparkcyf.voicesipagent.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Base64
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Picture and group messages (MMS) on the SIM phone. Needs SimBridge Gateway to be the default
 * SMS app there: Android only hands MMS to that app. Incoming MMS are downloaded with the
 * phone's own MMS service and forwarded to the main phone(s); MMS from the main phone are
 * encoded here ([MmsPdu]) and sent through the SIM.
 */
object MmsGateway {
    private const val ACTION_DOWNLOADED = "io.github.simbridge.gateway.MMS_DOWNLOADED"
    private const val ACTION_SENT = "io.github.simbridge.gateway.MMS_SENT"
    /** Largest MMS we try to send (most carriers accept 300 KB to 1 MB). */
    const val MAX_SEND_BYTES = 600 * 1024

    fun isDefaultSmsApp(ctx: Context) = Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName

    private fun dir(ctx: Context) = File(ctx.cacheDir, "mms").apply { mkdirs() }
    private fun uriFor(ctx: Context, f: File) = FileProvider.getUriForFile(ctx, "${ctx.packageName}.mms", f)

    private fun smsManager(ctx: Context, subId: Int?): SmsManager {
        val base = ctx.getSystemService(SmsManager::class.java)
        return if (subId != null && subId >= 0) runCatching { base.createForSubscriptionId(subId) }.getOrDefault(base) else base
    }

    // ------------------------------------------------------------ incoming

    /** WAP push with an M-Notification.ind: fetch the message. */
    fun onWapPush(ctx: Context, pdu: ByteArray, subId: Int?) {
        val n = runCatching { MmsPdu.parse(pdu) }.onFailure { AppLog.w("MMS: unreadable push", it) }.getOrNull() ?: return
        if (n.type == MmsPdu.DELIVERY_IND) { AppLog.i("MMS: delivery report"); return }
        if (n.type != MmsPdu.NOTIFICATION_IND || n.contentLocation.isNullOrBlank()) return
        val tid = n.transactionId ?: System.currentTimeMillis().toString()
        val f = File(dir(ctx), "in-${tid.filter(Char::isLetterOrDigit).take(40)}.pdu")
        val pi = PendingIntent.getBroadcast(ctx, tid.hashCode(),
            Intent(ACTION_DOWNLOADED).setClass(ctx, MmsResultReceiver::class.java)
                .putExtra("file", f.path).putExtra("tid", tid).putExtra("sub", subId ?: -1),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        AppLog.i("MMS: downloading (${n.messageSize ?: "?"} bytes)")
        runCatching { smsManager(ctx, subId).downloadMultimediaMessage(ctx, n.contentLocation, uriFor(ctx, f), null, pi) }
            .onFailure { AppLog.w("MMS: download refused", it) }
    }

    @SuppressLint("MissingPermission")
    fun onDownloaded(ctx: Context, intent: Intent, ok: Boolean) {
        val f = File(intent.getStringExtra("file") ?: return)
        val subId = intent.getIntExtra("sub", -1).takeIf { it >= 0 }
        try {
            if (!ok || !f.exists() || f.length() == 0L) { AppLog.w("MMS: download failed"); return }
            val m = MmsPdu.parse(f.readBytes())
            if (m.type != MmsPdu.RETRIEVE_CONF) { AppLog.w("MMS: unexpected PDU ${m.type}"); return }
            // Tell the carrier we have it, so it stops re-notifying.
            intent.getStringExtra("tid")?.let { send(ctx, MmsPdu.notifyResp(it), subId, null) }
            val me = ownNumbers(ctx)
            val others = (listOfNotNull(m.from) + m.to + m.cc).filter { a -> me.none { sameNumber(it, a) } }.distinctBy { digits(it) }
            val rt = GatewayRuntime.get(ctx)
            val sim = if (subId != null && Sims.dual(ctx)) Sims.slotForSubId(ctx, subId) else null
            val o = JSONObject().put("op", "mms").put("from", m.from ?: "").put("at", m.date ?: System.currentTimeMillis())
                .put("participants", JSONArray(others)).put("text", m.text)
            m.subject?.let { o.put("subject", it) }
            sim?.let { o.put("sim", it) }
            o.put("parts", JSONArray().apply {
                m.media.forEach { p ->
                    put(JSONObject().put("type", p.contentType).put("name", p.name ?: "file.${MmsPdu.ext(p.contentType)}")
                        .put("data", Base64.encodeToString(p.data, Base64.NO_WRAP)))
                }
            })
            rt.control.deliver(o)
            AppLog.i("MMS forwarded: ${m.media.size} file(s), ${others.size} participant(s)")
        } catch (t: Throwable) {
            AppLog.w("MMS: could not read message", t)
        } finally {
            f.delete()
        }
    }

    // ------------------------------------------------------------ outgoing

    /** From the main phone: {"op":"mms-send","to":[..],"text":"..","parts":[{type,name,data}],"ref":"..","sim":2} */
    fun sendFromPhone(ctx: Context, o: JSONObject, report: (String, String?) -> Unit) {
        val to = o.optJSONArray("to")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            .filter { it.count(Char::isDigit) >= 3 || '@' in it }
        if (to.isEmpty()) { report("failed", "no recipients"); return }
        if (!isDefaultSmsApp(ctx)) { report("failed", "Make SimBridge Gateway the SMS app on the SIM phone to send picture and group messages"); return }
        val parts = o.optJSONArray("parts")?.let { a ->
            (0 until a.length()).map { i -> a.getJSONObject(i).let { p ->
                MmsPdu.Part(p.optString("type", "application/octet-stream"), Base64.decode(p.optString("data"), Base64.DEFAULT), p.optString("name").ifBlank { null })
            } }
        }.orEmpty()
        val pdu = MmsPdu.sendReq(to, o.optString("text").ifBlank { null }, parts, "T" + java.lang.Long.toHexString(System.nanoTime()))
        if (pdu.size > MAX_SEND_BYTES + 50_000) { report("failed", "too large (${pdu.size / 1024} KB)"); return }
        val subId = o.optInt("sim", 0).takeIf { it > 0 }?.let { Sims.subIdForSlot(ctx, it) }
        send(ctx, pdu, subId, o.optString("ref").ifBlank { null })
        AppLog.i("MMS: sending to ${to.size} recipient(s), ${parts.size} file(s), ${pdu.size / 1024} KB")
    }

    private fun send(ctx: Context, pdu: ByteArray, subId: Int?, ref: String?) {
        val f = File(dir(ctx), "out-${System.nanoTime()}.pdu").apply { writeBytes(pdu) }
        val pi = PendingIntent.getBroadcast(ctx, f.name.hashCode(),
            Intent(ACTION_SENT).setClass(ctx, MmsResultReceiver::class.java).putExtra("file", f.path).putExtra("ref", ref),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching { smsManager(ctx, subId).sendMultimediaMessage(ctx, uriFor(ctx, f), null, null, pi) }
            .onFailure { AppLog.w("MMS: send refused", it); f.delete(); ref?.let { r -> GatewayRuntime.get(ctx).control.smsStatus(r, "failed", it.message) } }
    }

    fun onSent(ctx: Context, intent: Intent, ok: Boolean, code: Int) {
        intent.getStringExtra("file")?.let { File(it).delete() }
        val ref = intent.getStringExtra("ref") ?: return
        AppLog.i("MMS ${if (ok) "sent" else "failed ($code)"}")
        GatewayRuntime.get(ctx).control.smsStatus(ref, if (ok) "sent" else "failed", if (ok) null else "carrier error $code")
    }

    // ------------------------------------------------------------ helpers

    @SuppressLint("MissingPermission")
    private fun ownNumbers(ctx: Context): List<String> = runCatching {
        val sm = ctx.getSystemService(SubscriptionManager::class.java)
        Sims.list(ctx).mapNotNull { s -> runCatching { sm.getPhoneNumber(s.subId) }.getOrNull()?.takeIf { it.isNotBlank() } }
    }.getOrDefault(emptyList())

    private fun digits(a: String) = a.filter(Char::isDigit).takeLast(9)
    private fun sameNumber(a: String, b: String) = digits(a).length >= 6 && digits(a) == digits(b)

    /** Default SMS app duty: keep received texts in the phone's own inbox too. */
    fun storeInbox(ctx: Context, from: String?, body: String, subId: Int?) {
        runCatching {
            ctx.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
                put(Telephony.Sms.ADDRESS, from); put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1)
                subId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
            })
        }
    }
}

class MmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ok = resultCode == Activity.RESULT_OK
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    "io.github.simbridge.gateway.MMS_DOWNLOADED" -> MmsGateway.onDownloaded(context, intent, ok)
                    "io.github.simbridge.gateway.MMS_SENT" -> MmsGateway.onSent(context, intent, ok, resultCode)
                }
            } finally { pending.finish() }
        }.start()
    }
}

/** Default SMS app: incoming SMS arrive here (SMS_DELIVER) instead of SMS_RECEIVED. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val from = parts.firstOrNull()?.displayOriginatingAddress
        val body = parts.joinToString("") { it.messageBody ?: "" }
        if (body.isEmpty()) return
        val subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1).takeIf { it >= 0 }
        MmsGateway.storeInbox(context, from, body, subId)
        GatewayRuntime.get(context).forwardSms(from, body, subId)
    }
}

/** Default SMS app: MMS notifications (WAP_PUSH_DELIVER). */
class WapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val data = intent.getByteArrayExtra("data") ?: return
        val subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1).takeIf { it >= 0 }
        MmsGateway.onWapPush(context, data, subId)
    }
}

/** Default SMS app: "reply with a message" from the dialer of the SIM phone. */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val to = intent?.data?.schemeSpecificPart?.substringBefore('?')
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!to.isNullOrBlank() && !text.isNullOrBlank()) SmsSender.send(this, to, text) { _, _ -> }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

/** Default SMS app: something must handle "send a text". Texts are written on the main phone. */
class GatewaySmsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.widget.Toast.makeText(this, "Write texts in SimBridge on your main phone", android.widget.Toast.LENGTH_LONG).show()
        finish()
    }
}
