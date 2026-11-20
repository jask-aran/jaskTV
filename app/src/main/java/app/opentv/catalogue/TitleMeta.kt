package app.opentv.catalogue

import app.opentv.account.CoreDirectory
import app.opentv.core.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Someone in a title's credits: [role] is the character, or the job ("Director", "Created by"). */
data class CreditPerson(val id: Int, val name: String, val role: String?, val photo: String?)

/** Another title (collection part, recommendation, a person's credit), by its TMDB id. */
data class TitleRef(val tmdbId: Int, val movie: Boolean, val title: String, val year: Int?, val poster: String?, val rating: Double?,
    val role: String? = null) {
    /** "m:550" / "t:1396": matches the keys of [TitleMeta.onYourSources]-style lookups. */
    val key: String get() = (if (movie) "m:" else "t:") + tmdbId
}

/** IMDb, Rotten Tomatoes and Metacritic, as OMDb gives them ("8.2", "91%", "72"). */
data class ReviewScores(val imdb: String?, val imdbVotes: String?, val rottenTomatoes: String?, val metacritic: String?)

/** One written review (TMDB's users), shortened. */
data class ReviewExcerpt(val author: String, val text: String, val rating: Double?)

/**
 * Everything the media page shows about a title beyond what the provider gives: logo art, age rating, cast with
 * photos, scores, a review, its collection, related titles and (shows) air status.
 */
data class TitleDetails(
    val tmdbId: Int, val movie: Boolean, val logo: String?, val backdrop: String?, val tagline: String?, val overview: String?,
    val ageRating: String?, val runtimeMinutes: Int?, val tmdbScore: Double?, val votes: Int?, val genres: List<String>,
    val cast: List<CreditPerson>, val crew: List<CreditPerson>, val imdbId: String?,
    val collectionName: String?, val collection: List<TitleRef>, val recommendations: List<TitleRef>, val review: ReviewExcerpt?,
    /** Shows: "Returning", "Ended", "Cancelled"; the next episode ("S6 E1 · 12 Mar 2027") and the networks. */
    val status: String?, val nextEpisode: String?, val networks: List<String>, val seasons: Int?,
)

/** One episode as TMDB has it: what fills the gaps a provider leaves (stills, synopses, air dates). */
data class EpisodeMeta(val season: Int, val number: Int, val name: String?, val overview: String?, val still: String?,
    val airDate: String?, val runtimeMinutes: Int?)

data class PersonDetails(val id: Int, val name: String, val photo: String?, val bio: String?, val born: String?, val place: String?,
    val department: String?, val credits: List<TitleRef>)

/**
 * Title details for the media page. Asks a jasktv-core that has "meta" first (it fetches each title once with the
 * account's keys and keeps it for every device), else TMDB and OMDb directly with this device's keys. Answers are
 * also kept in memory for this run, so going back to a page costs nothing.
 */
