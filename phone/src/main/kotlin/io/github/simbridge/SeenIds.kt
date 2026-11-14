package io.github.simbridge

import android.content.Context

/** Ids of texts already received from the SIM phone (the last [MAX]), kept across restarts. */
class SeenIds(context: Context) {
    private val p = context.getSharedPreferences("seen_ids", Context.MODE_PRIVATE)
    private val ids = LinkedHashSet(p.getString("ids", "").orEmpty().split(',').filter { it.isNotBlank() })

    /** True if [id] is new (and now remembered); false for a copy. */
    @Synchronized fun add(id: String): Boolean {
        if (!ids.add(id)) return false
        while (ids.size > MAX) ids.remove(ids.first())
        p.edit().putString("ids", ids.joinToString(",")).apply()
        return true
    }

    private companion object { const val MAX = 500 }
}
