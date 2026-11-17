package app.opentv.account.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * This device's replica of the account's records, in its own small database (not the app's, so no migration):
 * every record's collection, key, value and stamp, whether it still needs sending, and whether it couldn't be
 * applied yet (a resume point for a movie this device hasn't loaded). Plus stable ids for this device's
 * playlists, profiles and collections, so records name them the same way on every device.
 */
class SyncStore(context: Context, name: String = "jask-sync.db") : SQLiteOpenHelper(context, name, null, 1) {
    data class Rec(val id: String, val coll: String, val key: String, val stamp: String, val value: String?, val seq: Long, val state: Int)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records (id TEXT PRIMARY KEY, coll TEXT NOT NULL, key TEXT NOT NULL, stamp TEXT NOT NULL, value TEXT, seq INTEGER NOT NULL, state INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX records_key ON records(coll, key)")
        db.execSQL("CREATE INDEX records_state ON records(state)")
        db.execSQL("CREATE TABLE ids (kind TEXT NOT NULL, local TEXT NOT NULL, uid TEXT NOT NULL, PRIMARY KEY (kind, local))")
        db.execSQL("CREATE UNIQUE INDEX ids_uid ON ids(kind, uid)")
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun Rec(c: android.database.Cursor) = Rec(c.getString(0), c.getString(1), c.getString(2), c.getString(3),
        if (c.isNull(4)) null else c.getString(4), c.getLong(5), c.getInt(6))
    private val cols = arrayOf("id", "coll", "key", "stamp", "value", "seq", "state")

    private fun query(where: String?, args: Array<String>?): List<Rec> =
        readableDatabase.query("records", cols, where, args, null, null, null).use { c -> buildList { while (c.moveToNext()) add(Rec(c)) } }

    fun byId(id: String): Rec? = query("id = ?", arrayOf(id)).firstOrNull()
    fun byKey(coll: String, key: String): Rec? = query("coll = ? AND key = ?", arrayOf(coll, key)).firstOrNull()
    fun inColl(coll: String): List<Rec> = query("coll = ?", arrayOf(coll))
    fun withState(state: Int): List<Rec> = query("state = ?", arrayOf(state.toString()))
    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM records", null).use { it.moveToFirst(); it.getInt(0) }

    fun put(r: Rec) {
        writableDatabase.insertWithOnConflict("records", null, ContentValues().apply {
            put("id", r.id); put("coll", r.coll); put("key", r.key); put("stamp", r.stamp)
            if (r.value == null) putNull("value") else put("value", r.value)
            put("seq", r.seq); put("state", r.state)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Marks a sent record as held by the server, unless it was rewritten since (then it still needs sending). */
    fun acked(id: String, stamp: String, seq: Long) {
        writableDatabase.execSQL("UPDATE records SET state = $SYNCED, seq = ? WHERE id = ? AND stamp = ? AND state = $UNSENT", arrayOf<Any>(seq, id, stamp))
    }

    fun setState(id: String, state: Int) = writableDatabase.execSQL("UPDATE records SET state = ? WHERE id = ?", arrayOf<Any>(state, id))

    fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try { return block().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }

    // ---- Stable ids ----

    fun uidFor(kind: String, local: String): String? =
        readableDatabase.rawQuery("SELECT uid FROM ids WHERE kind = ? AND local = ?", arrayOf(kind, local)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun localFor(kind: String, uid: String): String? =
        readableDatabase.rawQuery("SELECT local FROM ids WHERE kind = ? AND uid = ?", arrayOf(kind, uid)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun link(kind: String, local: String, uid: String) {
        writableDatabase.execSQL("DELETE FROM ids WHERE kind = ? AND (local = ? OR uid = ?)", arrayOf(kind, local, uid))
        writableDatabase.execSQL("INSERT INTO ids (kind, local, uid) VALUES (?, ?, ?)", arrayOf(kind, local, uid))
    }
    fun unlink(kind: String, local: String) = writableDatabase.execSQL("DELETE FROM ids WHERE kind = ? AND local = ?", arrayOf(kind, local))

    // ---- Meta ----

    fun meta(k: String): String? = readableDatabase.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun setMeta(k: String, v: String) = writableDatabase.execSQL("INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)", arrayOf(k, v))

    /** Forgets everything (signing out). */
    fun clear() = transaction { listOf("records", "ids", "meta").forEach { writableDatabase.execSQL("DELETE FROM $it") } }

    companion object {
        /** Held by the server and applied here. */
        const val SYNCED = 0
        /** Written here; not yet acknowledged by the server. */
        const val UNSENT = 1
        /** From the server, newer than what's here, but not applicable yet (what it names hasn't loaded). */
        const val PENDING = 2
    }
}
