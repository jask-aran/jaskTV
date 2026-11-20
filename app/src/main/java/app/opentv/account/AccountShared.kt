package app.opentv.account

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The account-wide records cores write about the shared TMDB match table, kept here (a small prefs file, so they're
 * there at launch, before sync):
 *  - "lease"/"tmdb": which core builds the table right now ({holder, name, until}). While it's held, no device matches
 *    on its own: it copies the builder's table.
 *  - "snap"/<playlist key>: the builder's snapshot of that playlist's table in the account's files
 *    ({by, base, base_now, delta, delta_now}), for when no core answers.
 */
object AccountShared {
    private val json = Json { ignoreUnknownKeys = true }
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("jask_account_shared", Context.MODE_PRIVATE)
    }

    fun put(coll: String, key: String, value: JsonElement?) {
        val p = prefs ?: return
        if (value == null) p.edit().remove("$coll/$key").apply() else p.edit().putString("$coll/$key", value.toString()).apply()
    }

    fun get(coll: String, key: String): JsonObject? =
        prefs?.getString("$coll/$key", null)?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    /** A core builds the table right now (its claim hasn't run out). */
    fun builderActive(now: Long = System.currentTimeMillis()): Boolean =
        (get("lease", "tmdb")?.get("until")?.jsonPrimitive?.longOrNull ?: 0L) > now
}
