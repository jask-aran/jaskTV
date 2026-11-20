package app.opentv.torrent

import app.opentv.core.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A copy of a film or episode found outside the IPTV provider: a torrent, streamed through TorrServer, or a
 * link an add-on already made playable (a debrid link). Found by [Releases.find]; turned into something the
 * player opens by [Releases.playable].
 */
data class Release(
    /** The release name as the finder gave it ("Movie.2025.2160p.WEB-DL.DV.HDR.DDP5.1-GROUP"). */
    val name: String,
    /** Where it came from: an add-on's name, or "Prowlarr · <indexer>". */
    val origin: String,
    val sizeBytes: Long? = null,
    val seeders: Int? = null,
    /** Already playable as it is. */
    val directUrl: String? = null,
    /** A magnet link, info hash or .torrent URL, for TorrServer. */
    val link: String? = null,
    /** TorrServer's file id in the torrent (1-based), when the finder knows which file it is. */
    val fileIndex: Int? = null,
) {
    /** 4 (2160p) … 1 (SD or unknown), from the name. */
    val resolution: Int get() = when {
        RES_4K.containsMatchIn(name) -> 4
        RES_1080.containsMatchIn(name) -> 3
        RES_720.containsMatchIn(name) -> 2
        else -> 1
    }
    val hdr: String? get() = when {
        DV.containsMatchIn(name) -> "Dolby Vision"
        HDR.containsMatchIn(name) -> "HDR"
        else -> null
    }
    /** What the picker shows under the name: "4K · Dolby Vision · 12.3 GB · 220 seeders · Torrentio". */
    val summary: String get() = listOfNotNull(
        when (resolution) { 4 -> "4K"; 3 -> "1080p"; 2 -> "720p"; else -> null },
        hdr,
        sizeBytes?.let { "%.1f GB".format(it / 1e9) },
        seeders?.let { "$it seeders" },
        origin,
    ).joinToString(" · ")

    companion object {
        private val RES_4K = Regex("""\b(2160p|4k|uhd)\b""", RegexOption.IGNORE_CASE)
        private val RES_1080 = Regex("""\b1080[pi]\b""", RegexOption.IGNORE_CASE)
        private val RES_720 = Regex("""\b720p\b""", RegexOption.IGNORE_CASE)
        private val DV = Regex("""\b(DV|DoVi|Dolby[ .]?Vision)\b""", RegexOption.IGNORE_CASE)
        private val HDR = Regex("""\bHDR(10\+?)?\b""", RegexOption.IGNORE_CASE)
    }
}

/** What to look for: a film, or one episode of a show. */
data class ReleaseQuery(val title: String, val year: Int?, val imdbId: String?, val season: Int? = null, val episode: Int? = null) {
    val isEpisode: Boolean get() = season != null && episode != null
}

/**
 * Finds releases for a title and makes them playable. Stremio add-ons (Torrentio-style) and Prowlarr find;
 * TorrServer streams torrents. Every part is optional and asked independently: one failing doesn't sink the rest.
 * Nothing here runs a server: TorrServer and Prowlarr are the user's (a machine on the home network today).
 */
