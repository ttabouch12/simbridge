package io.github.simbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.telecom.TelecomManager

class Notifier(private val context: Context, private val core: LinkCore) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, context.getString(R.string.ch_messages), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.ch_messages_d)
        })
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, context.getString(R.string.ch_status), NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, context.getString(R.string.ch_alerts), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_CALL, context.getString(R.string.ch_call), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.ch_call_d)
        })
        nm.createNotificationChannel(NotificationChannel(CH_MISSED, context.getString(R.string.ch_missed), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.ch_missed_d)
        })
        nm.createNotificationChannel(NotificationChannel(CH_MIC, context.getString(R.string.ch_mic), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.ch_mic_d)
            setSound(null, null)
        })
    }

    fun statusNotification(s: LinkStatus): Notification {
        val (title, text) = when (s.state) {
            LinkState.CONNECTED -> context.getString(R.string.n_connected) to context.getString(R.string.n_via, s.network ?: "Wi-Fi")
            LinkState.SEARCHING -> context.getString(R.string.looking) to s.detail
            LinkState.NO_WIFI -> context.getString(R.string.n_unreachable) to s.detail
            LinkState.NEEDS_PASSWORD -> context.getString(R.string.n_setup) to s.detail
        }
        val needsMic = !LinkService.micReady && s.state == LinkState.CONNECTED
        return Notification.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(title).setContentText(if (needsMic) context.getString(R.string.n_tap_mic) else text)
            .setOngoing(true).setShowWhen(false)
            .setContentIntent(if (needsMic) micIntent() else openApp(null, 0))
            .build()
    }

    fun showMessages(key: String, address: String) {
        val unread = core.store.unreadIn(key)
        if (unread.isEmpty()) { nm.cancel(key.hashCode()); return }
        val info = core.contacts.lookup(address)
        val name = info?.name ?: address
        val icon = core.contacts.photo(info?.photo)?.let { Icon.createWithBitmap(circle(it)) }
            ?: Icon.createWithBitmap(letterAvatar(name))
        val sender = Person.Builder().setName(name).setIcon(icon).setKey(key).build()
        val me = Person.Builder().setName(context.getString(R.string.you)).build()
        val style = Notification.MessagingStyle(me)
        val group = Group.isGroup(address)
        if (group) { style.conversationTitle = name; style.isGroupConversation = true }
        unread.forEach { m ->
            val info = MmsInfo.parse(m.att)
            val who = if (group) info?.from?.let { f ->
                val n = core.contacts.lookup(f)?.name ?: f
                Person.Builder().setName(n).setKey(MessageStore.keyOf(f)).setIcon(Icon.createWithBitmap(letterAvatar(n))).build()
            } ?: sender else sender
            val body = m.body.ifBlank { if (info?.files?.any { it.isImage } == true) "📷 Photo" else if (info != null) "📎 Attachment" else "" }
            style.addMessage(Notification.MessagingStyle.Message(body, m.time, who))
        }

        val reply = Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_link), context.getString(R.string.reply),
            PendingIntent.getBroadcast(context, key.hashCode(), Intent(context, ReplyReceiver::class.java)
                .setAction(ReplyReceiver.REPLY).putExtra("key", key).putExtra("address", address),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
            .addRemoteInput(RemoteInput.Builder(ReplyReceiver.TEXT).setLabel(context.getString(R.string.text_hint)).build())
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(true)
            .build()
        val read = Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_link), context.getString(R.string.mark_read),
            PendingIntent.getBroadcast(context, key.hashCode() + 1, Intent(context, ReplyReceiver::class.java)
                .setAction(ReplyReceiver.READ).putExtra("key", key),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .build()

        // A long-lived conversation shortcut puts the notification in Android's Conversations
        // section and lets Wear OS / Android Auto show it as a chat with the sender's face.
        runCatching {
            val open = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(MainActivity.EXTRA_KEY, key)
            context.getSystemService(android.content.pm.ShortcutManager::class.java).pushDynamicShortcut(
                android.content.pm.ShortcutInfo.Builder(context, "c:$key")
                    .setShortLabel(name).setLongLived(true).setIcon(icon).setPerson(sender)
                    .setCategories(setOf("android.shortcut.conversation"))
                    .setIntent(open).build())
        }
        val n = Notification.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_link)
            .setShortcutId("c:$key")
            .setLocusId(android.content.LocusId("c:$key"))
            .setStyle(style)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setColor(0xFF1A73E8.toInt())
            .setContentIntent(openApp(key, key.hashCode()))
            .addAction(reply).addAction(read)
            .setAutoCancel(true)
            .setWhen(unread.last().time)
            .build()
        runCatching { nm.notify(key.hashCode(), n) }
    }

    fun cancel(key: String) = nm.cancel(key.hashCode())

    fun callAccountOff() {
        val n = Notification.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(context.getString(R.string.n_missed_account))
            .setContentText(context.getString(R.string.n_missed_account_t))
            .setContentIntent(PendingIntent.getActivity(context, 7, Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true).build()
        runCatching { nm.notify(ID_ALERT, n) }
    }

    /** Android started SimBridge in the background (after a restart or update) without the mic. */
    fun micBlocked() {
        val n = Notification.Builder(context, CH_MIC)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(context.getString(R.string.n_mic_off))
            .setContentText(context.getString(R.string.n_mic_off_t))
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(micIntent())
            .setAutoCancel(true).build()
        runCatching { nm.notify(ID_MIC, n) }
    }

    fun micAllowed() = nm.cancel(ID_MIC)

    /** Shown during a SimBridge call: Record / Stop recording. Wear OS and cars show the action too. */
    fun inCall(who: String, recording: Boolean) {
        val name = core.contacts.lookup(who)?.name ?: who.ifBlank { "Call" }
        val action = if (recording) RecordReceiver.STOP else RecordReceiver.START
        val pi = PendingIntent.getBroadcast(context, 11, Intent(context, RecordReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(context, CH_CALL)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(if (recording) context.getString(R.string.n_recording, name) else context.getString(R.string.n_call_with, name))
            .setContentText(if (recording) context.getString(R.string.n_rec_saved_end) else context.getString(R.string.n_tap_record))
            .setOngoing(true).setUsesChronometer(true).setShowWhen(true)
            .setCategory(Notification.CATEGORY_CALL)
            .addAction(Notification.Action.Builder(null, if (recording) context.getString(R.string.stop_recording) else context.getString(R.string.record), pi).build())
            .build()
        runCatching { nm.notify(ID_CALL, n) }
    }

    fun cancelInCall() = nm.cancel(ID_CALL)

    fun recordingSaved(who: String, uri: android.net.Uri) {
        val name = core.contacts.lookup(who)?.name ?: who.ifBlank { "call" }
        val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val n = Notification.Builder(context, CH_CALL)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(context.getString(R.string.n_rec_saved))
            .setContentText(context.getString(R.string.n_rec_with, name))
            .setContentIntent(PendingIntent.getActivity(context, uri.hashCode(), open, PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true).build()
        runCatching { nm.notify(uri.hashCode(), n) }
    }

    /** A call the SIM phone got while this phone was unreachable. */
    fun missedCall(number: String, name: String?, at: Long, sim: Int) {
        val who = name ?: number.ifBlank { context.getString(R.string.unknown_caller) }
        val call = Intent(Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val b = Notification.Builder(context, CH_MISSED)
            .setSmallIcon(android.R.drawable.stat_notify_missed_call)
            .setContentTitle(context.getString(R.string.missed_call))
            .setContentText("$who${if (sim > 0) " · SIM $sim" else ""} · while this phone was offline")
            .setWhen(at).setShowWhen(true)
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
        if (number.isNotBlank()) {
            b.setContentIntent(PendingIntent.getActivity(context, number.hashCode(), call, PendingIntent.FLAG_IMMUTABLE))
            b.addAction(Notification.Action.Builder(null, context.getString(R.string.call_back),
                PendingIntent.getActivity(context, number.hashCode() + 1, call, PendingIntent.FLAG_IMMUTABLE)).build())
            b.addAction(Notification.Action.Builder(null, context.getString(R.string.message),
                PendingIntent.getActivity(context, number.hashCode() + 2, Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_SENDTO).setData(android.net.Uri.fromParts("smsto", number, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)).build())
        }
        runCatching { nm.notify("missed:$at".hashCode(), b.build()) }
    }

    private fun micIntent(): PendingIntent = PendingIntent.getActivity(context, 9,
        Intent(context, MicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

    private fun openApp(key: String?, req: Int): PendingIntent = PendingIntent.getActivity(context, req,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { key?.let { putExtra(MainActivity.EXTRA_KEY, it) } },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        const val CH_MESSAGES = "messages"
        const val CH_STATUS = "status"
        const val CH_ALERTS = "alerts"
        const val CH_MIC = "mic"
        const val CH_MISSED = "missed"
        const val CH_CALL = "call"
        const val ID_STATUS = 1
        const val ID_ALERT = 2
        const val ID_MIC = 3
        const val ID_CALL = 4

        val AVATAR_COLORS = intArrayOf(0xFF1A73E8.toInt(), 0xFFD93025.toInt(), 0xFF188038.toInt(), 0xFFE37400.toInt(),
            0xFF9334E6.toInt(), 0xFF007B83.toInt(), 0xFFC5221F.toInt(), 0xFF1967D2.toInt())

        fun avatarColor(name: String) = AVATAR_COLORS[Math.floorMod(name.hashCode(), AVATAR_COLORS.size)]

        fun letterAvatar(name: String, size: Int = 128): Bitmap {
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = avatarColor(name) }
            c.drawCircle(size / 2f, size / 2f, size / 2f, p)
            p.color = 0xFFFFFFFF.toInt(); p.textSize = size * 0.45f; p.textAlign = Paint.Align.CENTER
            val letter = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"
            c.drawText(letter, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
            return b
        }

        fun circle(src: Bitmap): Bitmap {
            val s = minOf(src.width, src.height)
            val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            c.drawCircle(s / 2f, s / 2f, s / 2f, p)
            p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
            c.drawBitmap(src, ((s - src.width) / 2).toFloat(), ((s - src.height) / 2).toFloat(), p)
            return b
        }
    }
}
