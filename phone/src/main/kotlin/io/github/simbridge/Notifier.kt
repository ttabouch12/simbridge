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
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Texts received by your SIM phone"
        })
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Connection to SIM phone", NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Problems", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun statusNotification(s: LinkStatus): Notification {
        val (title, text) = when (s.state) {
            LinkState.CONNECTED -> "Connected to SIM phone" to "Calls and texts via ${s.network ?: "Wi-Fi"}"
            LinkState.SEARCHING -> "Looking for your SIM phone…" to s.detail
            LinkState.NO_WIFI -> "SIM phone not reachable" to s.detail
            LinkState.NEEDS_PASSWORD -> "SimBridge needs setup" to s.detail
        }
        return Notification.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(title).setContentText(text)
            .setOngoing(true).setShowWhen(false)
            .setContentIntent(openApp(null, 0))
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
        val me = Person.Builder().setName("You").build()
        val style = Notification.MessagingStyle(me)
        unread.forEach { style.addMessage(Notification.MessagingStyle.Message(it.body, it.time, sender)) }

        val reply = Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_link), "Reply",
            PendingIntent.getBroadcast(context, key.hashCode(), Intent(context, ReplyReceiver::class.java)
                .setAction(ReplyReceiver.REPLY).putExtra("key", key).putExtra("address", address),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
            .addRemoteInput(RemoteInput.Builder(ReplyReceiver.TEXT).setLabel("Text message").build())
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(true)
            .build()
        val read = Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_link), "Mark as read",
            PendingIntent.getBroadcast(context, key.hashCode() + 1, Intent(context, ReplyReceiver::class.java)
                .setAction(ReplyReceiver.READ).putExtra("key", key),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .build()

        val n = Notification.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_link)
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
            .setContentTitle("Missed a call from your SIM phone")
            .setContentText("Turn on the SIM phone calling account so calls ring on this phone")
            .setContentIntent(PendingIntent.getActivity(context, 7, Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true).build()
        runCatching { nm.notify(ID_ALERT, n) }
    }

    private fun openApp(key: String?, req: Int): PendingIntent = PendingIntent.getActivity(context, req,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { key?.let { putExtra(MainActivity.EXTRA_KEY, it) } },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        const val CH_MESSAGES = "messages"
        const val CH_STATUS = "status"
        const val CH_ALERTS = "alerts"
        const val ID_STATUS = 1
        const val ID_ALERT = 2

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
