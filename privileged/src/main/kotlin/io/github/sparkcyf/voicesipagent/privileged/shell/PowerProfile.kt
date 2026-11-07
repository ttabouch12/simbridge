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

    private fun sh(vararg cmd: String) = ShellEnv.run(*cmd, timeoutSec = 15)

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
            .map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() && !keep(it, self) }.toList()
        for (pkg in pkgs) {
            val op = sh("cmd", "appops", "get", pkg, "RUN_ANY_IN_BACKGROUND").out
            val mode = when { "ignore" in op -> "ignore"; "allow" in op -> "allow"; else -> "default" }
            val bucket = sh("am", "get-standby-bucket", pkg).out.trim()
            apps.put(pkg, JSONObject().put("op", mode).put("bucket", bucket))
            sh("cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "ignore")
            sh("am", "set-standby-bucket", pkg, "restricted")
            sh("am", "force-stop", pkg)
        }
        state.put("apps", apps)
        Log.i(TAG, "companion power mode ON (${pkgs.size} apps restricted)")
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
            for (pkg in apps.keys()) {
                val a = apps.optJSONObject(pkg) ?: continue
                sh("cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", a.optString("op", "default"))
                val b = a.optString("bucket").toIntOrNull()
                sh("am", "set-standby-bucket", pkg, when {
                    b == null -> "active"; b <= 10 -> "active"; b <= 20 -> "working_set"; b <= 30 -> "frequent"; b <= 40 -> "rare"; else -> "restricted"
                })
            }
        }
        Log.i(TAG, "companion power mode OFF (restored)")
    }
}
