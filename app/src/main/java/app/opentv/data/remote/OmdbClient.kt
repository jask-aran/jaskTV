/*
 * jaskTV — GPL-3.0-or-later.
 * Optional, opt-in OMDb review summaries. Does not scrape IMDb or Rotten Tomatoes.
 */
package app.opentv.data.remote

import app.opentv.core.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ReviewScores(
    val imdbId: String?,
    /** Reported by OMDb, not a direct IMDb API connection. */
    val imdb: Double?,
    val votes: Long?,
    val rottenTomatoes: Int?,
    val metacritic: Int?,
)

internal object ReviewParser {
    fun parse(row: JSONObject): ReviewScores? {
        if (!row.optString("Response").equals("True", ignoreCase = true)) return null
        val imdb = row.optString("imdbRating").toDoubleOrNull()?.takeIf { it in 0.0..10.0 }
        val votes = row.optString("imdbVotes").replace(",", "").toLongOrNull()
        var rotten: Int? = null
        var metacritic = row.optString("Metascore").toIntOrNull()?.takeIf { it in 0..100 }
        val ratings = row.optJSONArray("Ratings")
        if (ratings != null) for (index in 0 until ratings.length()) {
            val value = ratings.optJSONObject(index) ?: continue
            when (value.optString("Source")) {
                "Rotten Tomatoes" ->
                    rotten = value.optString("Value").removeSuffix("%").toIntOrNull()?.takeIf { it in 0..100 }
                "Metacritic" ->
                    if (metacritic == null) metacritic = value.optString("Value").substringBefore("/")
                        .toIntOrNull()?.takeIf { it in 0..100 }
            }
        }
        return ReviewScores(row.optString("imdbID").takeIf { it.matches(Regex("tt[0-9]{7,10}")) },
            imdb, votes, rotten, metacritic)
    }

    fun comparable(a: String, b: String): Boolean {
        fun clean(s: String) = s.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]"), "")
        return clean(a) == clean(b)
    }
}

/** Optional external scores; source-labelled and deliberately not mixed into provider/TMDB ratings. */
class OmdbClient(private val http: OkHttpClient, private val settings: AppSettings) {
    private val cache = mutableMapOf<String, Pair<Long, ReviewScores?>>()
    private val ttl = TimeUnit.DAYS.toMillis(1)

    suspend fun lookup(title: String, year: Int?, movie: Boolean, imdbId: String?): ReviewScores? =
        withContext(Dispatchers.IO) {
            val apiKey = settings.omdbApiKey.value.trim()
            if (apiKey.isEmpty()) return@withContext null
            val key = "${apiKey.hashCode()}:${imdbId ?: title.lowercase(Locale.ROOT)}:${year ?: ""}:$movie"
            val cached = synchronized(cache) { cache[key] }
            if (cached != null && System.currentTimeMillis() - cached.first < ttl)
                return@withContext cached.second
            val url = "https://www.omdbapi.com/".toHttpUrl().newBuilder()
                .addQueryParameter("apikey", apiKey)
                .addQueryParameter("type", if (movie) "movie" else "series")
            val hasImdbId = !imdbId.isNullOrBlank() && imdbId.matches(Regex("tt[0-9]{7,10}"))
            if (hasImdbId) url.addQueryParameter("i", imdbId)
            else {
                url.addQueryParameter("t", title)
                if (year != null) url.addQueryParameter("y", year.toString())
            }
            val scores = try {
                http.newCall(Request.Builder().url(url.build()).get().build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    val item = JSONObject(body)
                    // A title lookup must not silently match a different work or remake.
                    if (!hasImdbId && !ReviewParser.comparable(item.optString("Title"), title))
                        return@use null
                    if (!hasImdbId && year != null &&
                        item.optString("Year").take(4).toIntOrNull()?.let { it != year } == true)
                        return@use null
                    ReviewParser.parse(item)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            // Do not negatively cache temporary quota, network or authentication failures.
            if (scores != null) synchronized(cache) {
                if (cache.size >= 256) cache.remove(cache.keys.first())
                cache[key] = System.currentTimeMillis() to scores
            }
            scores
        }
}