class TitleMeta(private val http: OkHttpClient, private val settings: AppSettings) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val memo = object : LinkedHashMap<String, Any?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any?>?) = size > 120
    }

    /** What's already in memory for a title (no network): lets a page come Back already filled in. */
    fun known(movie: Boolean, id: Int): TitleDetails? = synchronized(memo) { memo[(if (movie) "m" else "t") + id] as? TitleDetails }

    suspend fun movie(id: Int): TitleDetails? = remembered("m$id") {
        val o = tmdb("movie/$id", "append_to_response=credits,images,release_dates,recommendations,reviews&include_image_language=en,null") ?: return@remembered null
        val collection = o.obj("belongs_to_collection")
        val parts = collection?.int("id")?.let { cid -> tmdb("collection/$cid", "")?.arr("parts")?.mapNotNull { ref(it, true) } }
            ?.sortedBy { it.year ?: 9999 }.orEmpty()
        TitleDetails(id, true, logo(o), image(o.str("backdrop_path"), "w1280"), o.str("tagline")?.takeIf(String::isNotBlank), o.str("overview"),
            certification(o.obj("release_dates")?.arr("results"), movie = true), o.int("runtime")?.takeIf { it > 0 },
            o.double("vote_average")?.takeIf { it > 0 }, o.int("vote_count"), o.arr("genres")?.mapNotNull { it.jsonObject.str("name") }.orEmpty(),
            cast(o.obj("credits")?.arr("cast"), aggregate = false),
            o.obj("credits")?.arr("crew")?.mapNotNull { c -> c.jsonObject.takeIf { it.str("job") == "Director" || it.str("job") == "Screenplay" || it.str("job") == "Writer" } }
                ?.mapNotNull { person(it, it.str("job")) }?.distinctBy { it.id to it.role }?.take(6).orEmpty(),
            o.str("imdb_id")?.takeIf { it.startsWith("tt") }, collection?.str("name"), parts,
            o.obj("recommendations")?.arr("results")?.mapNotNull { ref(it, true) }.orEmpty(), review(o),
            null, null, emptyList(), null)
    }

    suspend fun show(id: Int): TitleDetails? = remembered("t$id") {
        val o = tmdb("tv/$id", "append_to_response=aggregate_credits,images,content_ratings,recommendations,reviews,external_ids&include_image_language=en,null")
            ?: return@remembered null
        val next = o.obj("next_episode_to_air")?.let { n ->
            val s = n.int("season_number"); val e = n.int("episode_number")
            listOfNotNull(if (s != null && e != null) "S$s E$e" else null, n.str("air_date")?.let(::day)).joinToString(" · ").ifBlank { null }
        }
        TitleDetails(id, false, logo(o), image(o.str("backdrop_path"), "w1280"), o.str("tagline")?.takeIf(String::isNotBlank), o.str("overview"),
            certification(o.obj("content_ratings")?.arr("results"), movie = false),
            o.arr("episode_run_time")?.firstOrNull()?.jsonPrimitive?.intOrNull, o.double("vote_average")?.takeIf { it > 0 }, o.int("vote_count"),
            o.arr("genres")?.mapNotNull { it.jsonObject.str("name") }.orEmpty(),
            cast(o.obj("aggregate_credits")?.arr("cast"), aggregate = true),
            o.arr("created_by")?.mapNotNull { person(it.jsonObject, "Created by") }.orEmpty(),
            o.obj("external_ids")?.str("imdb_id")?.takeIf { it.startsWith("tt") }, null, emptyList(),
            o.obj("recommendations")?.arr("results")?.mapNotNull { ref(it, false) }.orEmpty(), review(o),
            when (o.str("status")) { "Returning Series" -> "Returning"; "Ended" -> "Ended"; "Canceled" -> "Cancelled"; "In Production" -> "In production"; else -> o.str("status") },
            next, o.arr("networks")?.mapNotNull { it.jsonObject.str("name") }.orEmpty(), o.int("number_of_seasons"))
    }

    suspend fun season(tvId: Int, season: Int): List<EpisodeMeta> = remembered("s$tvId/$season") {
        tmdb("tv/$tvId/season/$season", "")?.arr("episodes")?.mapNotNull { e -> val o = e.jsonObject
            EpisodeMeta(season, o.int("episode_number") ?: return@mapNotNull null, o.str("name"), o.str("overview")?.takeIf(String::isNotBlank),
                image(o.str("still_path"), "w300"), o.str("air_date"), o.int("runtime"))
        }.orEmpty()
    }.orEmpty()

    suspend fun person(id: Int): PersonDetails? = remembered("p$id") {
        val o = tmdb("person/$id", "append_to_response=combined_credits") ?: return@remembered null
        val credits = o.obj("combined_credits")?.let { c -> c.arr("cast").orEmpty() + c.arr("crew").orEmpty() }.orEmpty()
            .mapNotNull { e -> val x = e.jsonObject; val movie = x.str("media_type") == "movie"
                ref(e, movie)?.copy(role = x.str("character")?.takeIf(String::isNotBlank) ?: x.str("job")) to (x.double("popularity") ?: 0.0) }
            .filter { it.first != null }.sortedByDescending { it.second }.mapNotNull { it.first }.distinctBy { it.key }
        PersonDetails(id, o.str("name") ?: return@remembered null, image(o.str("profile_path"), "h632"), o.str("biography")?.takeIf(String::isNotBlank),
            o.str("birthday"), o.str("place_of_birth"), o.str("known_for_department"), credits)
    }

    /** IMDb, Rotten Tomatoes and Metacritic for an IMDb id (core's cache, else OMDb with this device's key). */
    suspend fun scores(imdb: String): ReviewScores? = remembered("o$imdb") {
        val body = core("meta/omdb?i=$imdb") ?: settings.omdbApiKey.value.trim().takeIf(String::isNotEmpty)?.let { key ->
            fetch("https://www.omdbapi.com/".toHttpUrl().newBuilder().addQueryParameter("apikey", key).addQueryParameter("i", imdb).build().toString())
        } ?: return@remembered null
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@remembered null
        if (o.str("Response") == "False") return@remembered null
        val ratings = o.arr("Ratings").orEmpty().map { it.jsonObject }
        ReviewScores(o.str("imdbRating")?.takeIf { it != "N/A" }, o.str("imdbVotes")?.takeIf { it != "N/A" },
            ratings.firstOrNull { it.str("Source") == "Rotten Tomatoes" }?.str("Value"),
            o.str("Metascore")?.takeIf { it != "N/A" })
    }

    // ---- fetching ------------------------------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> remembered(key: String, load: suspend () -> T?): T? {
        synchronized(memo) { if (memo.containsKey(key)) return memo[key] as T? }
        val value = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() }
        if (value != null) synchronized(memo) { memo[key] = value }
        return value
    }

    private fun tmdb(path: String, query: String): JsonObject? {
        val body = core("meta/tmdb/$path" + if (query.isEmpty()) "" else "?$query") ?: run {
            val key = settings.tmdbApiKey.value.trim().takeIf(String::isNotEmpty) ?: return null
            fetch("https://api.themoviedb.org/3/$path?api_key=$key" + if (query.isEmpty()) "" else "&$query")
        } ?: return null
        return runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()?.takeIf { it["success"]?.jsonPrimitive?.booleanOrNull != false }
    }

    /** From a core with "meta", or null (none answering, or it couldn't get it). */
    private fun core(path: String): String? {
        val core = CoreDirectory.endpoint("meta") ?: return null
        val request = Request.Builder().url("${core.base}/$path").header("Authorization", "Bearer ${core.secret}").build()
        return runCatching { http.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null } }.getOrNull()
    }

    private fun fetch(url: String): String? =
        runCatching { http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null } }.getOrNull()

    // ---- reading TMDB's answers --------------------------------------------------------------------------------

    private fun image(path: String?, size: String) = path?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/$size$it" }

    /** The title's logo art: English first, then language-free, best voted. */
    private fun logo(o: JsonObject) = o.obj("images")?.arr("logos").orEmpty().map { it.jsonObject }
        .sortedWith(compareBy<JsonObject>({ if (it.str("iso_639_1") == "en") 0 else 1 }, { -(it.double("vote_average") ?: 0.0) }))
        .firstOrNull()?.let { image(it.str("file_path")?.replace(".svg", ".png"), "w500") }

    /** Australia's classification first (M, MA 15+), then the US one, then the UK one. */
    private fun certification(results: JsonArray?, movie: Boolean): String? {
        val byCountry = results.orEmpty().map { it.jsonObject }.associateBy { it.str("iso_3166_1") }
        for (c in listOf("AU", "US", "GB")) {
            val r = byCountry[c] ?: continue
            val value = if (movie) r.arr("release_dates").orEmpty().map { it.jsonObject.str("certification").orEmpty() }.firstOrNull(String::isNotBlank)
                else r.str("rating")?.takeIf(String::isNotBlank)
            if (value != null) return value
        }
        return null
    }

    private fun cast(list: JsonArray?, aggregate: Boolean) = list.orEmpty().take(20).mapNotNull { e -> val o = e.jsonObject
        val role = if (aggregate) o.arr("roles")?.firstOrNull()?.jsonObject?.str("character") else o.str("character")
        person(o, role?.takeIf(String::isNotBlank))
    }

    private fun person(o: JsonObject, role: String?): CreditPerson? = o.int("id")?.let { id ->
        CreditPerson(id, o.str("name") ?: return null, role, image(o.str("profile_path"), "w185"))
    }

    private fun ref(e: JsonElement, movie: Boolean): TitleRef? { val o = e.jsonObject
        val title = o.str(if (movie) "title" else "name") ?: return null
        val date = o.str(if (movie) "release_date" else "first_air_date")
        return TitleRef(o.int("id") ?: return null, movie, title, date?.take(4)?.toIntOrNull(), image(o.str("poster_path"), "w342"),
            o.double("vote_average")?.takeIf { it > 0 })
    }

    /** The review that best represents the title: rated nearest its average score (not the harshest or the gushiest). */
    private fun review(o: JsonObject) = o.obj("reviews")?.arr("results").orEmpty().map { it.jsonObject }
        .filter { (it.str("content")?.length ?: 0) > 80 }
        .minByOrNull { r -> val avg = o.double("vote_average") ?: 6.5
            r.obj("author_details")?.double("rating")?.let { kotlin.math.abs(it - avg) } ?: 5.0 }?.let { r ->
            val text = r.str("content")!!.replace(Regex("""[*_#>]|\r"""), "").replace(Regex("""\n{2,}"""), "\n").trim()
            ReviewExcerpt(r.str("author") ?: "A TMDB user", if (text.length > 420) text.take(420).substringBeforeLast(' ') + "…" else text,
                r.obj("author_details")?.double("rating"))
        }

    private fun day(date: String) = runCatching {
        val d = java.time.LocalDate.parse(date)
        d.format(java.time.format.DateTimeFormatter.ofPattern(if (d.year == java.time.LocalDate.now().year) "d MMM" else "d MMM yyyy"))
    }.getOrDefault(date)

    private fun JsonObject.str(k: String) = this[k]?.let { it as? JsonPrimitive }?.contentOrNull
    private fun JsonObject.int(k: String) = this[k]?.let { it as? JsonPrimitive }?.intOrNull
    private fun JsonObject.double(k: String) = this[k]?.let { it as? JsonPrimitive }?.doubleOrNull
    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun JsonObject.arr(k: String) = this[k] as? JsonArray
}
