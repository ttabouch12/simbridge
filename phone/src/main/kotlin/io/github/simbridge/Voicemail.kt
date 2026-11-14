package io.github.simbridge

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File

/**
 * Messages the SIM phone recorded for unanswered calls, and what screened callers said. Each
 * voicemail becomes a message from the caller with the recording attached; the words are
 * written in once recognized (cloud speech or on-device, as set for call translation).
 */
object Voicemails {
    fun onMessage(ctx: Context, core: LinkCore, o: JSONObject) {
        val from = o.optString("from").ifBlank { ctx.getString(R.string.unknown_caller) }
        val rate = o.optInt("rate", 8000)
        val wav = android.util.Base64.decode(o.optString("wav"), android.util.Base64.DEFAULT)
        val at = o.optLong("at", System.currentTimeMillis())
        val file = "vm-$at.wav"
        File(ctx.filesDir, "mms").apply { mkdirs() }.resolve(file).writeBytes(wav)
        val sec = (wav.size - 44) / 2 / rate
        val label = ctx.getString(R.string.vm_label, "%d:%02d".format(sec / 60, sec % 60))
        val att = MmsInfo(null, listOf(Attachment("audio/wav", file, label))).toJson()
        val id = core.store.add(from, label, outgoing = false, status = Msg.RECEIVED, time = at, sim = o.optInt("sim", 0), att = att)
        core.notifier.showMessages(MessageStore.keyOf(from), from)
        Log.i("voicemail saved (#$id, ${sec}s)")
        Thread({
            val pcm = Pcm.readWav(File(ctx.filesDir, "mms/$file"), rate) ?: return@Thread
            val text = runCatching { Speech(ctx, core.prefs).transcribe(pcm, rate, core.prefs.callerLang) }.getOrNull()?.trim()
            if (!text.isNullOrEmpty() && !Speech.isJunk(text)) {
                core.store.setBody(id, "$label\n“$text”")
                core.notifier.showMessages(MessageStore.keyOf(from), from)
            }
        }, "voicemail-text").start()
    }

    /** A screened caller: show who they said they are, right before the call rings here. */
    fun onScreened(ctx: Context, core: LinkCore, o: JSONObject) {
        val number = o.optString("from")
        val who = core.contacts.lookup(number)?.name ?: number.ifBlank { ctx.getString(R.string.unknown_caller) }
        val rate = o.optInt("rate", 8000)
        val wav = android.util.Base64.decode(o.optString("wav"), android.util.Base64.DEFAULT)
        Thread({
            val f = File(ctx.cacheDir, "screen.wav").apply { writeBytes(wav) }
            val pcm = Pcm.readWav(f, rate) ?: return@Thread
            val text = runCatching { Speech(ctx, core.prefs).transcribe(pcm, rate, core.prefs.callerLang) }.getOrNull()?.trim()
            core.notifier.screened(who, text?.takeIf { it.isNotEmpty() && !Speech.isJunk(it) } ?: ctx.getString(R.string.screen_unclear))
        }, "screen-text").start()
    }
}
