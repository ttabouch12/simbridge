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
            "password" -> { core.setPassword(intent.getStringExtra("password") ?: ""); Log.i("control: password set") }
            "reconnect" -> core.reconnect()
            "status" -> Log.i("control: status=${core.status.value} ua=${core.ua?.status} account=${Telecom.isEnabled(context)}")
            "send" -> core.sendText(intent.getStringExtra("to") ?: return, intent.getStringExtra("text") ?: return)
            "start" -> LinkService.start(context)
            "test-in" -> core.onIncomingText(intent.getStringExtra("from"), null, intent.getStringExtra("text") ?: "test")
        }
        LinkService.start(context)
    }
}
