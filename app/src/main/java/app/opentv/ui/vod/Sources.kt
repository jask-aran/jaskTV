package app.opentv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.torrent.Release
import app.opentv.ui.jaskFocusLift
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * One copy of a title, as the Sources panel lists it: what it is ([label], [detail]), whether it's the one to pick
 * ([recommended], with why), and what can be done with it. [play] starts it; [download] saves it to this device and
 * returns what to tell the user. Either can be null (a season pack plays nothing; a stream may not be saveable).
 */
data class SourceRow(val label: String, val detail: String, val recommended: String? = null,
    val play: (suspend () -> Boolean)? = null, val download: (suspend () -> String)? = null)

/** What a Sources screen is for: watching a copy, or saving one to this device. */
enum class SourceMode { WATCH, DOWNLOAD }

/**
 * The Sources list: every copy of a film, an episode, a season or a show, grouped by where it comes from, for one
 * [mode] (a row's tap watches it, or downloads it):
 *  - Your playlists: the provider's copies, under each playlist's name (streams, or straight downloads).
 *  - Prowlarr · through a jasktv-core: torrents its Prowlarr found, fetched by its TorrServer ([torrentRoute] names
 *    the core). Null route: no core on this network, said plainly. [torrents] null: still searching.
 * Downloads save to this device (the core's own storage is the other destination to come, for a home media server).
 */
@Composable
fun SourcesPanel(provider: List<SourceRow>, torrentRoute: String?, torrents: List<SourceRow>?, mode: SourceMode, modifier: Modifier = Modifier,
    providerNone: String = "None of your playlists has this.", playlist: String? = null,
    /** What the torrents are of, when that isn't the sheet's title (a show's Sources: the episode Play starts). */
    torrentsOf: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<SourceRow?>(null) }
    fun act(row: SourceRow) {
        if (busy != null) return
        val action: (suspend () -> String?)? = when (mode) {
            SourceMode.WATCH -> row.play?.let { p -> { if (p()) null else "Couldn't play that copy (is the core at home running?)" } }
            SourceMode.DOWNLOAD -> row.download?.let { d -> app.opentv.core.NotificationPermission.askIfNeeded(context); { d() } }
        }
        if (action == null) return
        busy = row
        scope.launch {
            val message = runCatching { action() }.getOrElse { "That didn't start" }
            busy = null
            if (message != null) android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val usable = { r: SourceRow -> if (mode == SourceMode.WATCH) r.play != null else r.download != null }
    Column(modifier) {
        Group("Your playlists" + (playlist?.let { " · $it" } ?: ""), if (provider.none(usable)) providerNone else null)
        // Many versions (languages, regions): the first few, then the rest on request.
        var all by remember { mutableStateOf(false) }
        val shown = provider.filter(usable)
        (if (all || shown.size <= 4) shown else shown.take(3)).forEach { r -> SourceLine(r, busy == r, mode) { act(r) } }
        if (!all && shown.size > 4) ShowAllLine("Show all ${shown.size} versions") { all = true }
        Group("Prowlarr" + (torrentRoute?.let { " · through $it" } ?: ""),
            if (torrentRoute == null && !app.opentv.account.CoreDirectory.checked.collectAsState().value) "Looking for jasktv-core on this network…"
            else if (torrentRoute == null) "Needs a jasktv-core on this network (its Prowlarr finds torrents, its TorrServer fetches them). None is reachable from here."
            else if (mode == SourceMode.DOWNLOAD) "Torrents${torrentsOf?.let { " of $it" }.orEmpty()} fetched by the core as they save: stay on the home network until they finish."
            else "Torrents${torrentsOf?.let { " of $it" }.orEmpty()}, streamed through the core's TorrServer.")
        when {
            torrentRoute == null -> Unit
            torrents == null -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Searching Prowlarr…", color = Color(0xFFD6D6D6), style = MaterialTheme.typography.bodyMedium)
            }
            torrents.none(usable) -> Text("No torrents found.", color = Color(0xFFBDBDBD), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp))
            else -> torrents.filter(usable).forEach { r -> SourceLine(r, busy == r, mode) { act(r) } }
        }
    }
}

