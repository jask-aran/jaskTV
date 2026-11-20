package app.opentv.catalogue

import app.opentv.core.AppSettings
import app.opentv.data.model.Movie
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.json.*
import java.io.IOException

/** Worker-owned client, rate-limited by [TmdbGate]. No requests are made by cards or detail composition. */
class CanonicalMovieResolver(private val http: OkHttpClient, private val settings: AppSettings) {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun get(path: String, query: String? = null, year: Int? = null): JsonObject? {
        val url = "https://api.themoviedb.org/3/".toHttpUrl().newBuilder()
            .addPathSegments(path).addQueryParameter("api_key", settings.tmdbApiKey.value.trim())
        if (query != null) url.addQueryParameter("query", query).addQueryParameter("include_adult", "false")
        if (year != null) url.addQueryParameter("year", year.toString())
        val request = Request.Builder().url(url.build()).build()
        return TmdbGate.request({ http.newCall(request) }) {
            json.parseToJsonElement(it.body?.string() ?: throw IOException("Empty metadata response")).jsonObject
        }
    }

    /**
     * One request per title: a TMDB id from the provider is looked up directly; otherwise a search, whose result
     * already has the poster, backdrop, overview and rating (genres and runtime come later, with the title's page).
     */
    suspend fun resolve(movie: Movie): Pair<CanonicalMovie?, MovieMatchDecision> {
        val explicit = movie.tmdbId?.toIntOrNull()?.takeIf { it > 0 }
        if (explicit != null) {
            val detail = get("movie/$explicit") ?: return null to MovieMatchDecision.Unmatched
            val candidate = candidate(detail) ?: return null to MovieMatchDecision.Unmatched
            if (candidate.id != explicit) return null to MovieMatchDecision.Unmatched
            return metadata(detail) to MovieMatching.explicit(candidate, MovieMatching.releaseYear(movie.name, movie.year))
        }
        val query = MovieMatching.query(movie.name)
        if (query.isBlank()) return null to MovieMatchDecision.Unmatched
        val year = MovieMatching.releaseYear(movie.name, movie.year)
        val raw = get("search/movie", query, year)?.get("results")?.jsonArray.orEmpty().map { it.jsonObject }
        val decision = MovieMatching.decide(movie.name, year, raw.mapNotNull { candidate(it) })
        val title = if (decision is MovieMatchDecision.Accepted) {
            raw.firstOrNull { it.text("id")?.toIntOrNull() == decision.candidate.id }?.let(::metadata)
        } else null
        // A disappearing candidate must not produce an accepted link with missing metadata.
        return title to if (decision is MovieMatchDecision.Accepted && title == null) MovieMatchDecision.Unmatched else decision
    }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun candidate(o: JsonObject): MovieCandidate? {
        val id = o.text("id")?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val title = o.text("title")?.takeIf { it.isNotBlank() } ?: return null
        return MovieCandidate(id, title, o.text("original_title").orEmpty(), o.text("release_date")?.take(4)?.toIntOrNull())
    }
    private fun metadata(o: JsonObject): CanonicalMovie? {
        val c = candidate(o) ?: return null
        return CanonicalMovie(c.id, c.title, c.originalTitle, c.year,
            o.text("original_language")?.takeIf { it.isNotBlank() },
            o.text("poster_path")?.let { "https://image.tmdb.org/t/p/w500$it" },
            o.text("backdrop_path")?.let { "https://image.tmdb.org/t/p/w1280$it" },
            o.text("overview"), o["genres"]?.jsonArray?.mapNotNull { it.jsonObject.text("name") }?.joinToString(", "),
            o["vote_average"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 },
            o["runtime"]?.jsonPrimitive?.intOrNull, System.currentTimeMillis())
    }
}
