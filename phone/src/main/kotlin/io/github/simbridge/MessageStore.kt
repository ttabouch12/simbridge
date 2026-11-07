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
) {
    companion object {
        const val RECEIVED = 0
        const val SENDING = 1
        const val SENT = 2
        const val FAILED = 3
    }
}

data class Conversation(val key: String, val address: String, val last: Msg, val unread: Int)

/** Texts relayed by the SIM phone, kept on this phone. A tiny SQLite table; one row per message. */
class MessageStore(context: Context) : SQLiteOpenHelper(context, "messages.db", null, 2) {
    private val _version = MutableStateFlow(0L)
    /** Bumps on every change; UI re-queries when it moves. */
    val version: StateFlow<Long> = _version

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE m (id INTEGER PRIMARY KEY AUTOINCREMENT, k TEXT NOT NULL, addr TEXT NOT NULL, " +
            "body TEXT NOT NULL, ts INTEGER NOT NULL, out INTEGER NOT NULL, status INTEGER NOT NULL, rd INTEGER NOT NULL, " +
            "sys INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX m_k ON m(k, ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE m ADD COLUMN sys INTEGER NOT NULL DEFAULT 0")
    }

    /** Remembers the matching row in Android's SMS database (see [SystemSms]). */
    fun setSystemId(id: Long, sysId: Long) {
        writableDatabase.update("m", ContentValues().apply { put("sys", sysId) }, "id=?", arrayOf(id.toString()))
    }

    private fun changed() { _version.value = _version.value + 1 }

    fun add(address: String, body: String, outgoing: Boolean, status: Int, time: Long = System.currentTimeMillis()): Long {
        val id = writableDatabase.insert("m", null, ContentValues().apply {
            put("k", keyOf(address)); put("addr", address); put("body", body); put("ts", time)
            put("out", if (outgoing) 1 else 0); put("status", status); put("rd", if (outgoing) 1 else 0)
        })
        changed()
        return id
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

    /** Deletes a conversation; returns the system SMS ids that were copies of it. */
    fun deleteConversation(key: String): List<Long> {
        val sys = ArrayList<Long>()
        readableDatabase.rawQuery("SELECT sys FROM m WHERE k=? AND sys>0", arrayOf(key)).use {
            while (it.moveToNext()) sys += it.getLong(0)
        }
        writableDatabase.delete("m", "k=?", arrayOf(key))
        changed()
        return sys
    }

    fun conversations(): List<Conversation> {
        val unread = HashMap<String, Int>()
        readableDatabase.rawQuery("SELECT k, COUNT(*) FROM m WHERE rd=0 GROUP BY k", null).use {
            while (it.moveToNext()) unread[it.getString(0)] = it.getInt(1)
        }
        val out = ArrayList<Conversation>()
        readableDatabase.rawQuery("SELECT m.* FROM m JOIN (SELECT k AS kk, MAX(id) AS mid FROM m GROUP BY k) t " +
            "ON m.id = t.mid ORDER BY m.ts DESC", null).use {
            while (it.moveToNext()) {
                val msg = it.msg()
                out += Conversation(msg.key, msg.address, msg, unread[msg.key] ?: 0)
            }
        }
        return out
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
    )

    companion object {
        /**
         * One conversation per correspondent, however the number is written:
         * +90 532 123 45 67, 0532 123 4567 and 5321234567 all map to the last ten digits.
         * Short codes and alphanumeric senders ("TURKTELEKOM") are kept as they are.
         */
        fun keyOf(address: String): String {
            val digits = address.filter { it.isDigit() }
            return when {
                digits.length >= 10 && digits.length >= address.count { it.isLetterOrDigit() } -> digits.takeLast(10)
                digits.isNotEmpty() && digits.length == address.count { it.isLetterOrDigit() } -> digits
                else -> address.trim().uppercase()
            }
        }
    }
}
