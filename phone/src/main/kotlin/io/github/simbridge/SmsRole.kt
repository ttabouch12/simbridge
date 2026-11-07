package io.github.simbridge

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony

/** Whether SimBridge is the phone's default SMS app (then it owns the system message store). */
fun isDefaultSmsApp(context: Context): Boolean =
    runCatching { Telephony.Sms.getDefaultSmsPackage(context) == context.packageName }.getOrDefault(false)

/**
 * SMS that arrive on this phone's *own* SIM (if it has one) while SimBridge is the default SMS app.
 * The default app is responsible for storing them, so they go into the system store and SimBridge.
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val from = parts.firstOrNull()?.displayOriginatingAddress ?: return
        val body = parts.joinToString("") { it.displayMessageBody ?: "" }
        LinkApp.core(context).onLocalSms(from, body)
    }
}

/** MMS isn't supported; the receiver only exists because the SMS role requires it. */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}

/** Quick replies from the Phone app's incoming-call screen ("Can't talk now…"), sent through the SIM phone. */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val number = intent?.data?.schemeSpecificPart?.substringBefore('?')?.trim()
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (!number.isNullOrEmpty() && !text.isNullOrEmpty()) LinkApp.core(this).sendText(number, text)
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
