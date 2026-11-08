package io.github.simbridge

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion

/**
 * Home / work Wi-Fi handover. This phone suggests the saved networks to Android (so it joins them
 * by itself once the hotspot goes away) and, while on the SIM phone's hotspot, watches for them:
 * when one is in range it asks the SIM phone to join it and turn the hotspot off.
 */
object Places {
    @Volatile private var lastAsk = 0L

    /** Registers (or clears) the saved places as Wi-Fi suggestions. Android asks once to allow them. */
    fun apply(ctx: Context, core: LinkCore) {
        val wm = ctx.getSystemService(WifiManager::class.java) ?: return
        runCatching { wm.removeNetworkSuggestions(emptyList()) }   // empty list = remove all of ours
        if (!core.prefs.placesAuto) return
        // Without a password the network must already be saved on this phone; nothing to suggest.
        val list = core.prefs.places.filter { it.password.isNotEmpty() }.map { p ->
            WifiNetworkSuggestion.Builder().setSsid(p.ssid).setWpa2Passphrase(p.password).build()
        }
        if (list.isNotEmpty()) runCatching { wm.addNetworkSuggestions(list) }.onFailure { Log.w("places: suggestions refused", it) }
    }

    @Suppress("DEPRECATION")
    fun currentSsid(ctx: Context): String? = runCatching {
        ctx.getSystemService(WifiManager::class.java).connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }.getOrNull()

    /** Wi-Fi names this phone sees now, strongest first. */
    @SuppressLint("MissingPermission")
    fun nearby(ctx: Context): List<String> = runCatching {
        ctx.getSystemService(WifiManager::class.java).scanResults.sortedByDescending { it.level }.map { it.SSID }
            .filter { it.isNotBlank() }.distinct()
    }.getOrDefault(emptyList())

    /** Called every minute by the service. */
    @SuppressLint("MissingPermission")
    fun check(ctx: Context, core: LinkCore) {
        if (!core.prefs.placesAuto) return
        val places = core.prefs.places.takeIf { it.isNotEmpty() } ?: return
        val here = currentSsid(ctx)
        if (places.any { it.ssid == here }) return                 // already on a place's Wi-Fi
        if (System.currentTimeMillis() - lastAsk < 3 * 60_000) return
        val wm = ctx.getSystemService(WifiManager::class.java) ?: return
        @Suppress("DEPRECATION") runCatching { wm.startScan() }
        val seen = runCatching { wm.scanResults.map { it.SSID } }.getOrDefault(emptyList()).toSet()
        val p = places.firstOrNull { it.ssid in seen } ?: return
        lastAsk = System.currentTimeMillis()
        Log.i("places: ${p.name.ifBlank { "a saved place" }} in range, asking the SIM phone to switch")
        core.control.atPlace(p)
    }
}
