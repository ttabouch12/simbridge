package io.github.simbridge

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val core = LinkApp.core(context)
        val key = intent.getStringExtra("key") ?: return
        when (intent.action) {
            REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(TEXT)?.toString()?.trim()
                val address = intent.getStringExtra("address") ?: return
                if (!text.isNullOrEmpty()) core.sendText(address, text)
                core.markRead(key)
                core.notifier.cancel(key)
            }
            READ -> {
                core.markRead(key)
                core.notifier.cancel(key)
            }
        }
    }

    companion object {
        const val REPLY = "io.github.simbridge.REPLY"
        const val READ = "io.github.simbridge.READ"
        const val TEXT = "text"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LinkService.start(context)
    }
}

/** adb shell am broadcast -a io.github.simbridge.CONTROL -n io.github.simbridge/.ControlReceiver --es op ... */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val core = LinkApp.core(context)
        when (intent.getStringExtra("op")) {
            "pair" -> PairedGateway.parse(intent.getStringExtra("link") ?: "")?.let(core::pair)
                ?: Log.w("control: bad pairing link")
            "reconnect" -> core.reconnect()
            "status" -> Log.i("control: status=${core.status.value} ua=${core.ua?.status} account=${Telecom.isEnabled(context)}")
            "send" -> core.sendText(intent.getStringExtra("to") ?: return, intent.getStringExtra("text") ?: return)
            "start" -> LinkService.start(context)
            "ussd" -> core.control.ussd(intent.getStringExtra("code") ?: return, intent.getIntExtra("sim", 0).takeIf { it > 0 })
            "ctl" -> core.control.control(intent.getStringExtra("action") ?: return, intent.getBooleanExtra("on", true))
            "dash" -> core.control.refresh()
            "rec" -> if (intent.getBooleanExtra("on", true)) ActiveCall.current?.startRecording() else ActiveCall.current?.stopRecording()
            "block" -> { core.prefs.blocked = core.prefs.blocked + (intent.getStringExtra("number") ?: return); core.blockRulesChanged() }
            "unblock" -> { core.prefs.blocked = core.prefs.blocked - (intent.getStringExtra("number") ?: return); core.blockRulesChanged() }
            "test-in" -> core.onIncomingText(intent.getStringExtra("from"), null, intent.getStringExtra("text") ?: "test")
        }
        LinkService.start(context)
    }
}
