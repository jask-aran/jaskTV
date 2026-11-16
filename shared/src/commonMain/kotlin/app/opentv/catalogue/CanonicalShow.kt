package app.opentv.catalogue

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import app.opentv.core.AppSettings
import app.opentv.data.model.Series
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * TMDB TV metadata for one provider TMDB hint, checked against the provider's own names.
 * [validated] = false records a checked hint that didn't match (or no longer exists), so it is not
 * fetched again; its metadata is never shown. Shows still group by provider hint: this is clean
 * artwork and titles for those groups, not a full canonical TV identity (no per-row match table yet).
 */
@Entity(tableName = "canonical_shows")
data class CanonicalShow(
    @PrimaryKey val tmdbId: Int,
    val validated: Boolean,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val title: String?,
    val originalTitle: String?,
    val year: Int?,
    val originalLanguage: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val synopsis: String?,
    val genres: String?,
    val rating: Double?,
    val seasonCount: Int?,
    val checkedMillis: Long,
    /** First TMDB network ("Apple TV+", "HBO"); null until a detail request has filled it. */
    val network: String? = null,
    /** TMDB status: "Returning Series", "Ended", "Canceled", "In Production"…; null until detailed. */
    val status: String? = null,
    /** TMDB logo for [network] (w154 PNG); "" when TMDB has none, null until detailed. */
    val networkLogo: String? = null,
) {
    /** "Running" / "Ended" / "Cancelled" for chips; null when unknown. */
    val statusLabel: String? get() = when (status) {
        null -> null
        "Ended" -> "Ended"
        "Canceled" -> "Cancelled"
        else -> "Running"
    }
}

/** One unchecked hint with every provider name that carries it. */
data class PendingShowHint(val tmdbId: Int, val names: String, val year: Int?)

private const val VALID_HINT = "trim(tmdbId) != '' AND trim(tmdbId) NOT GLOB '*[^0-9]*' AND CAST(tmdbId AS INTEGER) BETWEEN 1 AND 2147483647"

@Dao
interface CanonicalShowDao {
    /** Saved and recently added shows first, so what you look at gets clean art soonest. */
    @Query("""SELECT CAST(tmdbId AS INTEGER) AS tmdbId, group_concat(DISTINCT name) AS names, MIN(year) AS year
        FROM series WHERE $VALID_HINT AND NOT EXISTS (SELECT 1 FROM canonical_shows c WHERE c.tmdbId = CAST(series.tmdbId AS INTEGER))
        GROUP BY CAST(tmdbId AS INTEGER) ORDER BY MAX(favourite) DESC, MAX(addedMillis) DESC LIMIT :limit""")
    suspend fun pending(limit: Int): List<PendingShowHint>

    @Query("SELECT * FROM canonical_shows WHERE tmdbId = :id AND validated = 1")
    suspend fun validated(id: Int): CanonicalShow?

    @Upsert suspend fun putAll(shows: List<CanonicalShow>)

    /** Validated rows filled from a TMDB list entry, still missing network/status. */
    @Query("SELECT * FROM canonical_shows WHERE validated = 1 AND (status IS NULL OR networkLogo IS NULL) LIMIT :limit")
    suspend fun needsDetail(limit: Int): List<CanonicalShow>

    @Query("SELECT * FROM canonical_shows WHERE tmdbId IN (:ids)")
    suspend fun byIds(ids: List<Int>): List<CanonicalShow>
}

/** Presentation copy only. Never persist this over the provider row. */
fun Series.withCanonical(show: CanonicalShow): Series = copy(
    name = show.title ?: name, year = show.year ?: year, posterUrl = show.posterUrl ?: posterUrl,
    backdropUrl = show.backdropUrl ?: backdropUrl, plot = show.synopsis ?: plot, genre = show.genres ?: genre,
    rating = show.rating ?: rating,
)

fun Series.tmdbHint(): Int? = tmdbId?.trim()?.toIntOrNull()?.takeIf { it > 0 }

object ShowMatching {
    /**
     * Accept TMDB's show for a hint when any provider name equals its title or original title, or
     * when the first-air year matches and one title contains the other. Mirrors the season-poster check.
     */
    fun accepts(names: List<String>, year: Int?, title: String, originalTitle: String, tmdbYear: Int?): Boolean {
        val keys = listOf(title, originalTitle).map(MovieMatching::titleKey).filter(String::isNotBlank)
        if (keys.isEmpty()) return false
        return names.map { MovieMatching.titleKey(MovieMatching.query(it)) }.filter(String::isNotBlank).any { wanted ->
            wanted in keys || (year != null && year == tmdbYear && keys.any { it in wanted || wanted in it })
        }
    }
}

class CanonicalShowResolver(private val http: OkHttpClient, private val settings: AppSettings) {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun fetch(id: Int): JsonObject? {
        delay(80) // TMDB allows far more; serial requests keep this gentle (~12/s).
        val url = "https://api.themoviedb.org/3/tv/$id".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", settings.tmdbApiKey.value.trim()).build()
        return http.newCall(Request.Builder().url(url).build()).execute().use {
            if (it.code == 404) return null
            if (!it.isSuccessful) throw IOException("Metadata service HTTP ${it.code}")
            json.parseToJsonElement(it.body?.string() ?: throw IOException("Empty metadata response")).jsonObject
        }
    }

    suspend fun resolve(hint: PendingShowHint): CanonicalShow {
        val o = fetch(hint.tmdbId) ?: return rejected(hint.tmdbId)
        fun text(key: String) = o[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        val title = text("name") ?: return rejected(hint.tmdbId)
        val original = text("original_name").orEmpty()
        val year = text("first_air_date")?.take(4)?.toIntOrNull()
        // Provider years are often the season's year, not the first-air year; names carry the weight.
        val names = hint.names.split(',').map(String::trim).filter(String::isNotEmpty)
        if (!ShowMatching.accepts(names, hint.year, title, original, year)) return rejected(hint.tmdbId)
        return CanonicalShow(hint.tmdbId, true, title, original.ifBlank { null }, year, text("original_language"),
            text("poster_path")?.let { "https://image.tmdb.org/t/p/w500$it" },
            text("backdrop_path")?.let { "https://image.tmdb.org/t/p/w1280$it" },
            text("overview"), genres(o), o["vote_average"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 },
            o["number_of_seasons"]?.jsonPrimitive?.intOrNull, System.currentTimeMillis(), network(o), text("status"), networkLogo(o))
    }

    /** Adds network, status, genres and season count to a row validated from a TMDB list entry. */
    suspend fun detail(show: CanonicalShow): CanonicalShow {
        val o = fetch(show.tmdbId) ?: return show.copy(status = show.status ?: "Unknown", networkLogo = "")
        return show.copy(network = network(o), status = o["status"]?.jsonPrimitive?.contentOrNull ?: "Unknown", networkLogo = networkLogo(o),
            genres = show.genres ?: genres(o), seasonCount = o["number_of_seasons"]?.jsonPrimitive?.intOrNull ?: show.seasonCount)
    }

    private fun genres(o: JsonObject) = o["genres"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
        ?.joinToString(", ")?.takeIf(String::isNotBlank)
    private fun networkLogo(o: JsonObject) = o["networks"]?.jsonArray?.firstOrNull()?.jsonObject?.get("logo_path")?.jsonPrimitive?.contentOrNull
        ?.let { "https://image.tmdb.org/t/p/w154$it" } ?: ""
    private fun network(o: JsonObject) = o["networks"]?.jsonArray?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull

    private fun rejected(id: Int) = CanonicalShow(id, false, null, null, null, null, null, null, null, null, null, null, System.currentTimeMillis())
}

