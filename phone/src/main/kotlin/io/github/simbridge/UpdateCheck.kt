package io.github.simbridge

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Once a day: is there a newer SimBridge release on GitHub? (Off in the F-Droid build.) */
object UpdateCheck {
    const val RELEASES = "https://github.com/ttabouch12/simbridge/releases/latest"
    private const val API = "https://api.github.com/repos/ttabouch12/simbridge/releases/latest"
    private val _latest = MutableStateFlow<String?>(null)
    /** Newer version name, or null when up to date / unknown. */
    val latest: StateFlow<String?> = _latest

    fun maybeCheck(ctx: Context, force: Boolean = false) {
        val core = LinkApp.core(ctx)
        if (!core.prefs.updateCheck && !force) return
        val p = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)
        p.getString("latest", null)?.let { if (newer(it, BuildConfig.VERSION_NAME)) _latest.value = it }
        if (!force && System.currentTimeMillis() - p.getLong("at", 0) < 86_400_000L) return
        Thread {
            runCatching {
                val c = (URL(API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000; readTimeout = 10_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "SimBridge")
                }
                val tag = c.inputStream.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)).optString("tag_name") }.removePrefix("v")
                p.edit().putString("latest", tag).putLong("at", System.currentTimeMillis()).apply()
                _latest.value = tag.takeIf { newer(it, BuildConfig.VERSION_NAME) }
            }.onFailure { Log.w("update check failed: ${it.message}") }
        }.start()
    }

    /** "2.1.0" > "2.0.3"; ignores suffixes like "-beta". */
    fun newer(a: String, b: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
