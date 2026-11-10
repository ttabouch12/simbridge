package io.github.simbridge

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Msg(
    val id: Long,
    val key: String,
    val address: String,
    val body: String,
    val time: Long,
    val outgoing: Boolean,
    val status: Int,
    val read: Boolean,
    /** SIM slot on a dual-SIM gateway (0 = not known / single SIM). */
    val sim: Int = 0,
    /** MMS attachments as JSON (see [Attachments]); empty for plain texts. */
    val att: String = "",
    val starred: Boolean = false,
) {
    companion object {
        const val RECEIVED = 0
        const val SENDING = 1
        const val SENT = 2
        const val FAILED = 3
        const val DELIVERED = 4
    }
}

data class Conversation(val key: String, val address: String, val last: Msg, val unread: Int,
                        val pinned: Boolean = false, val archived: Boolean = false)

/** A text waiting for its send time. */
data class Scheduled(val id: Long, val address: String, val body: String, val at: Long, val sim: Int)

/** Texts relayed by the SIM phone, kept on this phone. A tiny SQLite table; one row per message. */
class MessageStore(context: Context) : SQLiteOpenHelper(context, "messages.db", null, 4) {
    private val _version = MutableStateFlow(0L)
    /** Bumps on every change; UI re-queries when it moves. */
    val version: StateFlow<Long> = _version

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE m (id INTEGER PRIMARY KEY AUTOINCREMENT, k TEXT NOT NULL, addr TEXT NOT NULL, " +
            "body TEXT NOT NULL, ts INTEGER NOT NULL, out INTEGER NOT NULL, status INTEGER NOT NULL, rd INTEGER NOT NULL, " +
            "sys INTEGER NOT NULL DEFAULT 0, sim INTEGER NOT NULL DEFAULT 0, att TEXT NOT NULL DEFAULT '', " +
            "star INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX m_k ON m(k, ts)")
        extraTables(db)
    }

    /** Per-conversation flags (pinned, archived) and texts scheduled for later. */
    private fun extraTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS c (k TEXT PRIMARY KEY, pinned INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS sched (id INTEGER PRIMARY KEY AUTOINCREMENT, addr TEXT NOT NULL, body TEXT NOT NULL, " +
            "at INTEGER NOT NULL, sim INTEGER NOT NULL DEFAULT 0)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE m ADD COLUMN sys INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE m ADD COLUMN sim INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE m ADD COLUMN att TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE m ADD COLUMN star INTEGER NOT NULL DEFAULT 0")
            extraTables(db)
        }
    }

    /** Remembers the matching row in Android's SMS database (see [SystemSms]). */
    fun setSystemId(id: Long, sysId: Long) {
        writableDatabase.update("m", ContentValues().apply { put("sys", sysId) }, "id=?", arrayOf(id.toString()))
    }

    /** True if the same text from [address] already arrived within [windowMs] (a resend, not a new text). */
    fun isRecentDuplicate(address: String, body: String, windowMs: Long = 15 * 60_000L): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM m WHERE k=? AND out=0 AND body=? AND ts>? LIMIT 1",
        arrayOf(keyOf(address), body, (System.currentTimeMillis() - windowMs).toString())).use { it.moveToFirst() }

    fun lastIncomingSim(key: String): Int? = readableDatabase.rawQuery(
        "SELECT sim FROM m WHERE k=? AND out=0 AND sim>0 ORDER BY ts DESC LIMIT 1", arrayOf(key))
        .use { if (it.moveToFirst()) it.getInt(0) else null }

    fun systemId(id: Long): Long? = readableDatabase.rawQuery("SELECT sys FROM m WHERE id=?", arrayOf(id.toString()))
        .use { if (it.moveToFirst()) it.getLong(0).takeIf { v -> v > 0 } else null }

    private fun changed() { _version.value = _version.value + 1 }

    fun add(address: String, body: String, outgoing: Boolean, status: Int, time: Long = System.currentTimeMillis(),
            sim: Int = 0, att: String = "", read: Boolean = outgoing): Long {
        val id = writableDatabase.insert("m", null, ContentValues().apply {
            put("k", keyOf(address)); put("addr", address); put("body", body); put("ts", time)
            put("out", if (outgoing) 1 else 0); put("status", status); put("rd", if (read) 1 else 0)
            put("sim", sim); put("att", att)
        })
        // A new incoming text brings an archived conversation back to the list.
        if (!outgoing) writableDatabase.execSQL("UPDATE c SET archived=0 WHERE k=?", arrayOf(keyOf(address)))
        changed()
        return id
    }

    /** Every message, oldest first (backup). */
    fun all(): List<Msg> = readableDatabase.rawQuery("SELECT * FROM m ORDER BY ts, id", null).use { c ->
        ArrayList<Msg>().apply { while (c.moveToNext()) add(c.msg()) }
    }

    /** Status only moves forward (a late "sent" never hides "delivered"), except to FAILED. */
    fun advanceStatus(id: Long, status: Int) {
        val cur = get(id)?.status ?: return
        val rank = mapOf(Msg.SENDING to 0, Msg.SENT to 1, Msg.DELIVERED to 2)
        if (status == Msg.FAILED || (rank[status] ?: 0) > (rank[cur] ?: -1)) setStatus(id, status)
    }

    fun setStatus(id: Long, status: Int) {
        writableDatabase.update("m", ContentValues().apply { put("status", status) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun get(id: Long): Msg? = readableDatabase.rawQuery("SELECT * FROM m WHERE id=?", arrayOf(id.toString()))
        .use { if (it.moveToFirst()) it.msg() else null }

    /** Marks a conversation read; returns the system SMS ids that were unread, to mirror the change. */
    fun markRead(key: String): List<Long> {
        val sys = ArrayList<Long>()
        readableDatabase.rawQuery("SELECT sys FROM m WHERE k=? AND rd=0 AND sys>0", arrayOf(key)).use {
            while (it.moveToNext()) sys += it.getLong(0)
        }
        val n = writableDatabase.update("m", ContentValues().apply { put("rd", 1) }, "k=? AND rd=0", arrayOf(key))
        if (n > 0) changed()
        return sys
    }

    /** Attachment JSON of every picture message in a conversation (to delete the files). */
    fun attachmentsIn(key: String): List<String> = readableDatabase.rawQuery("SELECT att FROM m WHERE k=? AND att<>''", arrayOf(key))
        .use { c -> ArrayList<String>().apply { while (c.moveToNext()) add(c.getString(0)) } }

    /** Deletes a conversation; returns the system SMS ids that were copies of it. */
    fun deleteConversation(key: String): List<Long> {
        val sys = ArrayList<Long>()
        readableDatabase.rawQuery("SELECT sys FROM m WHERE k=? AND sys>0", arrayOf(key)).use {
            while (it.moveToNext()) sys += it.getLong(0)
        }
        writableDatabase.delete("m", "k=?", arrayOf(key))
        writableDatabase.delete("c", "k=?", arrayOf(key))
        changed()
        return sys
    }

    // ------------------------------------------------------------ flags, single messages, search

    private fun flag(keys: Collection<String>, column: String, on: Boolean) {
        val db = writableDatabase
        keys.forEach { k ->
            db.execSQL("INSERT OR IGNORE INTO c (k) VALUES (?)", arrayOf(k))
            db.execSQL("UPDATE c SET $column=? WHERE k=?", arrayOf<Any>(if (on) 1 else 0, k))
        }
        changed()
    }

    fun setPinned(keys: Collection<String>, on: Boolean) = flag(keys, "pinned", on)
    fun setArchived(keys: Collection<String>, on: Boolean) = flag(keys, "archived", on)

    /** Marks a conversation unread again (its latest incoming message). */
    fun markUnread(key: String) {
        writableDatabase.execSQL("UPDATE m SET rd=0 WHERE id=(SELECT MAX(id) FROM m WHERE k=? AND out=0)", arrayOf(key))
        changed()
    }

    fun setStarred(ids: Collection<Long>, on: Boolean) {
        ids.forEach { writableDatabase.execSQL("UPDATE m SET star=? WHERE id=?", arrayOf<Any>(if (on) 1 else 0, it)) }
        changed()
    }

    /** Deletes single messages; returns their rows (for the system SMS copies and attachment files). */
    fun deleteMessages(ids: Collection<Long>): List<Msg> {
        val gone = ids.mapNotNull { get(it) }
        ids.forEach { writableDatabase.delete("m", "id=?", arrayOf(it.toString())) }
        changed()
        return gone
    }

    /** Messages containing [query] (newest first), or the starred ones when [starred]. */
    fun search(query: String, starred: Boolean = false, limit: Int = 60): List<Msg> {
        val like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val where = if (starred) "star=1 AND body LIKE ? ESCAPE '\\'" else "body LIKE ? ESCAPE '\\'"
        return readableDatabase.rawQuery("SELECT * FROM m WHERE $where ORDER BY ts DESC LIMIT $limit", arrayOf(like)).use { c ->
            ArrayList<Msg>().apply { while (c.moveToNext()) add(c.msg()) }
        }
    }

    // ------------------------------------------------------------ scheduled texts

    fun schedule(address: String, body: String, at: Long, sim: Int): Long {
        val id = writableDatabase.insert("sched", null, ContentValues().apply {
            put("addr", address); put("body", body); put("at", at); put("sim", sim)
        })
        changed()
        return id
    }

    /** Scheduled texts, soonest first; only those due by [dueBy] when given. */
    fun scheduled(dueBy: Long = Long.MAX_VALUE): List<Scheduled> =
        readableDatabase.rawQuery("SELECT id, addr, body, at, sim FROM sched WHERE at<=? ORDER BY at", arrayOf(dueBy.toString())).use { c ->
            ArrayList<Scheduled>().apply { while (c.moveToNext()) add(Scheduled(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4))) }
        }

    fun unschedule(id: Long) {
        writableDatabase.delete("sched", "id=?", arrayOf(id.toString()))
        changed()
    }

    fun conversations(): List<Conversation> {
        val flags = HashMap<String, Pair<Boolean, Boolean>>()
        readableDatabase.rawQuery("SELECT k, pinned, archived FROM c WHERE pinned=1 OR archived=1", null).use {
            while (it.moveToNext()) flags[it.getString(0)] = (it.getInt(1) == 1) to (it.getInt(2) == 1)
        }
        val unread = HashMap<String, Int>()
        readableDatabase.rawQuery("SELECT k, COUNT(*) FROM m WHERE rd=0 GROUP BY k", null).use {
            while (it.moveToNext()) unread[it.getString(0)] = it.getInt(1)
        }
        val out = ArrayList<Conversation>()
        readableDatabase.rawQuery("SELECT m.* FROM m JOIN (SELECT k AS kk, MAX(id) AS mid FROM m GROUP BY k) t " +
            "ON m.id = t.mid ORDER BY m.ts DESC", null).use {
            while (it.moveToNext()) {
                val msg = it.msg()
                val f = flags[msg.key]
                out += Conversation(msg.key, msg.address, msg, unread[msg.key] ?: 0, f?.first == true, f?.second == true)
            }
        }
        // Pinned conversations first; the newest-first order is kept inside each group.
        return out.sortedByDescending { it.pinned }
    }

    fun thread(key: String): List<Msg> {
        val out = ArrayList<Msg>()
        readableDatabase.rawQuery("SELECT * FROM m WHERE k=? ORDER BY ts, id", arrayOf(key)).use {
            while (it.moveToNext()) out += it.msg()
        }
        return out
    }

    /** Recent incoming messages of a conversation, for the notification. */
    fun unreadIn(key: String): List<Msg> = thread(key).filter { !it.read && !it.outgoing }.takeLast(8)

    private fun Cursor.msg() = Msg(
        id = getLong(getColumnIndexOrThrow("id")),
        key = getString(getColumnIndexOrThrow("k")),
        address = getString(getColumnIndexOrThrow("addr")),
        body = getString(getColumnIndexOrThrow("body")),
        time = getLong(getColumnIndexOrThrow("ts")),
        outgoing = getInt(getColumnIndexOrThrow("out")) == 1,
        status = getInt(getColumnIndexOrThrow("status")),
        read = getInt(getColumnIndexOrThrow("rd")) == 1,
        sim = getInt(getColumnIndexOrThrow("sim")),
        att = getString(getColumnIndexOrThrow("att")) ?: "",
        starred = getInt(getColumnIndexOrThrow("star")) == 1,
    )

    companion object {
        /**
         * One conversation per correspondent, however the number is written:
         * +90 532 123 45 67, 0532 123 4567 and 5321234567 all map to the last ten digits.
         * Short codes and alphanumeric senders ("TURKTELEKOM") are kept as they are.
         */
        fun keyOf(address: String): String {
            if (';' in address) return "G:" + Group.members(address).map { keyOf(it) }.sorted().joinToString(",")
            val digits = address.filter { it.isDigit() }
            return when {
                digits.length >= 10 && digits.length >= address.count { it.isLetterOrDigit() } -> digits.takeLast(10)
                digits.isNotEmpty() && digits.length == address.count { it.isLetterOrDigit() } -> digits
                else -> address.trim().uppercase()
            }
        }
    }
}