@Composable
private fun Group(title: String, note: String?) {
    Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
    if (note != null) Text(note, color = Color(0xFFAAAAAA), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
    else Spacer(Modifier.padding(top = 4.dp))
}

/** "Show all N versions": a row of the list's own shape, so it reads as part of it. */
@Composable
private fun ShowAllLine(label: String, onTap: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).jaskFocusLift(1.02f).clip(RoundedCornerShape(12.dp))
        .background(Color.White.copy(alpha = .05f)).clickable(onClick = onTap).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xFFE8E8E8), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** The ★ line on your provider's own copy: one wording per mode, the same on every Sources sheet. */
fun providerRecommended(mode: SourceMode): String =
    if (mode == SourceMode.WATCH) "Streams at once, straight from your provider" else "Quickest and surest download, straight from your provider"

/**
 * A provider version's two lines: what sets it apart (language, edition, quality: [versionLabel]) over the playlist
 * it's on. Never the playlist twice, never the provider's raw name ("EN - Dexter: Resurrection (2025) (US)").
 */
fun versionLines(rawName: String, quality: String, provider: String): Pair<String, String> {
    val label = versionLabel(rawName, quality, "")
    return if (label.isBlank()) provider to "" else label to provider
}

/** One copy: tap it to watch or download it (the screen's mode). */
@Composable
private fun SourceLine(r: SourceRow, busy: Boolean, mode: SourceMode, onTap: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).jaskFocusLift(1.02f).clip(RoundedCornerShape(12.dp))
        .background(Color.White.copy(alpha = if (r.recommended != null) .13f else .05f)).clickable(enabled = !busy, onClick = onTap)
        .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (r.recommended != null) Text("★ " + r.recommended, color = Color(0xFFE8E8E8), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            Text(r.label, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (r.detail.isNotBlank()) Text(r.detail, color = Color(0xFFBDBDBD), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else Icon(if (mode == SourceMode.WATCH) Icons.Filled.PlayArrow else Icons.Filled.Download, if (mode == SourceMode.WATCH) "Watch" else "Download", tint = Color.White)
    }
}

/**
 * The Sources screen (a sheet): a Watch / Download switch, then [content] (the Sources list for that mode; [top] adds
 * a show's scope chips). Opened from Sources (Watch) or a Download button (Download).
 */
@Composable
fun SourcesSheet(title: String, subtitle: String?, mode: SourceMode, onMode: ((SourceMode) -> Unit)?, onDismiss: () -> Unit,
    top: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    app.opentv.ui.JaskSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).heightIn(max = 680.dp).verticalScroll(rememberScrollState())) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = Color(0xFFBDBDBD), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Watch (Sources, beside Play) and Download are separate menus; only a sheet that's both offers the switch.
            if (onMode != null) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                app.opentv.ui.settings.JaskChoicePill("Watch", selected = mode == SourceMode.WATCH) { onMode(SourceMode.WATCH) }
                app.opentv.ui.settings.JaskChoicePill("Download", selected = mode == SourceMode.DOWNLOAD) { onMode(SourceMode.DOWNLOAD) }
            }
            Text(if (mode == SourceMode.DOWNLOAD) "Download · saves to this device" else "Watch · streams now", color = Color(0xFF9E9E9E), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
            top()
            content()
            Spacer(Modifier.padding(bottom = 14.dp))
        }
    }
}

// ---- Torrents: what a release covers, and its row ---------------------------------------------------------------

/** What a torrent holds: one episode, a season, or several seasons (a complete series). */
enum class PackScope { EPISODE, SEASON, SHOW }

private val MULTI = Regex("""COMPLETE|\bS\d{1,2}\s*[-–]\s*S?\d{1,2}\b|SEASONS?\s*\d+\s*[-–]\s*\d+|\bALL SEASONS\b""", RegexOption.IGNORE_CASE)
private val EPISODE = Regex("""\bS\d{1,2}\s*E\d{1,3}\b|\b\d{1,2}x\d{2}\b""", RegexOption.IGNORE_CASE)

