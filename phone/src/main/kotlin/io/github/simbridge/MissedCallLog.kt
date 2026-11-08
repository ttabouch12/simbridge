package io.github.simbridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog

/** Recent missed calls from this phone's call log (every call here comes through the SIM phone). */
object MissedCallLog {
    data class Call(val number: String, val name: String?, val at: Long)

    fun recent(ctx: Context, limit: Int): List<Call> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return emptyList()
        return runCatching {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE),
                "${CallLog.Calls.TYPE}=?", arrayOf(CallLog.Calls.MISSED_TYPE.toString()),
                "${CallLog.Calls.DATE} DESC")?.use { c ->
                buildList {
                    while (c.moveToNext() && size < limit) add(Call(c.getString(0).orEmpty(), c.getString(1)?.ifBlank { null }, c.getLong(2)))
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }
}
