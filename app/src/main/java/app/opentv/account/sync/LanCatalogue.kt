package app.opentv.account.sync

import androidx.sqlite.db.SupportSQLiteDatabase
import app.opentv.data.model.Source
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

/**
 * LAN sync: a device on the same Wi-Fi that already has a playlist source's catalogue copies it to one that
 * doesn't, instead of the new device downloading tens of megabytes from the provider and matching every title
 * with TMDB again. Rows travel over [LanLink.fetch], sealed with the account key.
 *
 * Only ever fills an empty catalogue (a first sync): a device that already has channels or movies for a source
 * refreshes from the provider as before. Rows refer to each other by the provider's ids (stream id, series id,
 * category id), never by local row ids, so they copy across with only `sourceId` rewritten; local row ids are
 * left out and assigned fresh.
 */
object LanCatalogue {
    /** Two parts, fetched when each would otherwise be downloaded: live channels, and movies plus series. */
    const val LIVE = "live"
    const val VOD = "vod"
    /** TMDB matches only: asked for before each matching batch, so a device matches only what no device nearby has. */
    const val TMDB = "tmdb"

    /** Names a source the same way on every device (the playlist itself, not this device's row id). */
    fun sourceKey(s: Source): String {
        val text = "${s.kind}|${s.url.trimEnd('/')}|${s.username.orEmpty()}"
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Tables per part: (table, filter by source, leave out the local `id`). */
    private val parts = mapOf(
        LIVE to listOf(Table("categories", bySource = true, dropId = false), Table("channels", bySource = true, dropId = true)),
        VOD to listOf(
            Table("categories", bySource = true, dropId = false),
            Table("movies", bySource = true, dropId = true),
            Table("series", bySource = true, dropId = true),
            Table("episodes", bySource = true, dropId = true),
            Table("movie_inventory_snapshots", bySource = true, dropId = false),
            Table("movie_inventory_assets", bySource = true, dropId = false),
            Table("movie_identity_matches", bySource = true, dropId = false),
            // TMDB results are shared by every source: whatever the other device has matched already.
            Table("canonical_movies", bySource = false, dropId = false),
            Table("canonical_shows", bySource = false, dropId = false),
        ),
        TMDB to listOf(
            Table("movie_identity_matches", bySource = true, dropId = false),
            Table("canonical_movies", bySource = false, dropId = false),
            Table("canonical_shows", bySource = false, dropId = false),
        ),
    )

    private data class Table(val name: String, val bySource: Boolean, val dropId: Boolean)

    /** Whether this device already has [part] for [sourceId] (then it isn't filled from another device). */
    fun has(db: SupportSQLiteDatabase, sourceId: Long, part: String): Boolean {
        val tables = if (part == LIVE) listOf("channels") else listOf("movies", "series")
        return tables.any { t -> db.query("SELECT 1 FROM $t WHERE sourceId = ? LIMIT 1", arrayOf<Any>(sourceId)).use { it.moveToFirst() } }
    }

    /** The serving side: writes [part] of [source] in chunks. Nothing for it here: one empty, not-found chunk. */
    fun export(db: SupportSQLiteDatabase, sources: List<Source>, request: LanLink.BulkRequest, emit: (LanLink.BulkChunk) -> Boolean) {
        val source = sources.firstOrNull { sourceKey(it) == request.source }
        val tables = parts[request.part]
        if (source == null || tables == null || !has(db, source.id, request.part)) { emit(LanLink.BulkChunk(done = true, found = false)); return }
        for (t in tables) {
            val sql = "SELECT * FROM ${t.name}" + if (t.bySource) " WHERE sourceId = ?" else ""
            db.query(sql, if (t.bySource) arrayOf<Any>(source.id) else emptyArray()).use { c ->
                val keep = (0 until c.columnCount).filter { !(t.dropId && c.getColumnName(it) == "id") }
                val columns = keep.map { c.getColumnName(it) }
                val rows = ArrayList<List<JsonElement>>(CHUNK)
                while (c.moveToNext()) {
                    rows += keep.map { i ->
                        when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> JsonNull
                            android.database.Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(i))
                            android.database.Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(i))
                            else -> JsonPrimitive(c.getString(i))
                        }
                    }
                    if (rows.size >= CHUNK) { if (!emit(LanLink.BulkChunk(t.name, columns, rows.toList()))) return; rows.clear() }
                }
                if (rows.isNotEmpty() && !emit(LanLink.BulkChunk(t.name, columns, rows.toList()))) return
            }
        }
        emit(LanLink.BulkChunk(done = true))
    }

    /**
     * The receiving side: each chunk is written in its own transaction (SQLite transactions belong to a thread,
     * and chunks arrive as the network delivers them). A copy cut off halfway leaves some rows: harmless, since
     * the caller then downloads from the provider as usual, which fills in the rest.
     */
    @Suppress("DEPRECATION") // Room's own transaction calls, so its screens see the new rows when each commits.
    /**
     * [replace]: the rows come from the account's builder (a core, or its snapshot), whose table is the account's, so
     * they replace what's here, except matches chosen by hand on this device. Otherwise (another device's copy) only
     * rows this device doesn't have are added.
     */
    class Import(private val room: androidx.room.RoomDatabase, private val sourceId: Long, private val replace: Boolean = false) {
        var rows = 0; private set
        fun put(chunk: LanLink.BulkChunk) {
            if (chunk.rows.isEmpty() || chunk.table !in ALLOWED) return
            // Column names come from the other device: only plain identifiers reach the SQL.
            require(chunk.columns.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }) { "Bad column" }
            val src = chunk.columns.indexOf("sourceId")
            val columns = chunk.columns.joinToString { "`$it`" }
            val values = chunk.columns.joinToString { "?" }
            val sql = when {
                !replace -> "INSERT OR IGNORE INTO ${chunk.table} ($columns) VALUES ($values)"
                // A match chosen by hand stays; everything else takes the builder's.
                chunk.table == "movie_identity_matches" -> "INSERT INTO movie_identity_matches ($columns) VALUES ($values) " +
                    "ON CONFLICT(sourceId, streamId) DO UPDATE SET " +
                    chunk.columns.filter { it != "sourceId" && it != "streamId" && it != "manual" }.joinToString { "`$it` = excluded.`$it`" } +
                    " WHERE movie_identity_matches.manual = 0"
                else -> "INSERT OR REPLACE INTO ${chunk.table} ($columns) VALUES ($values)"
            }
            room.beginTransaction()
            try {
                val stmt = room.compileStatement(sql)
                for (row in chunk.rows) {
                    stmt.clearBindings()
                    row.forEachIndexed { i, v ->
                        val at = i + 1
                        when {
                            i == src -> stmt.bindLong(at, sourceId)
                            v is JsonNull -> stmt.bindNull(at)
                            v is JsonPrimitive && v.isString -> stmt.bindString(at, v.content)
                            v is JsonPrimitive && v.longOrNull != null -> stmt.bindLong(at, v.longOrNull!!)
                            v is JsonPrimitive && v.doubleOrNull != null -> stmt.bindDouble(at, v.doubleOrNull!!)
                            else -> stmt.bindNull(at)
                        }
                    }
                    stmt.executeInsert()
                    rows++
                }
                stmt.close()
                room.setTransactionSuccessful()
            } finally { room.endTransaction() }
        }
    }

    private val ALLOWED = parts.values.flatten().map { it.name }.toSet()
    private const val CHUNK = 1500
}