fun packScope(name: String): PackScope = when {
    EPISODE.containsMatchIn(name) -> PackScope.EPISODE
    MULTI.containsMatchIn(name) -> PackScope.SHOW
    else -> PackScope.SEASON
}

private fun resolutionLabel(r: Release) = when (r.resolution) { 4 -> "4K"; 3 -> "1080p"; 2 -> "720p"; else -> "SD" }

/** How a torrent is likely to go, from its seeders (people sharing the whole of it). */
fun torrentHealth(seeders: Int?): String {
    val count = seeders?.let { if (it == 1) "1 seeder" else "$it seeders" }
    return when {
        seeders == null -> "seeders unknown"
        seeders >= 50 -> "Fast · $count"
        seeders >= 10 -> "Good · $count"
        seeders >= 3 -> "Slow · $count"
        else -> "May not finish · $count"
    }
}

fun torrentDetail(r: Release, extra: String? = null) = listOfNotNull(resolutionLabel(r), r.hdr, r.sizeBytes?.let { "%.1f GB".format(it / 1e9) },
    // Not the core it came through: the Prowlarr section's header already names it.
    torrentHealth(r.seeders), extra).joinToString(" · ")

/** Why [r] is the one to pick, for its ★ line. */
fun recommendReason(r: Release) = "Recommended: ${resolutionLabel(r)}, ${torrentHealth(r.seeders).substringBefore(" ·").lowercase()}, a sensible size for a device"

/**
 * Core's address for one episode's file inside a pack: the same torrent, with the season and episode it should pick
 * (core opens the torrent and finds that episode's file). Null when the copy didn't come through a core.
 */
fun episodeInPack(r: Release, season: Int, episode: Int): String? {
    val url = r.directUrl?.toHttpUrlOrNull() ?: return null
    if (!url.encodedPath.contains("/play")) return null
    return url.newBuilder().setQueryParameter("s", season.toString()).setQueryParameter("e", episode.toString()).build().toString()
}

/**
 * The torrent worth saving on a device: at least a few seeders, 1080p before 720p before the rest, within a sensible
 * size (an episode up to 4 GB, a film up to 8 GB), most seeders first. 4K is left out: a phone gains little from it.
 */
fun bestForDevice(list: List<Release>, episode: Boolean, episodes: Int = 1): Release? {
    // A pack of [episodes] is judged per episode (a 1080p season of 8 is fine at 20 GB).
    val limit = (if (episode) 4e9 else 8e9) * episodes.coerceAtLeast(1)
    return list.filter { r -> r.resolution < 4 && (r.directUrl != null || (r.seeders ?: 0) >= 3) && (r.sizeBytes ?: 0) < limit }
        .sortedWith(compareByDescending<Release> { it.resolution }.thenByDescending { it.seeders ?: 0 }).firstOrNull()
}

/** Plays an address that isn't a provider title (a torrent through a core): (playback key, address, title). MainScreen provides it. */
internal val LocalJaskPlayUrl = androidx.compose.runtime.staticCompositionLocalOf<(String, String, String) -> Unit> { { _, _, _ -> } }

/** The core torrents come through right now (its name), following the cores as they're found or lost. */
@Composable
fun rememberTorrentRoute(): String? {
    val cores by app.opentv.account.CoreDirectory.available.collectAsState()
    return cores.firstOrNull { "stremio" in it.capabilities }?.name
}

private val LANGUAGES = mapOf("EN" to "English", "GR" to "Greek", "SE" to "Swedish", "AL" to "Albanian", "DE" to "German", "IT" to "Italian",
    "FR" to "French", "ES" to "Spanish", "PL" to "Polish", "NL" to "Dutch", "AR" to "Arabic", "PT" to "Portuguese", "TR" to "Turkish",
    "IN" to "Hindi", "IR" to "Persian", "RU" to "Russian", "DK" to "Danish", "NO" to "Norwegian", "FI" to "Finnish", "SC" to "Nordic",
    "KR" to "Korean", "JP" to "Japanese", "CN" to "Chinese", "HU" to "Hungarian", "RO" to "Romanian", "CZ" to "Czech", "EXYU" to "Ex-Yu")
