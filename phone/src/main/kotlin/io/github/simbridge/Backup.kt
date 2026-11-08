package io.github.simbridge

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Export / import of this phone's SimBridge data as one JSON file (texts, blocked numbers,
 * settings). The pairing key is left out on purpose: scan the SIM phone's code on a new phone.
 */
object Backup {
    private const val FORMAT = "simbridge-backup"

    fun export(ctx: Context, to: Uri): Int {
        val core = LinkApp.core(ctx)
        val msgs = core.store.all()
        val o = JSONObject()
            .put("format", FORMAT).put("version", 1).put("exportedAt", System.currentTimeMillis())
            .put("settings", JSONObject()
                .put("mirrorToSystem", core.prefs.mirrorToSystem)
                .put("autoRecord", core.prefs.autoRecord)
                .put("blockHidden", core.prefs.blockHidden))
            .put("blocked", JSONArray(core.prefs.blocked.toList()))
            .put("messages", JSONArray().apply {
                msgs.forEach { m ->
                    put(JSONObject().put("address", m.address).put("body", m.body).put("time", m.time)
                        .put("out", m.outgoing).put("status", m.status).put("read", m.read).put("sim", m.sim)
                        .apply { if (m.att.isNotEmpty()) put("att", m.att) })
                }
            })
        // Picture-message files go in too, or restored chats would show empty bubbles.
        o.put("files", JSONObject().apply {
            msgs.forEach { m -> MmsInfo.parse(m.att)?.files?.forEach { a ->
                val f = Mms.fileOf(ctx, a)
                if (f.exists() && !has(a.file)) put(a.file, android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP))
            } }
        })
        ctx.contentResolver.openOutputStream(to, "wt")!!.use { it.write(o.toString().toByteArray()) }
        return msgs.size
    }

    /** Merges a backup into this phone; returns how many texts were added (duplicates skipped). */
    fun import(ctx: Context, from: Uri): Int {
        val core = LinkApp.core(ctx)
        val text = ctx.contentResolver.openInputStream(from)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val o = JSONObject(text)
        require(o.optString("format") == FORMAT) { "Not a SimBridge backup" }
        o.optJSONObject("settings")?.let { s ->
            core.prefs.mirrorToSystem = s.optBoolean("mirrorToSystem", core.prefs.mirrorToSystem)
            core.prefs.autoRecord = s.optBoolean("autoRecord", core.prefs.autoRecord)
            core.prefs.blockHidden = s.optBoolean("blockHidden", core.prefs.blockHidden)
        }
        o.optJSONObject("files")?.let { files ->
            val dir = java.io.File(ctx.filesDir, "mms").apply { mkdirs() }
            for (name in files.keys()) {
                if ('/' in name || name.startsWith(".")) continue
                val f = java.io.File(dir, name)
                if (!f.exists()) runCatching { f.writeBytes(android.util.Base64.decode(files.getString(name), android.util.Base64.DEFAULT)) }
            }
        }
        o.optJSONArray("blocked")?.let { a -> core.prefs.blocked = core.prefs.blocked + (0 until a.length()).map { a.getString(it) } }
        val have = core.store.all().map { Triple(MessageStore.keyOf(it.address), it.time, it.body) }.toHashSet()
        var added = 0
        val a = o.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until a.length()) {
            val m = a.getJSONObject(i)
            val address = m.optString("address"); val body = m.optString("body"); val time = m.optLong("time")
            if (address.isEmpty() || !have.add(Triple(MessageStore.keyOf(address), time, body))) continue
            core.store.add(address, body, m.optBoolean("out"), m.optInt("status"), time, m.optInt("sim"), m.optString("att"),
                read = m.optBoolean("read", true))
            added++
        }
        core.blockRulesChanged()
        return added
    }
}

data class Recording(val uri: Uri, val name: String, val date: Long, val durationMs: Long, val size: Long)

/** Call recordings this app saved in Recordings/SimBridge. */
object Recordings {
    fun list(ctx: Context): List<Recording> {
        val out = ArrayList<Recording>()
        val col = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        runCatching {
            ctx.contentResolver.query(col, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE),
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?", arrayOf("${Environment.DIRECTORY_RECORDINGS}/SimBridge%"),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext()) out += Recording(ContentUris.withAppendedId(col, c.getLong(0)), c.getString(1) ?: "",
                    c.getLong(2) * 1000, c.getLong(3), c.getLong(4))
            }
        }.onFailure { Log.w("recordings: list failed", it) }
        return out
    }
}
