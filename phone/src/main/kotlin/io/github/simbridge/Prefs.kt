package io.github.simbridge

import android.content.Context

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("link", Context.MODE_PRIVATE)

    var password: String
        get() = p.getString("password", "") ?: ""
        set(v) = p.edit().putString("password", v).apply()

    /** Last address the SIM phone answered on, tried first after a restart. */
    var lastHost: String?
        get() = p.getString("last_host", null)
        set(v) = p.edit().putString("last_host", v).apply()

    var lastPort: Int
        get() = p.getInt("last_port", 5062)
        set(v) = p.edit().putInt("last_port", v).apply()

    val user: String get() = "phone"

    /** Also copy texts into the system SMS database (shows them in the default Messages app). */
    var mirrorToSystem: Boolean
        get() = p.getBoolean("mirror_system", true)
        set(v) = p.edit().putBoolean("mirror_system", v).apply()
}
