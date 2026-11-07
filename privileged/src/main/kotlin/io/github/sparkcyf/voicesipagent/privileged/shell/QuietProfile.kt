package io.github.sparkcyf.voicesipagent.privileged.shell

import android.util.Log
import org.json.JSONObject

/**
 * "Ultimate" companion mode for a gateway phone that lives in a bag: no sounds, no vibration,
 * and nothing that lights the screen. Each part is optional ([flags]) and every change is
 * recorded so OFF restores the user's exact previous values. Calls, SMS relay, Wi-Fi, mobile
 * data and the hotspot are not affected. Keys that don't exist on a device are created and
 * then deleted again on restore, which is harmless.
 */
internal object QuietProfile {
    private const val TAG = "VsaQuiet"
    const val SOUND = 1
    const val VIBRATION = 2
    const val SCREEN = 4

    private val SOUND_SETTINGS = listOf(
        "system" to "sound_effects_enabled", "system" to "dtmf_tone", "system" to "lockscreen_sounds_enabled",
        "global" to "charging_sounds_enabled", "system" to "charging_sounds_enabled",   // AOSP / Samsung
        "global" to "power_sounds_enabled", "system" to "screen_lock_sound_enabled",
    ).map { Triple(it.first, it.second, "0") } +
        Triple("system", "all_sound_off", "1")   // Samsung: Accessibility > Mute all sounds

    private val VIBRATION_SETTINGS = listOf(
        "system" to "vibrate_when_ringing", "system" to "haptic_feedback_enabled",
        "system" to "ring_vibration_intensity", "system" to "notification_vibration_intensity",
        "system" to "haptic_feedback_intensity", "system" to "alarm_vibration_intensity",
        "system" to "hardware_haptic_feedback_intensity", "system" to "media_vibration_intensity",
        "system" to "VIB_RECVCALL_MAGNITUDE", "system" to "VIB_NOTIFICATION_MAGNITUDE",  // Samsung
        "system" to "SEM_VIBRATION_NOTIFICATION_INTENSITY", "global" to "charging_vibration_enabled",
        "system" to "VIB_FEEDBACK_MAGNITUDE", "system" to "keyboard_vibration_enabled",
        "system" to "navigation_gestures_vibrate", "system" to "sip_key_feedback_vibration",
        "system" to "dialing_keypad_vibrate", "system" to "camera_feedback_vibrate",
    ).map { Triple(it.first, it.second, "0") }

    private val SCREEN_SETTINGS = listOf(
        // AOSP ambient display / gestures that wake the screen
        Triple("secure", "doze_enabled", "0"), Triple("secure", "doze_always_on", "0"),
        Triple("secure", "doze_pulse_on_pick_up", "0"), Triple("secure", "doze_pulse_on_double_tap", "0"),
        Triple("secure", "doze_tap_gesture", "0"), Triple("secure", "doze_wake_display_gesture", "0"),
        Triple("secure", "doze_wake_screen_gesture", "0"), Triple("secure", "wake_gesture_enabled", "0"),
        Triple("secure", "doze_quick_pickup_gesture", "0"),
        // Samsung: Always On Display, tap/lift/double-tap to wake, notifications on lock screen
        Triple("system", "aod_mode", "0"), Triple("system", "aod_tap_to_show_mode", "0"),
        Triple("system", "lift_to_wake", "0"), Triple("system", "double_tab_to_wake_up", "0"),
        Triple("secure", "lock_screen_show_notifications", "0"), Triple("secure", "double_tap_to_wake", "0"),
        Triple("system", "motion_pick_up", "0"), Triple("system", "aod_notifications", "0"),
        Triple("global", "stay_on_while_plugged_in", "0"),
        Triple("system", "screen_off_timeout", "10000"),
    )

    // Streams silenced for SOUND: system, ring, music, alarm, notification.
    private val STREAMS = listOf(1, 2, 3, 4, 5)

    private fun sh(vararg cmd: String) = ShellEnv.run(*cmd, timeoutSec = 15)

    fun apply(audio: android.media.AudioManager, on: Boolean, flags: Int, savedState: String): String =
        try { if (on) enable(audio, flags) else { disable(audio, savedState); "" } } catch (t: Throwable) {
            Log.w(TAG, "quiet profile failed", t); if (on) savedState else ""
        }

    private fun enable(audio: android.media.AudioManager, flags: Int): String {
        val state = JSONObject().put("flags", flags)
        val settings = JSONObject()
        val wanted = buildList {
            if (flags and SOUND != 0) addAll(SOUND_SETTINGS)
            if (flags and VIBRATION != 0) addAll(VIBRATION_SETTINGS)
            if (flags and SCREEN != 0) addAll(SCREEN_SETTINGS)
        }
        for ((ns, key, value) in wanted) {
            settings.put("$ns/$key", sh("settings", "get", ns, key).out.trim())
            sh("settings", "put", ns, key, value)
        }
        state.put("settings", settings)
        if (flags and SOUND != 0) {
            val vols = JSONObject()
            for (s in STREAMS) {
                val cur = runCatching { audio.getStreamVolume(s) }.getOrNull() ?: continue
                vols.put("$s", cur)
                val min = runCatching { audio.getStreamMinVolume(s) }.getOrDefault(0)
                runCatching { audio.setStreamVolume(s, min, 0) }.onFailure { Log.w(TAG, "volume $s", it) }
            }
            state.put("volumes", vols)
        }
        // Total silence (Do Not Disturb, no exceptions) also blocks vibration and the screen
        // waking for notifications; use it when the user asked for both silence and no vibration.
        if (flags and SOUND != 0 && flags and VIBRATION != 0) {
            state.put("zen", sh("settings", "get", "global", "zen_mode").out.trim())
            sh("cmd", "notification", "set_dnd", "none")
        }
        Log.i(TAG, "quiet mode ON (flags=$flags, ${wanted.size} settings)")
        return state.toString()
    }

    private fun disable(audio: android.media.AudioManager, saved: String) {
        if (saved.isBlank()) return
        val state = JSONObject(saved)
        state.optJSONObject("settings")?.let { s ->
            for (k in s.keys()) {
                val (ns, key) = k.split('/', limit = 2)
                val v = s.optString(k)
                if (v.isEmpty() || v == "null") sh("settings", "delete", ns, key) else sh("settings", "put", ns, key, v)
            }
        }
        if (state.has("zen")) {
            sh("cmd", "notification", "set_dnd", when (state.optString("zen")) {
                "1" -> "priority"; "2" -> "none"; "3" -> "alarms"; else -> "off"
            })
        }
        state.optJSONObject("volumes")?.let { v ->
            for (s in v.keys()) runCatching { audio.setStreamVolume(s.toInt(), v.optInt(s), 0) }
                .onFailure { Log.w(TAG, "restore volume $s", it) }
        }
        Log.i(TAG, "quiet mode OFF (restored)")
    }
}
