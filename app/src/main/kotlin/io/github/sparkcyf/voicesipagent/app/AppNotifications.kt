package io.github.sparkcyf.voicesipagent.app

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Forwards the notifications of apps you pick (a bank or carrier app that only runs on this
 * phone) to the main phone, where each app gets its own conversation. Needs notification access,
 * which the gateway grants itself through Shizuku.
 */
class AppNotifications : NotificationListenerService() {
    private val recent = LinkedHashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg !in chosen(this)) return
        val n = sbn.notification
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY) != 0) return
        val e = n.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val body = listOf(title, text).filter { it.isNotBlank() }.joinToString("\n")
        // Apps re-post the same notification when it updates; send each text once per 10 minutes.
        val now = System.currentTimeMillis()
        synchronized(recent) {
            val k = "$pkg|$body"
            if ((recent[k] ?: 0) > now - 600_000) return
            recent[k] = now
            while (recent.size > 100) recent.remove(recent.keys.first())
        }
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        GatewayRuntime.get(this).forwardAppNotification(label, body)
    }

    companion object {
        private const val PREFS = "control"
        fun chosen(c: Context): Set<String> = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("notif_apps", emptySet()).orEmpty()
        fun choose(c: Context, apps: Set<String>) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("notif_apps", apps).apply()
        const val COMPONENT = "io.github.simbridge.gateway/io.github.sparkcyf.voicesipagent.app.AppNotifications"
    }
}
