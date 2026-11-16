/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import android.util.Log
import app.opentv.core.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The metadata TMDB can supply to fill gaps a provider left blank. Every field is nullable — TMDB
 * is only ever a *fallback*, so a caller takes what it has and only where its own data is missing.
 */
data class TmdbMeta(
    val tmdbId: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val overview: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val rating: Double? = null,
    val year: Int? = null,
)

/**
 * A thin, read-only TMDB v3 client used to enrich VOD detail pages when the provider's own
 * metadata is thin — the "match Plex" back-fill for posters, backdrops, synopsis, cast, director
 * and genre.
 *
 * ## Per-user key, on device only
 * There is no bundled key. Each user pastes their own free TMDB API key in Settings; it lives in
 * [AppSettings] (SharedPreferences) exactly like a provider credential and never leaves the box.
 * With no key set [isConfigured] is false and every lookup short-circuits to null, so the whole
 * feature is simply inert until the user opts in — no traffic, nothing to leak.
 *
 * ## Blocking on purpose
 * The methods block on the calling thread. The one caller ([app.opentv.data.repo.CatalogRepository]
 * detail back-fill) is already inside `withContext(Dispatchers.IO)`, matching how the Xtream client
 * is used, so there is no value in another coroutine hop here.
 */
class TmdbClient(
    private val http: OkHttpClient,
    private val settings: AppSettings,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    /** True once the user has saved a key. Callers gate on this so nothing runs without one. */
    fun isConfigured(): Boolean = settings.tmdbApiKey.value.isNotBlank()

    /** Metadata for a film: by [tmdbId] when the provider already gave one, else a title(+year) search. */
    fun movieMeta(title: String, year: Int?, tmdbId: String?): TmdbMeta? =
        lookup(isMovie = true, title = title, year = year, tmdbId = tmdbId)

    /** Metadata for a show. Same strategy as [movieMeta]; TMDB has no director for TV, so that stays null. */
    fun seriesMeta(title: String, year: Int?, tmdbId: String?): TmdbMeta? =
        lookup(isMovie = false, title = title, year = year, tmdbId = tmdbId)

    /**
     * The IMDb id (`tt…`) for a title, for handing to Stremio add-ons. Uses the provider's TMDB id
     * when it gave one, otherwise a title(+year) search, then reads `imdb_id` from the movie details
     * (top-level) or the TV `external_ids` endpoint. Null with no key, no match, or no IMDb mapping.
     */
    fun imdbId(title: String, year: Int?, isMovie: Boolean, tmdbId: String?): String? {
        val key = settings.tmdbApiKey.value.trim()
        if (key.isEmpty()) return null
        val id = tmdbId?.takeIf { it.isNotBlank() }
            ?: searchId(isMovie, searchTitle(title), year, key)
            ?: return null
        val builder = TMDB_BASE.newBuilder()
            .addPathSegment(if (isMovie) "movie" else "tv")
            .addPathSegment(id)
        if (!isMovie) builder.addPathSegment("external_ids")
        builder.addQueryParameter("api_key", key)
        val o = get(builder.build())?.jsonObject ?: return null
        return o["imdb_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("tt") }
    }

    private fun lookup(isMovie: Boolean, title: String, year: Int?, tmdbId: String?): TmdbMeta? {
        val key = settings.tmdbApiKey.value.trim()
        if (key.isEmpty()) return null
        val query = searchTitle(title)
        if (query.isBlank() && tmdbId.isNullOrBlank()) return null
        val id = tmdbId?.takeIf { it.isNotBlank() } ?: searchId(isMovie, query, year, key) ?: return null
        return runCatching { details(isMovie, id, key) }
            .onFailure { Log.w(TAG, "TMDB details failed for $id", it) }
            .getOrNull()
    }

    /** Finds the best-matching TMDB id, retrying without the year filter if a year search comes back empty. */
    private fun searchId(isMovie: Boolean, query: String, year: Int?, key: String): String? {
        fun run(withYear: Boolean): String? {
            val b = TMDB_BASE.newBuilder()
                .addPathSegment("search")
                .addPathSegment(if (isMovie) "movie" else "tv")
                .addQueryParameter("api_key", key)
                .addQueryParameter("query", query)
                .addQueryParameter("include_adult", "false")
            if (withYear && year != null) {
                b.addQueryParameter(if (isMovie) "year" else "first_air_date_year", year.toString())
            }
            val results = get(b.build())?.jsonObject?.get("results")?.jsonArray ?: return null
            val candidates = results.mapNotNull { element ->
                val o = element.jsonObject
                val id = o["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
                val titleField = if (isMovie) "title" else "name"
                val originalField = if (isMovie) "original_title" else "original_name"
                app.opentv.catalogue.MovieCandidate(id,
                    o[titleField]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    o[originalField]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    o[if (isMovie) "release_date" else "first_air_date"]?.jsonPrimitive?.contentOrNull?.take(4)?.toIntOrNull())
            }
            val decision = app.opentv.catalogue.MovieMatching.decide(query, year, candidates)
            return (decision as? app.opentv.catalogue.MovieMatchDecision.Accepted)?.candidate?.id?.toString()
        }
        return run(withYear = year != null)
    }

    private fun details(isMovie: Boolean, id: String, key: String): TmdbMeta? {
        val url = TMDB_BASE.newBuilder()
            .addPathSegment(if (isMovie) "movie" else "tv")
            .addPathSegment(id)
            .addQueryParameter("api_key", key)
            .addQueryParameter("append_to_response", "credits")
            .build()
        val o = get(url)?.jsonObject ?: return null

        val poster = o["poster_path"]?.jsonPrimitive?.contentOrNull
        val backdrop = o["backdrop_path"]?.jsonPrimitive?.contentOrNull
        val overview = o["overview"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val genre = o["genres"]?.jsonArray
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString(", ")?.takeIf { it.isNotBlank() }

        val credits = o["credits"]?.jsonObject
        val cast = credits?.get("cast")?.jsonArray
            ?.take(TOP_CAST)
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString(", ")?.takeIf { it.isNotBlank() }
        val director = if (!isMovie) null else credits?.get("crew")?.jsonArray
            ?.firstOrNull { it.jsonObject["job"]?.jsonPrimitive?.contentOrNull == "Director" }
            ?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull

        val rating = o["vote_average"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0.0 }
        val dateStr = (o["release_date"] ?: o["first_air_date"])?.jsonPrimitive?.contentOrNull
        val year = dateStr?.take(4)?.toIntOrNull()

        return TmdbMeta(
            tmdbId = id,
            posterUrl = poster?.let { IMG_POSTER + it },
            backdropUrl = backdrop?.let { IMG_BACKDROP + it },
            overview = overview,
            cast = cast,
            director = director,
            genre = genre,
            rating = rating,
            year = year,
        )
    }

    /**
     * Season number → poster for a show, from one `/tv/{id}` request. Provider TMDB hints are not
     * always right, so the posters are used only when TMDB's name matches [title] or its first-air
     * year matches [year]. Empty without a key, on mismatch or on failure.
     */
    fun seasonPosters(tvId: String, title: String, year: Int?): Map<Int, String> {
        val key = settings.tmdbApiKey.value.takeIf { it.isNotBlank() } ?: return emptyMap()
        val url = TMDB_BASE.newBuilder().addPathSegment("tv").addPathSegment(tvId).addQueryParameter("api_key", key).build()
        val o = get(url)?.jsonObject ?: return emptyMap()
        fun norm(s: String?) = s.orEmpty().lowercase().filter(Char::isLetterOrDigit)
        val names = listOfNotNull(o["name"]?.jsonPrimitive?.contentOrNull, o["original_name"]?.jsonPrimitive?.contentOrNull).map(::norm)
        val tmdbYear = o["first_air_date"]?.jsonPrimitive?.contentOrNull?.take(4)?.toIntOrNull()
        val wanted = norm(title)
        val nameMatch = wanted.isNotEmpty() && names.any { it == wanted }
        val yearMatch = year != null && year == tmdbYear && names.any { it.isNotEmpty() && (it in wanted || wanted in it) }
        if (!nameMatch && !yearMatch) return emptyMap()
        return o["seasons"]?.jsonArray?.mapNotNull { element ->
            val season = element.jsonObject
            val number = season["season_number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val poster = season["poster_path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            number to IMG_SEASON + poster
        }?.toMap().orEmpty()
    }

    private fun get(url: HttpUrl): JsonElement? {
        val request = Request.Builder().url(url).header("User-Agent", "OpenTV").build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                json.parseToJsonElement(body)
            }
        }.onFailure { Log.w(TAG, "TMDB request failed", it) }.getOrNull()
    }

    /** Strips a trailing "(2023)"/year and collapses whitespace so the title searches cleanly. */
    private fun searchTitle(raw: String): String =
        raw.replace(Regex("""\(?(19|20)\d{2}\)?\s*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private companion object {
        const val TAG = "TmdbClient"
        val TMDB_BASE: HttpUrl = "https://api.themoviedb.org/3".toHttpUrl()
        const val IMG_POSTER = "https://image.tmdb.org/t/p/w500"
        const val IMG_BACKDROP = "https://image.tmdb.org/t/p/w1280"
        const val IMG_SEASON = "https://image.tmdb.org/t/p/w342"
        const val TOP_CAST = 12
    }
}
