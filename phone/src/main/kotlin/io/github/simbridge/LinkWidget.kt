package io.github.simbridge

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen widget: link state and the SIM phone's battery and signal. Tap opens the app. */
class LinkWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = update(ctx)

    companion object {
        private var last: String? = null

        /** Redraws all widgets; cheap no-op when nothing changed. */
        fun update(ctx: Context) {
            val core = LinkApp.core(ctx)
            val link = core.status.value
            val gw = core.control.status.value
            val online = link.state == LinkState.CONNECTED
            val title = ctx.getString(if (online) R.string.dash_connected else R.string.dash_offline)
            val detail = buildList {
                gw?.battery?.let { add("🔋 $it%" + if (gw.charging) " ⚡" else "") }
                gw?.sims?.firstOrNull()?.let { add(it.carrier.ifBlank { it.name } + " " + it.network) }
                if (isEmpty()) add(link.detail)
            }.joinToString("  ·  ")
            val key = "$online|$detail"
            if (key == last) return
            last = key
            val v = RemoteViews(ctx.packageName, R.layout.widget_link).apply {
                setTextViewText(R.id.widget_title, title)
                setTextViewText(R.id.widget_detail, detail)
                setInt(R.id.widget_dot, "setColorFilter", if (online) 0xFF34A853.toInt() else 0xFFEA4335.toInt())
                setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(ctx, 0,
                    Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            }
            val mgr = AppWidgetManager.getInstance(ctx)
            mgr.updateAppWidget(ComponentName(ctx, LinkWidget::class.java), v)
        }
    }
}