private val TAG_WORDS = mapOf("NF" to "Netflix", "PCOK" to "Peacock", "AMZN" to "Prime Video", "AMZ" to "Prime Video", "ATVP" to "Apple TV+", "DSNP" to "Disney+",
    "HMAX" to "Max", "MAX" to "Max", "PMTP" to "Paramount+", "HULU" to "Hulu", "STAN" to "Stan", "SUBS" to "subtitles", "DUB" to "dubbed", "MULTI" to "multi-language", "TOP" to "Top picks")

/**
 * A provider version, as people tell them apart: its language and edition from the provider's tag ("4K-NF - El
 * Camino" → "Netflix · 4K", "GR - …" → "Greek"), plus [quality] when known. Untagged: [fallback].
 */
fun versionLabel(rawName: String, quality: String, fallback: String): String {
    val tag = rawName.substringBefore(" - ", "").trim().takeIf { it.isNotEmpty() && it.length <= 12 && it.none(Char::isLowerCase) }
    val parts = tag?.split('-', ' ')?.filter(String::isNotBlank).orEmpty()
    val res = parts.firstOrNull { it in setOf("4K", "8K", "FHD", "HD", "UHD") } ?: quality.takeIf { it.isNotBlank() }
    val words = parts.filterNot { it in setOf("4K", "8K", "FHD", "HD", "UHD") }.map { LANGUAGES[it] ?: TAG_WORDS[it] ?: it }
    return (words + listOfNotNull(res)).joinToString(" · ").ifBlank { fallback }
}

/** 0: tagged English, 1: no language tag (usually English: "NF", "4K", "TOP"), 2: another language. */
fun englishFirst(rawName: String): Int {
    val parts = rawName.substringBefore(" - ", "").uppercase().split('-', ' ', '|').filter(String::isNotBlank)
    return when {
        "EN" in parts || "ENG" in parts -> 0
        parts.none { it in LANGUAGES } -> 1
        else -> 2
    }
}

/**
 * What a playlist's copy of a film actually is, for its Sources row: picture size, HDR, codec, size of the file
 * ("1080p · HEVC · 2.1 GB"), as a torrent's row shows. From the provider's `get_vod_info` and a HEAD request on the
 * file (no download). Kept for this run of the app, so reopening Sources doesn't ask again.
 */
object ProviderTech {
    private val known = androidx.compose.runtime.mutableStateMapOf<Long, String>()

    fun line(movieId: Long): String? = known[movieId]

