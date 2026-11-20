/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import app.opentv.R
import app.opentv.core.AppText
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.Episode
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.StreamKind
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Client for the Xtream Codes "player API".
 *
 * ## Why this is hand-rolled instead of using typed `@Serializable` models
 *
 * Xtream panels are not a standard. Different builds return the same field as a number, a
 * quoted number, an empty string, `null`, or omit it entirely — and `stream_id` in particular
 * flips between `12345` and `"12345"` depending on the panel. Strict deserialisation into a
 * data class means one unusual field aborts the parse of the *entire* catalogue, which the
 * user experiences as "the app won't load my channels" with no way to diagnose it.
 *
 * So we read [JsonElement] defensively and coerce. Every accessor below tolerates the wrong
 * type and returns null rather than throwing. Ugly; correct.
 */
class XtreamApi(
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    class XtreamException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Verifies credentials and returns the account's expiry, if the panel reports one. */
    suspend fun authenticate(source: Source): AccountInfo = withContext(Dispatchers.IO) {
        val body = getJson(source, action = null).jsonObject
        val userInfo = body["user_info"]?.jsonObjectOrNull
            ?: throw XtreamException(AppText.get(R.string.err_no_account_info))

        val status = userInfo["status"].asStringOrNull
        if (status != null && !status.equals("Active", ignoreCase = true)) {
            throw XtreamException(AppText.get(R.string.err_account_status, status))
        }
        if (userInfo["auth"].asIntOrNull == 0) {
            throw XtreamException(AppText.get(R.string.err_credentials))
        }

        AccountInfo(
            username = userInfo["username"].asStringOrNull,
            expiryMillis = userInfo["exp_date"].asLongOrNull?.times(1000),
            maxConnections = userInfo["max_connections"].asIntOrNull,
            activeConnections = userInfo["active_cons"].asIntOrNull,
            timezone = body["server_info"]?.jsonObjectOrNull?.get("timezone").asStringOrNull,
        )
    }

    suspend fun liveCategories(source: Source): List<Category> =
        categories(source, "get_live_categories", StreamKind.LIVE)

    suspend fun movieCategories(source: Source): List<Category> =
        categories(source, "get_vod_categories", StreamKind.MOVIE)

    suspend fun seriesCategories(source: Source): List<Category> =
        categories(source, "get_series_categories", StreamKind.SERIES)

    private suspend fun categories(
        source: Source,
        action: String,
        kind: StreamKind,
    ): List<Category> = withContext(Dispatchers.IO) {
        getJson(source, action).arrayOrEmpty.mapIndexedNotNull { index, element ->
            val obj = element.jsonObjectOrNull ?: return@mapIndexedNotNull null
            val id = obj["category_id"].asStringOrNull ?: return@mapIndexedNotNull null
            Category(
                id = id,
                sourceId = source.id,
                name = obj["category_name"].asStringOrNull ?: id,
                kind = kind,
                sortIndex = index,
            )
        }
    }

    /** Stream id → current name for one live category. Event slots are renamed per event; this keeps them fresh cheaply. */
    suspend fun liveNamesInCategory(source: Source, categoryId: String): Map<String, String> = withContext(Dispatchers.IO) {
        // Straight from the provider: this is how renamed event channels stay fresh.
        getJson(source, "get_live_streams", viaCore = false) { it.addQueryParameter("category_id", categoryId) }.arrayOrEmpty.mapNotNull { element ->
            val obj = element.jsonObjectOrNull ?: return@mapNotNull null
            val id = obj["stream_id"].asStringOrNull ?: return@mapNotNull null
            val name = obj["name"].asStringOrNull ?: return@mapNotNull null
            id to name
        }.toMap()
    }

    suspend fun liveStreams(source: Source): List<Channel> = withContext(Dispatchers.IO) {
        channelsFrom(source, getJson(source, "get_live_streams"))
    }

    /**
     * The live channels, from jasktv-core as only what changed since this device's last copy when it can ([ListCopy.delta]:
     * the channels returned are the changed ones, [ListCopy.removed] the stream ids gone), else whole.
     */
    suspend fun liveStreamsCopy(source: Source): Pair<List<Channel>, ListCopy> = withContext(Dispatchers.IO) {
        coreCopy(source, "get_live_streams")?.let { (response, copy) ->
            val parsed = response.use { r -> r.body?.string()?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } }
            if (parsed != null) return@withContext channelsFrom(source, parsed) to copy
        }
        liveStreams(source) to ListCopy.WHOLE
    }

    /**
     * Only what changed in the live list since this device's last copy from jasktv-core: mostly renamed event slots
     * (core re-reads them every few minutes). Null when no core can answer with changes (no core, or this device has
     * never copied from it): nothing big is ever downloaded here.
     */
    suspend fun liveChangesFromCore(source: Source): Pair<List<Channel>, ListCopy>? = withContext(Dispatchers.IO) {
        val core = coreFor(source, "get_live_streams") ?: return@withContext null
        if ("deltas" !in core.capabilities) return@withContext null
        val key = "${core.id}|${app.opentv.account.sync.LanCatalogue.sourceKey(source)}|get_live_streams"
        if (app.opentv.account.CoreDirectory.since(key) <= 0) return@withContext null
        val (response, copy) = coreCopy(source, "get_live_streams") ?: return@withContext null
        if (!copy.delta) { response.close(); return@withContext null }
        val parsed = response.use { r -> r.body?.string()?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } } ?: return@withContext null
        channelsFrom(source, parsed) to copy
    }

    private fun channelsFrom(source: Source, body: JsonElement): List<Channel> =
        body.arrayOrEmpty.mapIndexedNotNull { index, element ->
            val obj = element.jsonObjectOrNull ?: return@mapIndexedNotNull null
            val streamId = obj["stream_id"].asStringOrNull ?: return@mapIndexedNotNull null
            val name = obj["name"].asStringOrNull ?: return@mapIndexedNotNull null
            Channel(
                sourceId = source.id,
                streamId = streamId,
                name = name,
                categoryId = obj["category_id"].asStringOrNull,
                logoUrl = obj["stream_icon"].asStringOrNull?.takeIf { it.isNotBlank() },
                // epg_channel_id is what joins to XMLTV; it is frequently blank, in which
                // case the channel simply has no guide rather than the guide being broken.
                epgChannelId = obj["epg_channel_id"].asStringOrNull?.takeIf { it.isNotBlank() },
                tvArchive = obj["tv_archive"].asIntOrNull == 1,
                tvArchiveDays = obj["tv_archive_duration"].asIntOrNull ?: 0,
                number = obj["num"].asIntOrNull,
                streamUrl = liveStreamUrl(source, streamId),
                sortIndex = obj["num"].asIntOrNull ?: index,
            )
        }

    /**
     * The movie library, handed over in batches as it downloads. A big provider's
     * get_vod_streams runs to tens of MB; reading it whole (text + JSON tree + models at once)
     * blew the heap on phones, so it is streamed element by element instead.
     */
    suspend fun movies(source: Source, onBatch: suspend (List<Movie>) -> Unit): ListCopy =
        streamRows(source, "get_vod_streams", MovieField.KEYS) { rows -> onBatch(rows.mapNotNull { movieFrom(source, it) }) }

    /** Fields read from each get_vod_streams element; everything else is skipped unread. */
    private object MovieField {
        const val ID = 0; const val NAME = 1; const val EXT = 2; const val CATEGORY = 3; const val ICON = 4; const val RATING = 5
        const val YEAR = 6; const val PLOT = 7; const val DURATION = 8; const val ADDED = 9; const val BACKDROP_PATH = 10
        const val BACKDROP = 11; const val MOVIE_IMAGE = 12; const val COVER_BIG = 13; const val CAST = 14; const val ACTORS = 15
        const val DIRECTOR = 16; const val GENRE = 17; const val TMDB_ID = 18; const val TMDB = 19
        val KEYS = arrayOf("stream_id", "name", "container_extension", "category_id", "stream_icon", "rating", "year", "plot",
            "duration_secs", "added", "backdrop_path", "backdrop", "movie_image", "cover_big", "cast", "actors", "director",
            "genre", "tmdb_id", "tmdb")
    }

    private fun movieFrom(source: Source, r: Array<String?>): Movie? {
        val streamId = r[MovieField.ID] ?: return null
        val name = r[MovieField.NAME] ?: return null
        val extension = r[MovieField.EXT]?.takeIf { it.isNotBlank() }
        return Movie(
            sourceId = source.id,
            streamId = streamId,
            name = name,
            categoryId = r[MovieField.CATEGORY],
            posterUrl = r[MovieField.ICON]?.takeIf { it.isNotBlank() },
            rating = r[MovieField.RATING]?.toDoubleOrNull(),
            year = r[MovieField.YEAR].wholeInt(),
            plot = r[MovieField.PLOT],
            durationSeconds = r[MovieField.DURATION].wholeInt(),
            containerExtension = extension,
            streamUrl = vodStreamUrl(source, streamId, extension),
            addedMillis = r[MovieField.ADDED].wholeLong()?.times(1000) ?: 0L,
            // Rich metadata is best-effort here: the streams list carries it on some panels and not others. Whatever
            // is missing is back-filled from get_vod_info on the first detail open. Same order as asBackdropUrl.
            backdropUrl = r[MovieField.BACKDROP_PATH] ?: r[MovieField.BACKDROP]
                ?: r[MovieField.MOVIE_IMAGE]?.takeIf { it.isNotBlank() } ?: r[MovieField.COVER_BIG]?.takeIf { it.isNotBlank() },
            cast = r[MovieField.CAST] ?: r[MovieField.ACTORS],
            director = r[MovieField.DIRECTOR],
            genre = r[MovieField.GENRE],
            tmdbId = r[MovieField.TMDB_ID] ?: r[MovieField.TMDB],
        )
    }

    /** The series library in batches, streamed for the same reason as [movies]. */
    suspend fun series(source: Source, onBatch: suspend (List<Series>) -> Unit): ListCopy =
        streamRows(source, "get_series", SeriesField.KEYS) { rows -> onBatch(rows.mapNotNull { seriesFrom(source, it) }) }

    /** Fields read from each get_series element; everything else is skipped unread. */
    private object SeriesField {
        const val ID = 0; const val NAME = 1; const val CATEGORY = 2; const val COVER = 3; const val RATING = 4; const val YEAR = 5
        const val RELEASE = 6; const val PLOT = 7; const val MODIFIED = 8; const val BACKDROP_PATH = 9; const val BACKDROP = 10
        const val COVER_BIG = 11; const val CAST = 12; const val ACTORS = 13; const val GENRE = 14; const val TMDB_ID = 15; const val TMDB = 16
        val KEYS = arrayOf("series_id", "name", "category_id", "cover", "rating", "year", "releaseDate", "plot", "last_modified",
            "backdrop_path", "backdrop", "cover_big", "cast", "actors", "genre", "tmdb_id", "tmdb")
    }

    private fun seriesFrom(source: Source, r: Array<String?>): Series? {
        val seriesId = r[SeriesField.ID] ?: return null
        val name = r[SeriesField.NAME] ?: return null
        return Series(
            sourceId = source.id,
            seriesId = seriesId,
            name = name,
            categoryId = r[SeriesField.CATEGORY],
            posterUrl = r[SeriesField.COVER]?.takeIf { it.isNotBlank() },
            rating = r[SeriesField.RATING]?.toDoubleOrNull(),
            year = r[SeriesField.YEAR].wholeInt() ?: r[SeriesField.RELEASE]?.take(4)?.toIntOrNull(),
            plot = r[SeriesField.PLOT],
            addedMillis = r[SeriesField.MODIFIED].wholeLong()?.times(1000) ?: 0L,
            // get_series carries most of this inline on the majority of panels; anything missing is back-filled from
            // get_series_info on the first detail open. Same order as asBackdropUrl ("cover_big", then "cover").
            backdropUrl = r[SeriesField.BACKDROP_PATH] ?: r[SeriesField.BACKDROP]
                ?: r[SeriesField.COVER_BIG]?.takeIf { it.isNotBlank() } ?: r[SeriesField.COVER]?.takeIf { it.isNotBlank() },
            cast = r[SeriesField.CAST] ?: r[SeriesField.ACTORS],
            genre = r[SeriesField.GENRE],
            tmdbId = r[SeriesField.TMDB_ID] ?: r[SeriesField.TMDB],
        )
    }

    /**
     * Episode list for one series.
     *
     * `episodes` is an object keyed by season number whose values are arrays — except on some
     * panels, where it is an array of arrays, and on others where a season with a single
     * episode collapses to a bare object. All three shapes are handled.
     */
    suspend fun episodes(source: Source, seriesId: String): List<Episode> =
        withContext(Dispatchers.IO) {
            val body = getJson(source, "get_series_info") { it.addQueryParameter("series_id", seriesId) }
            val episodesNode = body.jsonObjectOrNull?.get("episodes") ?: return@withContext emptyList()

            val seasonBuckets: List<Pair<Int?, JsonElement>> = when (episodesNode) {
                is JsonObject -> episodesNode.entries.map { it.key.toIntOrNull() to it.value }
                is JsonArray -> episodesNode.mapIndexed { index, value -> index to value }
                else -> emptyList()
            }

            seasonBuckets.flatMap { (seasonHint, bucket) ->
                val items: List<JsonElement> = when (bucket) {
                    is JsonArray -> bucket.toList()
                    is JsonObject -> listOf(bucket)
                    else -> emptyList()
                }
                items.mapNotNull { element ->
                    val obj = element.jsonObjectOrNull ?: return@mapNotNull null
                    val episodeId = obj["id"].asStringOrNull ?: return@mapNotNull null
                    val info = obj["info"].jsonObjectOrNull
                    val extension = obj["container_extension"].asStringOrNull
                        ?.takeIf { it.isNotBlank() }
                    Episode(
                        sourceId = source.id,
                        seriesId = seriesId,
                        episodeId = episodeId,
                        season = obj["season"].asIntOrNull ?: seasonHint ?: 1,
                        episodeNumber = obj["episode_num"].asIntOrNull ?: 0,
                        title = obj["title"].asStringOrNull ?: "Episode",
                        plot = info?.get("plot").asStringOrNull,
                        durationSeconds = info?.get("duration_secs").asIntOrNull,
                        stillUrl = info?.get("movie_image").asStringOrNull?.takeIf { it.isNotBlank() },
                        streamUrl = seriesStreamUrl(source, episodeId, extension),
                    )
                }
            }
        }

    /**
     * Rich metadata for a single movie, from `get_vod_info`'s `info` object.
     *
     * The streams list ([movies]) is deliberately lightweight; the heavy fields (backdrop art,
     * cast, director, genre) live behind this per-title call, which the detail screen makes lazily
     * on open. Every field is best-effort — a panel that omits `info` entirely yields null, not an
     * error, so the caller can fall back to whatever the list already had.
     */
    suspend fun movieInfo(source: Source, streamId: String): VodInfo? = withContext(Dispatchers.IO) {
        val body = runCatching {
            getJson(source, "get_vod_info") { it.addQueryParameter("vod_id", streamId) }
        }.getOrNull() ?: return@withContext null
        val info = body.jsonObjectOrNull?.get("info").jsonObjectOrNull ?: return@withContext null
        VodInfo(
            backdropUrl = info.asBackdropUrl("movie_image", "cover_big"),
            cast = info["cast"].asStringOrNull ?: info["actors"].asStringOrNull,
            director = info["director"].asStringOrNull,
            genre = info["genre"].asStringOrNull,
            tmdbId = info["tmdb_id"].asStringOrNull ?: info["tmdb"].asStringOrNull,
            plot = info["plot"].asStringOrNull ?: info["description"].asStringOrNull,
            rating = info["rating"].asDoubleOrNull,
            year = info["year"].asIntOrNull ?: info["releasedate"].asStringOrNull?.take(4)?.toIntOrNull(),
            durationSeconds = info["duration_secs"].asIntOrNull,
        )
    }

    /**
     * What a provider's copy of a movie is technically (picture size, codecs, bitrate, length), from `get_vod_info`'s
     * `info.video` / `info.audio` blocks (ffprobe output on most panels). Null when the panel doesn't say.
     */
    suspend fun movieTech(source: Source, streamId: String): VodTech? = withContext(Dispatchers.IO) {
        val body = runCatching { getJson(source, "get_vod_info") { it.addQueryParameter("vod_id", streamId) } }.getOrNull() ?: return@withContext null
        val info = body.jsonObjectOrNull?.get("info").jsonObjectOrNull ?: return@withContext null
        val video = info["video"].jsonObjectOrNull
        val audio = info["audio"].jsonObjectOrNull
        val tech = VodTech(
            width = video?.get("width").asIntOrNull, height = video?.get("height").asIntOrNull,
            videoCodec = video?.get("codec_name").asStringOrNull,
            hdr = video?.get("color_transfer").asStringOrNull?.let { it == "smpte2084" || it == "arib-std-b67" } == true,
            audioCodec = audio?.get("codec_name").asStringOrNull, channels = audio?.get("channels").asIntOrNull,
            bitrateKbps = info["bitrate"].asIntOrNull, durationSeconds = info["duration_secs"].asIntOrNull,
        )
        tech.takeIf { it.height != null || it.videoCodec != null || it.bitrateKbps != null }
    }

    data class VodTech(val width: Int?, val height: Int?, val videoCodec: String?, val hdr: Boolean, val audioCodec: String?,
        val channels: Int?, val bitrateKbps: Int?, val durationSeconds: Int?)

    /**
     * Rich metadata for a single series, from `get_series_info`'s `info` object. Same contract as
     * [movieInfo]; series carry no director. This shares the `get_series_info` endpoint with
     * [episodes] but is a separate call — the detail screen fetches episodes and info independently,
     * and most series are never opened, so paying for both only when needed is the point.
     */
    suspend fun seriesInfo(source: Source, seriesId: String): SeriesInfo? = withContext(Dispatchers.IO) {
        val body = runCatching {
            getJson(source, "get_series_info") { it.addQueryParameter("series_id", seriesId) }
        }.getOrNull() ?: return@withContext null
        val info = body.jsonObjectOrNull?.get("info").jsonObjectOrNull ?: return@withContext null
        SeriesInfo(
            backdropUrl = info.asBackdropUrl("cover_big", "cover"),
            cast = info["cast"].asStringOrNull ?: info["actors"].asStringOrNull,
            genre = info["genre"].asStringOrNull,
            tmdbId = info["tmdb_id"].asStringOrNull ?: info["tmdb"].asStringOrNull,
            plot = info["plot"].asStringOrNull ?: info["description"].asStringOrNull,
            rating = info["rating"].asDoubleOrNull,
            year = info["year"].asIntOrNull ?: info["releaseDate"].asStringOrNull?.take(4)?.toIntOrNull(),
        )
    }

    /** Opens the XMLTV guide as a stream. The caller must close it. */
    suspend fun openEpgStream(source: Source): InputStream = withContext(Dispatchers.IO) {
        // jasktv-core's copy of the guide first (fetched from the same address, every six hours): the provider's
        // connection isn't held while a TV downloads 60 MB.
        coreRequest(source, "xmltv")?.let { r ->
            val response = runCatching { coreHttp.newCall(r).execute() }.getOrNull()
            if (response != null && response.isSuccessful && response.body != null) return@withContext response.body!!.byteStream()
            response?.close()
            app.opentv.account.CoreDirectory.forget()
        }
        val url = source.epgUrl?.toHttpUrlOrNull() ?: xmltvUrl(source)
        val response = http.newCall(request(source, url)).execute()
        if (!response.isSuccessful) {
            response.close()
            throw XtreamException(AppText.get(R.string.err_guide_http, response.code))
        }
        response.body?.byteStream()
            ?: throw XtreamException(AppText.get(R.string.err_guide_empty_server))
    }

    // ---- URL construction ----------------------------------------------------------------

    fun liveStreamUrl(source: Source, streamId: String): String =
        "${source.url}/live/${source.username}/${source.password}/$streamId.${source.liveFormat.extension}"

    fun vodStreamUrl(source: Source, streamId: String, extension: String?): String =
        "${source.url}/movie/${source.username}/${source.password}/$streamId.${extension ?: "mp4"}"

    fun seriesStreamUrl(source: Source, episodeId: String, extension: String?): String =
        "${source.url}/series/${source.username}/${source.password}/$episodeId.${extension ?: "mp4"}"

    /**
     * Catch-up / archive stream for a past programme. Uses the widely-supported path form
     * `/timeshift/{user}/{pass}/{durationMinutes}/{yyyy-MM-dd:HH-mm}/{streamId}.ts`, which returns a
     * seekable MPEG-TS just like a recording. The start time is formatted in the device's local
     * zone, matching how the guide shows programme times.
     */
    fun catchupUrl(source: Source, streamId: String, startUtcMillis: Long, durationMinutes: Int): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US)
            .format(java.util.Date(startUtcMillis))
        val minutes = durationMinutes.coerceAtLeast(1)
        return "${source.url}/timeshift/${source.username}/${source.password}/$minutes/$stamp/$streamId.ts"
    }

    private fun xmltvUrl(source: Source): HttpUrl =
        baseUrl(source).newBuilder()
            .encodedPath("/xmltv.php")
            .addQueryParameter("username", source.username.orEmpty())
            .addQueryParameter("password", source.password.orEmpty())
            .build()

    private fun baseUrl(source: Source): HttpUrl =
        source.url.toHttpUrlOrNull()
            ?: throw XtreamException("\"${source.url}\" is not a valid server address.")

    // ---- Plumbing ------------------------------------------------------------------------

    /**
     * Runs a blocking OkHttp call so that cancelling the coroutine (Pause syncing, leaving the screen) aborts the
     * download straight away. A blocking read never checks for cancellation, so a 70 MB catalogue would otherwise
     * finish downloading and parsing first. [block] runs on the IO pool; the response is closed afterwards.
     */
    private suspend fun <T> okhttp3.Call.cancellable(
        on: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
        block: suspend (okhttp3.Response) -> T,
    ): T = kotlinx.coroutines.coroutineScope {
        val call = this@cancellable
        // Unconfined: the abort runs at once on the cancelling thread, even if [on]'s threads are all busy reading.
        val watcher = launch(Dispatchers.Unconfined) { try { kotlinx.coroutines.awaitCancellation() } finally { call.cancel() } }
        try {
            withContext(on) { call.execute().use { block(it) } }
        } catch (e: java.io.IOException) {
            // The abort surfaces as an IOException ("Canceled"): report it as the cancellation it is.
            if (call.isCanceled()) throw kotlinx.coroutines.CancellationException("Download stopped").apply { initCause(e) }
            throw e
        } finally { watcher.cancel() }
    }

    private suspend fun getJson(
        source: Source,
        action: String?,
        viaCore: Boolean = true,
        extra: (HttpUrl.Builder) -> Unit = {},
    ): JsonElement {
        val builder = baseUrl(source).newBuilder()
            .encodedPath("/player_api.php")
            .addQueryParameter("username", source.username.orEmpty())
            .addQueryParameter("password", source.password.orEmpty())
        if (action != null) builder.addQueryParameter("action", action)
        extra(builder)

        // Categories and live lists: from jasktv-core's copy when one answers (same bytes as the provider's).
        if (viaCore && action in CORE_CACHED) coreRequest(source, action!!)?.let { r ->
            val parsed = try {
                coreHttp.newCall(r).cancellable { response ->
                    response.body?.string()?.takeIf { response.isSuccessful && it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { app.opentv.account.CoreDirectory.forget(); null }
            if (parsed != null) return parsed
        }

        return http.newCall(request(source, builder.build())).cancellable { response ->
            if (!response.isSuccessful) {
                throw XtreamException(describeHttpFailure(response.code))
            }
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) throw XtreamException(AppText.get(R.string.err_empty_response))
            try {
                json.parseToJsonElement(text)
            } catch (e: Exception) {
                // Panels behind a captive portal or Cloudflare return HTML here. Saying
                // "not valid JSON" is useless to a user; say what it probably means.
                throw XtreamException(
                    AppText.get(R.string.err_not_catalogue),
                    e,
                )
            }
        }
    }

    /**
     * Streams a player_api JSON array of objects, calling [onBatch] every [STREAM_BATCH] elements, so memory stays flat
     * however large the catalogue is. A non-array reply (some panels send `{}` for "nothing") yields no batches.
     *
     * Each element becomes an array of [keys]' values as strings (numbers and booleans as written; for an array, its
     * first non-blank string; empty and literal "null" become null). Fields not in [keys] are skipped without being
     * read into memory. Parsing into a JSON tree first cost ~20 MB of short-lived objects a second on a big list,
     * and the collections that followed cost the UI frames.
     */
    // Parsing 180k titles is the heaviest CPU work in the app: background priority (see BackgroundWork).
    private suspend fun streamRows(
        source: Source,
        action: String,
        keys: Array<String>,
        onBatch: suspend (List<Array<String?>>) -> Unit,
    ): ListCopy = withContext(app.opentv.core.BackgroundWork.dispatcher) {
        val url = baseUrl(source).newBuilder()
            .encodedPath("/player_api.php")
            .addQueryParameter("username", source.username.orEmpty())
            .addQueryParameter("password", source.password.orEmpty())
            .addQueryParameter("action", action)
            .build()
        val index = HashMap<String, Int>(keys.size * 2).apply { keys.forEachIndexed { i, k -> put(k, i) } }
        // From jasktv-core when one answers: only what changed since this device's last copy, or the whole list; the
        // provider when it doesn't (or fails). Rows are upserted by provider id, so a copy cut off halfway and then
        // read again from the provider is harmless.
        coreCopy(source, action)?.let { (response, copy) ->
            // Not a list, or a whole copy with nothing in it (a core whose cache is empty or broken): use the provider.
            // Taken as a whole list, an empty copy emptied the film inventory until the next good sync.
            val ok = try {
                val rows = response.use { readRows(it, keys, index, onBatch) }
                rows > 0 || (rows == 0 && copy.delta)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { app.opentv.account.CoreDirectory.forget(); false }
            if (ok) return@withContext copy
        }
        http.newCall(request(source, url)).cancellable(app.opentv.core.BackgroundWork.dispatcher) { response ->
            if (!response.isSuccessful) throw XtreamException(describeHttpFailure(response.code))
            readRows(response, keys, index, onBatch)
        }
        ListCopy.WHOLE
    }

    private suspend fun readRows(
        response: okhttp3.Response,
        keys: Array<String>,
        index: Map<String, Int>,
        onBatch: suspend (List<Array<String?>>) -> Unit,
    ): Int {
        // Rows read; -1 when the body isn't a JSON array at all (an error object, an empty body).
        var count = -1
        run {
            val stream = java.io.BufferedInputStream(response.body?.byteStream() ?: return@run, 64 * 1024)
            // Peek at the first non-blank byte: only an array is streamable.
            stream.mark(64)
            var first: Int
            do { first = stream.read() } while (first != -1 && first.toChar().isWhitespace())
            stream.reset()
            if (first != '['.code) return@run
            count = 0
            val reader = android.util.JsonReader(java.io.InputStreamReader(stream, Charsets.UTF_8)).apply { isLenient = true }
            val batch = ArrayList<Array<String?>>(STREAM_BATCH)
            reader.beginArray()
            while (reader.hasNext()) {
                if (reader.peek() != android.util.JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue }
                val row = arrayOfNulls<String>(keys.size)
                reader.beginObject()
                while (reader.hasNext()) {
                    val at = index[reader.nextName()]
                    if (at == null) reader.skipValue() else row[at] = reader.readField()
                }
                reader.endObject()
                batch += row
                count++
                if (batch.size >= STREAM_BATCH) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    onBatch(batch.toList())
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) onBatch(batch.toList())
        }
        return count
    }

    /** One field's value as a string (see [streamRows]); objects are skipped. */
    private fun android.util.JsonReader.readField(): String? = when (peek()) {
        android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> nextString().takeIf { it.isNotEmpty() && it != "null" }
        android.util.JsonToken.BOOLEAN -> nextBoolean().toString()
        android.util.JsonToken.NULL -> { nextNull(); null }
        android.util.JsonToken.BEGIN_ARRAY -> {
            beginArray()
            var found: String? = null
            while (hasNext()) {
                if (found == null && peek() == android.util.JsonToken.STRING) found = nextString().takeIf { it.isNotBlank() && it != "null" }
                else skipValue()
            }
            endArray()
            found
        }
        else -> { skipValue(); null }
    }

    private fun String?.wholeInt(): Int? = this?.substringBefore('.')?.toIntOrNull()
    private fun String?.wholeLong(): Long? = this?.substringBefore('.')?.toLongOrNull()

    // ---- jasktv-core ----

    /** Core's copy of one of [CORE_CACHED] for this playlist, when a core answers and keeps them. */
    private fun coreRequest(source: Source, action: String): Request? = coreFor(source, action)?.let { coreRequest(source, action, it) }

    /** The best answering core that keeps [action]'s list (live lists: only a full core keeps them). */
    private fun coreFor(source: Source, action: String): app.opentv.account.CoreDirectory.Endpoint? {
        if (source.kind != app.opentv.data.model.SourceKind.XTREAM || (action !in CORE_CACHED && action != "xmltv")) return null
        return app.opentv.account.CoreDirectory.endpoint(when (action) { in CORE_LIVE -> "live-cache"; "xmltv" -> "guide"; else -> "xtream-cache" })
    }

    private fun coreRequest(source: Source, action: String, core: app.opentv.account.CoreDirectory.Endpoint): Request =
        Request.Builder().url("${core.base}/xtream/${app.opentv.account.sync.LanCatalogue.sourceKey(source)}/$action")
            .header("Authorization", "Bearer ${core.secret}").build()

    /**
     * Asks jasktv-core for [action]'s list: only the changes since this device's last copy from that core when it keeps
     * them, else the whole list. The open response (read it, then close it) and how it came. Null: no core answered.
     */
    private fun coreCopy(source: Source, action: String): Pair<okhttp3.Response, ListCopy>? {
        val core = coreFor(source, action) ?: return null
        val plain = coreRequest(source, action, core)
        val key = "${core.id}|${app.opentv.account.sync.LanCatalogue.sourceKey(source)}|$action"
        val since = if ("deltas" in core.capabilities) app.opentv.account.CoreDirectory.since(key) else 0L
        val request = if (since > 0) plain.newBuilder().url(plain.url.newBuilder().addQueryParameter("since", since.toString()).build()).build() else plain
        val response = runCatching { coreHttp.newCall(request).execute() }.getOrNull()
        if (response == null || !response.isSuccessful) { response?.close(); app.opentv.account.CoreDirectory.forget(); return null }
        val now = response.header("X-Core-Now")?.toLongOrNull()
        val delta = response.header("X-Core-Delta") == "1"
        val removed = if (!delta) emptyList() else runCatching {
            val r = plain.newBuilder().url(plain.url.newBuilder().addPathSegment("removed").addQueryParameter("since", since.toString()).build()).build()
            coreHttp.newCall(r).execute().use { res ->
                if (!res.isSuccessful) throw java.io.IOException("removed: HTTP ${res.code}")
                (json.parseToJsonElement(res.body!!.string()) as? JsonArray)?.mapNotNull { it.asStringOrNull }.orEmpty()
            }
        }.getOrElse { response.close(); return null }
        return response to ListCopy(delta, removed) { now?.let { app.opentv.account.CoreDirectory.setSince(key, it) } }
    }

    /** Core is on the home network: a short connect timeout, so a core that just went away costs seconds. */
    private val coreHttp: OkHttpClient by lazy { http.newBuilder().connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build() }

    private fun request(source: Source, url: HttpUrl): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", source.userAgent)
            .build()

    private fun describeHttpFailure(code: Int): String = when (code) {
        401, 403 -> AppText.get(R.string.err_http_auth, code)
        404 -> AppText.get(R.string.err_http_404)
        405 -> AppText.get(R.string.err_http_405_x)
        429 -> AppText.get(R.string.err_http_429_x)
        in 500..599 -> AppText.get(R.string.err_http_5xx_x, code)
        else -> AppText.get(R.string.err_http, code)
    }

    data class AccountInfo(
        val username: String?,
        val expiryMillis: Long?,
        val maxConnections: Int?,
        val activeConnections: Int?,
        val timezone: String?,
    )

    /**
     * Rich per-movie metadata from `get_vod_info`. Every field is nullable — the caller merges it
     * over the (possibly sparse) row it already has, only filling blanks. `plot`/`rating`/`year`/
     * `durationSeconds` are included because `get_vod_info` often carries a fuller version than the
     * streams list did.
     */
    data class VodInfo(
        val backdropUrl: String?,
        val cast: String?,
        val director: String?,
        val genre: String?,
        val tmdbId: String?,
        val plot: String?,
        val rating: Double?,
        val year: Int?,
        val durationSeconds: Int?,
    )

    /** Rich per-series metadata from `get_series_info`. Series have no director. See [VodInfo]. */
    data class SeriesInfo(
        val backdropUrl: String?,
        val cast: String?,
        val genre: String?,
        val tmdbId: String?,
        val plot: String?,
        val rating: Double?,
        val year: Int?,
    )
}

// ---- Defensive JSON accessors ------------------------------------------------------------
// Every one of these returns null rather than throwing. See the class doc for why.

private val JsonElement?.jsonObjectOrNull: JsonObject?
    get() = this as? JsonObject

private val JsonElement.arrayOrEmpty: List<JsonElement>
    get() = (this as? JsonArray)?.toList() ?: emptyList()

private val JsonElement?.asPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive

private val JsonElement?.asStringOrNull: String?
    get() = asPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotEmpty() && it != "null" }

/**
 * A field that is a plain URL string on some panels and a JSON array of URLs on others — which is
 * exactly how Xtream ships `backdrop_path`. Returns the first non-blank element of an array, or the
 * string itself otherwise. `asStringOrNull` already drops empties and literal "null".
 */
private val JsonElement?.firstStringOrNull: String?
    get() = when (this) {
        is JsonArray -> firstNotNullOfOrNull { it.asStringOrNull }
        else -> asStringOrNull
    }

/**
 * The best backdrop URL from an `info`/stream object: `backdrop_path` (array-aware) first, then the
 * given fallback keys (e.g. `movie_image`, `cover_big`) as plain strings. Keeps the array handling
 * in one place so [movies], [series], [movieInfo] and [seriesInfo] stay consistent.
 */
private fun JsonObject.asBackdropUrl(vararg fallbackKeys: String): String? {
    this["backdrop_path"].firstStringOrNull?.let { return it }
    this["backdrop"].firstStringOrNull?.let { return it }
    for (key in fallbackKeys) this[key].asStringOrNull?.takeIf { it.isNotBlank() }?.let { return it }
    return null
}

private val JsonElement?.asIntOrNull: Int?
    get() = asStringOrNull?.substringBefore('.')?.toIntOrNull()

private val JsonElement?.asLongOrNull: Long?
    get() = asStringOrNull?.substringBefore('.')?.toLongOrNull()

private val JsonElement?.asDoubleOrNull: Double?
    get() = asStringOrNull?.toDoubleOrNull()

/** Catalogue elements handed to the database per write while streaming a big list. */
private const val STREAM_BATCH = 1_000

/** What jasktv-core keeps a copy of: the film, series and live lists (live: only while under half an hour old). */
/**
 * How a list arrived. [delta]: from jasktv-core as only the entries that changed since this device's last copy, with
 * [removed] the ids gone; else whole. Call [commit] once everything is stored: it records the copy, so the next one
 * asks for changes from here.
 */
class ListCopy(val delta: Boolean, val removed: List<String>, val commit: () -> Unit) {
    companion object { val WHOLE = ListCopy(false, emptyList()) {} }
}

private val CORE_LIVE = setOf("get_live_streams", "get_live_categories")
private val CORE_CACHED = setOf("get_vod_streams", "get_series", "get_vod_categories", "get_series_categories") + CORE_LIVE
