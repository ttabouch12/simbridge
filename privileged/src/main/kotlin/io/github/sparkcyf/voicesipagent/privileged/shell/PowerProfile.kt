package io.github.sparkcyf.voicesipagent.privileged.shell

import android.util.Log
import org.json.JSONObject

/**
 * Companion power profile for a phone that only relays calls, SMS and its hotspot.
 * Applies a fixed set of reversible changes and records the previous values so OFF restores
 * exactly what the user had. Telephony, Wi-Fi, mobile data and the hotspot are never touched.
 */
internal object PowerProfile {
    private const val TAG = "VsaPower"

    private val SETTINGS = listOf(
        Triple("global", "low_power", "1"),                  // Android battery saver
        Triple("global", "wifi_scan_always_enabled", "0"),   // background Wi-Fi scanning
        Triple("global", "ble_scan_always_enabled", "0"),    // background Bluetooth scanning
        Triple("system", "screen_off_timeout", "15000"),     // screen sleeps after 15 s
    )

    /** Apps that must keep running in the background. */
    private fun keep(pkg: String, self: String) = pkg == self || pkg.contains("shizuku", ignoreCase = true)

    private const val PARALLEL = 8
    private val SAFE = Regex("[A-Za-z0-9_.]+")

    private fun sh(vararg cmd: String, timeout: Long = 15) = ShellEnv.run(*cmd, timeoutSec = timeout)

    /** Runs [body] (which sees the package in \$p) for every package, [PARALLEL] at a time. */
    private fun batch(pkgs: List<String>, body: String): String {
        if (pkgs.isEmpty()) return ""
        val script = "n=0\nfor p in ${pkgs.joinToString(" ")}; do\n( $body ) &\n" +
            "n=${'$'}((n+1)); [ ${'$'}((n % $PARALLEL)) -eq 0 ] && wait\ndone\nwait\n"
        return sh("sh", "-c", script, timeout = 120).out
    }

    fun apply(on: Boolean, self: String, savedState: String): String =
        try { if (on) enable(self) else { disable(savedState); "" } } catch (t: Throwable) {
            Log.w(TAG, "power profile failed", t); if (on) savedState else ""
        }

    private fun enable(self: String): String {
        val state = JSONObject()
        val settings = JSONObject()
        for ((ns, key, value) in SETTINGS) {
            settings.put("$ns/$key", sh("settings", "get", ns, key).out.trim())
            sh("settings", "put", ns, key, value)
        }
        state.put("settings", settings)
        state.put("location", sh("cmd", "location", "is-location-enabled").out.trim())
        sh("cmd", "location", "set-location-enabled", "false")
        state.put("bluetooth", sh("settings", "get", "global", "bluetooth_on").out.trim())
        sh("cmd", "bluetooth_manager", "disable")
        val apps = JSONObject()
        val pkgs = sh("pm", "list", "packages", "-3").out.lineSequence()
            .map { it.removePrefix("package:").trim() }.filter { SAFE.matches(it) && !keep(it, self) }.toList()
        // One shell for all apps, eight at a time: reads the old values, then restricts.
        // Hundreds of separate processes took a minute; this takes a few seconds.
        val out = batch(pkgs, """o=${'$'}(cmd appops get "${'$'}p" RUN_ANY_IN_BACKGROUND | tr '\n' ' '); b=${'$'}(am get-standby-bucket "${'$'}p")
            cmd appops set "${'$'}p" RUN_ANY_IN_BACKGROUND ignore; am set-standby-bucket "${'$'}p" restricted; am force-stop "${'$'}p"
            echo "@@${'$'}p|${'$'}o|${'$'}b"""")
        for (line in out.lineSequence()) {
            if (!line.startsWith("@@")) continue
            val (pkg, op, bucket) = line.removePrefix("@@").split('|', limit = 3).let { Triple(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
            val mode = when { "ignore" in op -> "ignore"; "allow" in op -> "allow"; else -> "default" }
            apps.put(pkg, JSONObject().put("op", mode).put("bucket", bucket.trim()))
        }
        state.put("apps", apps)
        Log.i(TAG, "companion power mode ON (${apps.length()} of ${pkgs.size} apps restricted)")
        return state.toString()
    }

    private fun disable(saved: String) {
        if (saved.isBlank()) return
        val state = JSONObject(saved)
        state.optJSONObject("settings")?.let { s ->
            for (k in s.keys()) {
                val (ns, key) = k.split('/', limit = 2)
                val v = s.optString(k)
                if (v.isEmpty() || v == "null") sh("settings", "delete", ns, key) else sh("settings", "put", ns, key, v)
            }
        }
        if (state.optString("location") == "true") sh("cmd", "location", "set-location-enabled", "true")
        if (state.optString("bluetooth") == "1") sh("cmd", "bluetooth_manager", "enable")
        state.optJSONObject("apps")?.let { apps ->
            val script = StringBuilder()
            for (pkg in apps.keys()) {
                if (!SAFE.matches(pkg)) continue
                val a = apps.optJSONObject(pkg) ?: continue
                val op = a.optString("op", "default").takeIf { it in setOf("allow", "ignore", "default") } ?: "default"
                val b = a.optString("bucket").toIntOrNull()
                val bucket = when {
                    b == null -> "active"; b <= 10 -> "active"; b <= 20 -> "working_set"; b <= 30 -> "frequent"; b <= 40 -> "rare"; else -> "restricted"
                }
                script.append("(cmd appops set $pkg RUN_ANY_IN_BACKGROUND $op; am set-standby-bucket $pkg $bucket) &\n")
                script.append("n=${'$'}((n+1)); [ ${'$'}((n % $PARALLEL)) -eq 0 ] && wait\n")
            }
            script.append("wait\n")
            sh("sh", "-c", "n=0\n$script", timeout = 120)
        }
        Log.i(TAG, "companion power mode OFF (restored)")
    }
}