class Releases(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Releases from every configured finder, best first: playable now, then resolution, then seeders. */
    fun find(query: ReleaseQuery, addons: List<Pair<String, String>>, prowlarrUrl: String, prowlarrKey: String,
             torrents: Boolean): List<Release> {
        val found = ArrayList<Release>()
        if (query.imdbId != null) for ((name, manifest) in addons) {
            found += runCatching { fromAddon(manifest, name, query) }.onFailure { Log.w(TAG, "Add-on $name failed", it) }.getOrDefault(emptyList())
        }
        if (prowlarrUrl.isNotBlank() && prowlarrKey.isNotBlank() && torrents) {
            found += runCatching { fromProwlarr(prowlarrUrl, prowlarrKey, query) }.onFailure { Log.w(TAG, "Prowlarr failed", it) }.getOrDefault(emptyList())
        }
        // Torrents only when there's a TorrServer to stream them.
        return found.filter { it.directUrl != null || (torrents && it.link != null) }
            .distinctBy { it.directUrl ?: hashOf(it.link) ?: it.link }
            .sortedWith(compareByDescending<Release> { it.directUrl != null }.thenByDescending { it.resolution }
                .thenByDescending { it.seeders ?: 0 })
    }

    /** A Stremio add-on's streams for the title: direct links as they are, torrents as magnets for TorrServer. */
    private fun fromAddon(manifestUrl: String, addonName: String, q: ReleaseQuery): List<Release> {
        val base = manifestUrl.trim().removeSuffix("manifest.json").trimEnd('/')
        val id = if (q.isEpisode) "${q.imdbId}:${q.season}:${q.episode}" else q.imdbId!!
        val url = "$base/stream/${if (q.isEpisode) "series" else "movie"}/$id.json".toHttpUrlOrNull() ?: return emptyList()
        val streams = getJson(Request.Builder().url(url).header("User-Agent", "jaskTV").build())
            ?.jsonObject?.get("streams")?.jsonArray ?: return emptyList()
        return streams.mapNotNull { element ->
            val s = element.jsonObject
            val text = listOfNotNull(s.str("title"), s.str("description")).joinToString("\n")
            val name = s["behaviorHints"]?.let { (it as? JsonObject)?.str("filename") } ?: text.lineSequence().firstOrNull()?.trim()
                ?: s.str("name") ?: "Stream"
            val size = SIZE.find(text)?.let { m -> (m.groupValues[1].toDoubleOrNull() ?: return@let null) * if (m.groupValues[2].equals("GB", true)) 1e9 else 1e6 }?.toLong()
            val seeders = SEEDERS.find(text)?.groupValues?.get(1)?.toIntOrNull()
            val direct = s.str("url")?.takeIf { it.startsWith("http") }
            val hash = s.str("infoHash")?.lowercase()
            if (direct == null && hash == null) return@mapNotNull null
            val trackers = (s["sources"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull?.removePrefix("tracker:")?.takeIf { t -> t.contains("://") } }.orEmpty()
            Release(name, addonName, size, seeders, directUrl = direct,
                link = hash?.let { magnet(it, name, trackers) },
                // Stremio counts files from 0; TorrServer from 1.
                fileIndex = (s["fileIdx"]?.jsonPrimitive?.intOrNull)?.let { it + 1 })
        }
    }

    /** Prowlarr's search across all its indexers; torrents only (Usenet needs a download client). */
    private fun fromProwlarr(baseUrl: String, apiKey: String, q: ReleaseQuery): List<Release> {
        val text = if (q.isEpisode) "${q.title} S%02dE%02d".format(q.season, q.episode) else listOfNotNull(q.title, q.year?.toString()).joinToString(" ")
        val url = "$baseUrl/api/v1/search".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("query", text)?.addQueryParameter("type", "search")
            ?.addQueryParameter("categories", if (q.isEpisode) "5000" else "2000")?.addQueryParameter("limit", "100")
            ?.build() ?: return emptyList()
        val items = getJson(Request.Builder().url(url).header("X-Api-Key", apiKey).build()) as? JsonArray ?: return emptyList()
        val words = significantWords(q.title)
        return items.mapNotNull { element ->
            val o = element.jsonObject
            if (o.str("protocol")?.equals("torrent", true) == false) return@mapNotNull null
            val name = o.str("title") ?: return@mapNotNull null
            // Search is by words: keep releases that name the title (and the year, for a film).
            val folded = fold(name)
            if (!words.all { folded.contains(it) }) return@mapNotNull null
            if (!q.isEpisode && q.year != null && !name.contains(q.year.toString()) && !name.contains((q.year - 1).toString())) return@mapNotNull null
            val hash = o.str("infoHash")?.lowercase()
            val link = o.str("magnetUrl")?.takeIf { it.startsWith("magnet:") } ?: hash?.let { magnet(it, name, emptyList()) } ?: o.str("downloadUrl")
            Release(name, "Prowlarr · ${o.str("indexer") ?: "?"}", o["size"]?.jsonPrimitive?.longOrNull, o["seeders"]?.jsonPrimitive?.intOrNull, link = link)
        }
    }

    /**
     * Something the player opens: a direct link as it is, a torrent as a TorrServer stream. For a torrent whose
     * file isn't known (Prowlarr), TorrServer is asked for its file list and the largest video file is played.
     */
    fun playable(release: Release, torrServerUrl: String): String? {
        release.directUrl?.let { return it }
        val link = release.link ?: return null
        if (torrServerUrl.isBlank()) return null
        val index = release.fileIndex ?: bestFile(torrServerUrl, link) ?: 1
        return "$torrServerUrl/stream/${enc(release.name.take(80))}?link=${enc(link)}&index=$index&play"
    }

    /** Whether TorrServer answers at [baseUrl]: its version, or null. */
    fun torrServerVersion(baseUrl: String): String? = runCatching {
        http.newCall(Request.Builder().url("$baseUrl/echo").build()).execute().use { r -> if (r.isSuccessful) r.body?.string()?.trim() else null }
    }.getOrNull()

    private fun bestFile(baseUrl: String, link: String): Int? = runCatching {
        val stat = getJson(Request.Builder().url("$baseUrl/stream/stat?link=${enc(link)}&stat").build())?.jsonObject ?: return null
        (stat["file_stats"] as? JsonArray)?.map { it.jsonObject }
            ?.filter { f -> VIDEO.containsMatchIn(f.str("path").orEmpty()) && !SAMPLE.containsMatchIn(f.str("path").orEmpty()) }
            ?.maxByOrNull { it["length"]?.jsonPrimitive?.longOrNull ?: 0 }
            ?.get("id")?.jsonPrimitive?.intOrNull
    }.getOrNull()

    private fun getJson(request: Request) = runCatching {
        http.newCall(request).execute().use { r -> if (!r.isSuccessful) null else r.body?.string()?.let(json::parseToJsonElement) }
    }.getOrNull()

    private fun JsonObject.str(key: String) = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    companion object {
        private const val TAG = "Releases"
        private val SIZE = Regex("""(\d+(?:\.\d+)?)\s*(GB|MB)\b""", RegexOption.IGNORE_CASE)
        private val SEEDERS = Regex("""👤\s*(\d+)""")
        private val VIDEO = Regex("""\.(mkv|mp4|avi|m4v|mov|ts|webm)$""", RegexOption.IGNORE_CASE)
        private val SAMPLE = Regex("""\bsample\b""", RegexOption.IGNORE_CASE)
        private val HASH = Regex("""btih:([a-fA-F0-9]{40})""")
        private val STOP = setOf("the", "a", "an", "of", "and")

        fun magnet(hash: String, name: String, trackers: List<String>): String =
            "magnet:?xt=urn:btih:$hash&dn=${enc(name)}" + trackers.joinToString("") { "&tr=${enc(it)}" }

        private fun hashOf(link: String?): String? = link?.let { HASH.find(it)?.groupValues?.get(1)?.lowercase() ?: it.takeIf { l -> l.length == 40 } }
        private fun fold(text: String) = " " + text.lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim() + " "
        private fun significantWords(title: String) = fold(title).trim().split(' ').filter { it.isNotBlank() && it !in STOP }.map { " $it " }
        private fun enc(text: String) = java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    }
}