    suspend fun fetch(context: android.content.Context, movie: app.opentv.data.model.Movie) {
        if (movie.id in known) return
        known[movie.id] = "" // asked; blank until (or unless) something comes back
        val graph = app.opentv.core.ServiceLocator.get(context)
        val source = graph.sourceRepository.byId(movie.sourceId) ?: return
        // The file itself first: its size, and for MKV (most provider films) its picture size and codec, from the
        // first 256 KB. Many panels give neither in get_vod_info (strong8k: no video block, bitrate 0, HEAD → 520).
        if (!app.opentv.data.repo.DownloadEngine.mayConnect(movie.streamUrl)) { known.remove(movie.id); return } // a download holds it; ask next time
        val head = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fileHead(graph.httpClient, movie.streamUrl, source.userAgent) }
        val tech = if (head?.height == null && source.kind == app.opentv.data.model.SourceKind.XTREAM)
            runCatching { graph.xtreamApi.movieTech(source, movie.streamId) }.getOrNull() else null
        val width = head?.width ?: tech?.width
        val height = head?.height ?: tech?.height
        val codec = head?.codec ?: tech?.videoCodec
        val size = head?.bytes?.let { gb(it) } ?: tech?.let { t -> if ((t.bitrateKbps ?: 0) > 0 && t.durationSeconds != null)
            "~" + gb(t.bitrateKbps!!.toLong() * 125 * t.durationSeconds) else null }
        val parts = listOfNotNull(
            height?.let { h -> val w = width ?: 0; when { w >= 3200 || h >= 2000 -> "4K"; w >= 1800 || h >= 1000 -> "1080p"; w >= 1200 || h >= 700 -> "720p"; else -> "${h}p" } },
            "HDR".takeIf { head?.hdr == true || tech?.hdr == true },
            codec?.let { when (it.lowercase()) { "hevc", "h265" -> "HEVC"; "h264", "avc" -> "H.264"; "av1" -> "AV1"; else -> it.uppercase() } },
            tech?.channels?.takeIf { it >= 6 }?.let { if (it >= 8) "7.1" else "5.1" },
            size)
        known[movie.id] = parts.joinToString(" · ")
    }

    private val seasons = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * How much [episodes] add up to from the provider ("23.4 GB"), for the season download row: each file's size from
     * a one-byte ranged request, one after another (one-connection accounts). Null when it can't tell, or while a
     * download holds the provider.
     */
    suspend fun seasonSize(context: android.content.Context, episodes: List<app.opentv.data.model.Episode>): String? {
        if (episodes.isEmpty()) return null
        val key = episodes.joinToString(",") { it.id.toString() }
        seasons[key]?.let { return it.takeIf(String::isNotBlank) }
        val graph = app.opentv.core.ServiceLocator.get(context)
        val agent = graph.sourceRepository.byId(episodes.first().sourceId)?.userAgent ?: return null
        if (!app.opentv.data.repo.DownloadEngine.mayConnect(episodes.first().streamUrl)) return null
        val client = graph.httpClient.newBuilder().callTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build()
        var total = 0L; var known = 0
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            for (ep in episodes) {
                val bytes = runCatching {
                    client.newCall(okhttp3.Request.Builder().url(ep.streamUrl).header("User-Agent", agent).header("Range", "bytes=0-0").build()).execute()
                        .use { r -> if (r.isSuccessful) r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() else null }
                }.getOrNull()
                if (bytes != null && bytes > 0) { total += bytes; known++ }
            }
        }
        // Most of them answered: scale up for the rest ("~"); too few: say nothing rather than guess.
        val text = when {
            known == episodes.size -> gb(total)
            known >= episodes.size / 2 && known > 0 -> "~" + gb(total * episodes.size / known)
            else -> ""
        }
        seasons[key] = text
        return text.takeIf(String::isNotBlank)
    }

    private fun gb(bytes: Long) = if (bytes >= 1e9) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000} MB"

    private class Head(val bytes: Long?, val width: Int?, val height: Int?, val codec: String?, val hdr: Boolean)

    /** One ranged request: the total size (Content-Range) and, for Matroska, the video track's size and codec. */
    private fun fileHead(http: okhttp3.OkHttpClient, url: String, agent: String): Head? = runCatching {
        val client = http.newBuilder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
        client.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", agent).header("Range", "bytes=0-262143").build()).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val total = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: r.header("Content-Length")?.toLongOrNull()?.takeIf { r.code == 200 }
            val b = r.body?.source()?.let { src -> src.request(262_144); src.buffer.readByteArray(minOf(src.buffer.size, 262_144)) } ?: ByteArray(0)
            val t = app.opentv.data.repo.MediaHead.parse(b) ?: return@use Head(total, null, null, null, false)
            Head(total, t.width, t.height, t.codec, t.hdr)
        }
    }.getOrNull()
}

/** Asks for each copy's technical line once Sources is open (one at a time, so a slow panel isn't hit all at once). */
@androidx.compose.runtime.Composable
fun FetchProviderTech(movies: List<app.opentv.data.model.Movie>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(movies.map { it.id }) { movies.take(8).forEach { ProviderTech.fetch(context, it) } }
}
