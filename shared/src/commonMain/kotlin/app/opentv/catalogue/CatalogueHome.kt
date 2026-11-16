package app.opentv.catalogue

import androidx.room.Embedded
import app.opentv.core.AppSettings
import app.opentv.data.model.Series
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** One entry of a TMDB list (trending, now playing, popular…), as TMDB ranks it. */
@Serializable
data class RankedTitle(
    val id: Int, val title: String, val originalTitle: String = "", val year: Int? = null,
    val poster: String? = null, val backdrop: String? = null, val overview: String? = null,
    val rating: Double? = null, val language: String? = null,
)

@Serializable
private data class RankingsFile(val fetchedMillis: Long, val lists: Map<String, List<RankedTitle>>)

/**
 * Factual TMDB rankings for the Movies/Shows home: trending this week, new (now playing / on the
 * air) and popular. Cached on disk for six hours; a stale cache is used when TMDB can't be reached.
 * These are TMDB's global lists, not personal recommendations; the home shows only titles you have.
 */
class TmdbRankings(private val http: OkHttpClient, private val settings: AppSettings, private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    enum class Kind(val file: String, val lists: List<Pair<String, String>>) {
        MOVIES("rankings-movies.json", listOf(TRENDING to "trending/movie/week", NEW to "movie/now_playing", POPULAR to "movie/popular")),
        SHOWS("rankings-shows.json", listOf(TRENDING to "trending/tv/week", NEW to "tv/on_the_air", POPULAR to "tv/popular")),
    }

    fun cached(kind: Kind): Map<String, List<RankedTitle>>? = runCatching {
        json.decodeFromString<RankingsFile>(File(dir, kind.file).readText()).lists
    }.getOrNull()

    private fun fresh(kind: Kind) = runCatching {
        System.currentTimeMillis() - json.decodeFromString<RankingsFile>(File(dir, kind.file).readText()).fetchedMillis < SIX_HOURS
    }.getOrDefault(false)

    /** Cached lists when fresh, otherwise refetched (three pages each); stale cache on failure. */
    suspend fun lists(kind: Kind): Map<String, List<RankedTitle>>? = withContext(Dispatchers.IO) {
        if (fresh(kind)) return@withContext cached(kind)
        val key = settings.tmdbApiKey.value.trim().takeIf(String::isNotEmpty) ?: return@withContext cached(kind)
        val fetched = runCatching {
            kind.lists.associate { (name, path) -> name to (1..3).flatMap { page -> page(path, page, key, kind == Kind.SHOWS) }.distinctBy { it.id } }
        }.getOrNull()?.takeIf { lists -> lists.values.any { it.isNotEmpty() } } ?: return@withContext cached(kind)
        runCatching {
            dir.mkdirs()
            File(dir, kind.file).writeText(json.encodeToString(RankingsFile(System.currentTimeMillis(), fetched)))
        }
        fetched
    }

    private fun page(path: String, page: Int, key: String, tv: Boolean): List<RankedTitle> {
        val url = "https://api.themoviedb.org/3/$path".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", key).addQueryParameter("page", page.toString()).build()
        val body = http.newCall(Request.Builder().url(url).build()).execute().use {
            if (!it.isSuccessful) error("TMDB HTTP ${it.code}")
            it.body?.string() ?: error("empty")
        }
        return json.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty().mapNotNull { element ->
            val o = element.jsonObject
            fun text(k: String) = o[k]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            val id = o["id"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
            RankedTitle(id, text(if (tv) "name" else "title") ?: return@mapNotNull null, text(if (tv) "original_name" else "original_title").orEmpty(),
                text(if (tv) "first_air_date" else "release_date")?.take(4)?.toIntOrNull(),
                text("poster_path")?.let { "https://image.tmdb.org/t/p/w500$it" },
                text("backdrop_path")?.let { "https://image.tmdb.org/t/p/w1280$it" },
                text("overview"), o["vote_average"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 }, text("original_language"))
        }
    }

    companion object {
        const val TRENDING = "trending"
        const val NEW = "new"
        const val POPULAR = "popular"
        private const val SIX_HOURS = 6L * 60 * 60 * 1000
    }
}

/** A provider show group for one TMDB hint, with every provider name (to check the hint). */
data class HintGroup(@Embedded val primary: Series, val groupKey: String, val variantCount: Int, val names: String?, val bestQuality: Int)
