/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import app.opentv.ui.jaskWheelScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.statusBarsPadding
import app.opentv.ui.LocalLayoutClass
import app.opentv.ui.LayoutClass
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Episode
import app.opentv.data.model.Download
import app.opentv.core.ServiceLocator
import app.opentv.ui.downloads.DownloadControl
import app.opentv.ui.downloads.startMessage
import app.opentv.data.repo.DownloadRepository
import app.opentv.ui.downloads.KIND_EPISODE
import app.opentv.ui.downloads.KIND_MOVIE
import android.widget.Toast
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import app.opentv.ui.library.CollectionPickerDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.StremioStream
import app.opentv.data.parser.displayTitle
import app.opentv.data.parser.sourceTag
import app.opentv.data.parser.episodeDisplayTitle
import app.opentv.ui.VodViewModel
import coil.compose.AsyncImage
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

/**
 * A film's cinematic detail page: a backdrop hero with title, meta and Watch/Resume + favourite
 * over a gradient scrim, then synopsis, cast and director, and a "more like this" shelf. Opening it
 * enriches the row from the provider (backdrop/cast/genre) if those fields are still bare, so a card
 * that was plain in a grid fills out here.
 */
@Composable
fun MovieDetailScreen(
    movieId: Long,
    onPlay: (Movie) -> Unit,
    onPlayUrl: (key: String, url: String, title: String) -> Unit,
    onOpenMovie: (Movie) -> Unit,
    onOpenPerson: (String) -> Unit,
    onBack: () -> Unit = {},
    viewModel: VodViewModel = viewModel(),
) = JaskTheme {
    val providers by viewModel.sources.collectAsState()
    var versionNames by remember(movieId) { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var versions by remember(movieId) { mutableStateOf<List<Movie>>(emptyList()) }
    var movie by remember(movieId) { mutableStateOf<Movie?>(null) }
    var moreLike by remember(movieId) { mutableStateOf<List<Movie>>(emptyList()) }
    var resumeExists by remember(movieId) { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    val hasAddons by viewModel.hasAddons.collectAsState()
    val scope = rememberCoroutineScope()
    // null = the add-on picker is closed; a (possibly empty) list = show it. Separate flag for the spinner.
    var addonStreams by remember(movieId) { mutableStateOf<List<StremioStream>?>(null) }
    var addonLoading by remember(movieId) { mutableStateOf(false) }
    var showCollectionPicker by remember(movieId) { mutableStateOf(false) }
    val wideLayout = LocalLayoutClass.current.isWide

    LaunchedEffect(movieId) {
        versions = viewModel.canonicalVersions(movieId)
        versionNames = versions.associate { it.id to viewModel.rawMovieName(it.id) }
        val loaded = viewModel.movieDetail(movieId)
        movie = loaded
        if (loaded != null) {
            resumeExists = viewModel.resumePosition("movie:${loaded.id}")?.let { !it.isFinished } == true
            // Wide jaskTV pages show "More like this" for every movie; the phone page doesn't show it.
            moreLike = if (versions.isEmpty() || wideLayout) viewModel.moreLikeThis(loaded)
                .filter { it.displayTitle != loaded.displayTitle }.distinctBy { it.displayTitle to it.year } else emptyList()
        }
    }

    val m = movie ?: run { LoadingDetail(); return@JaskTheme }

    // Land focus on the primary action once the page is up, so a d-pad has somewhere to be.
    LaunchedEffect(m.id) {
        delay(60)
        runCatching { playFocus.requestFocus() }
    }

    if (LocalLayoutClass.current.usesJaskShell) {
        val tint = rememberArtworkTint(m.backdropUrl ?: m.posterUrl)
        var progress by remember(m.id) { mutableStateOf<Float?>(null) }
        var minutesLeft by remember(m.id) { mutableStateOf<Long?>(null) }
        LaunchedEffect(m.id, resumeExists) {
            val position = viewModel.resumePosition("movie:${m.id}")?.takeIf { !it.isFinished && it.durationMillis > 0 }
            progress = position?.let { it.positionMillis.toFloat() / it.durationMillis }
            minutesLeft = position?.let { ((it.durationMillis - it.positionMillis) / 60_000L).coerceAtLeast(1) }
        }
        // Canonical titles carry no credits; the provider's detail call may. Fetched after the page is up.
        var credits by remember(m.id) { mutableStateOf<Pair<String?, String?>?>(null) }
        LaunchedEffect(m.id) {
            credits = runCatching { viewModel.movieCredits(m.id) }.getOrNull()?.let { it.cast to it.director }
        }
        val pool = versions.ifEmpty { listOf(m) }
        val selected = pool.indexOfFirst { it.id == m.id }.coerceAtLeast(0)
        val unknownSource = stringResource(R.string.rec_source_unknown)
        val qualityUnknown = stringResource(R.string.catalogue_quality_unknown)
        val quality = { v: Movie -> app.opentv.data.parser.MovieBrowseKey.from(versionNames[v.id].orEmpty(), v.year).qualityLabel }
        val providerName = { v: Movie -> providers.firstOrNull { it.id == v.sourceId }?.name ?: unknownSource }
        var sourceSheet by remember { mutableStateOf(false) }
        val selectVersion = { index: Int ->
            val version = pool[index]
            movie = version
            scope.launch { resumeExists = viewModel.resumePosition("movie:${version.id}")?.let { !it.isFinished } == true }
            Unit
        }
        val list = androidx.compose.foundation.lazy.rememberLazyListState()
        var heroHeight by remember { mutableStateOf(0) }
        val wideHero = app.opentv.ui.LocalLayoutClass.current != app.opentv.ui.LayoutClass.PHONE
        app.opentv.ui.JaskSurfaceHost(Modifier.fillMaxSize(), background = { Box(Modifier.fillMaxSize().jaskPageBackground(tint, list, heroHeight)) {
            LazyColumn(Modifier.fillMaxSize().jaskWheelScroll(list), state = list, contentPadding = PaddingValues(bottom = 32.dp +
                androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
                item(key = "hero") {
                    JaskHero(m.displayTitle, m.backdropUrl, m.posterUrl, HeroFacts(rating = m.rating, quality = quality(m).takeIf { it.length <= 6 },
                        text = listOfNotNull(m.year?.toString(), m.durationSeconds?.takeIf { it > 0 }?.let { formatRuntime(it) })),
                        tint, onBack, Modifier.onSizeChanged { heroHeight = it.height }, blurb = m.plot.takeIf { wideHero }) {
                        val playLabel = stringResource(if (resumeExists) R.string.vod_resume else R.string.vod_watch_now)
                        JaskPlayPill(minutesLeft?.takeIf { resumeExists }?.let { "$playLabel · " + if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" } ?: playLabel, progress) { onPlay(m) }
                        JaskSourceButton(quality(m).ifBlank { providerName(m) }, pool.size) { sourceSheet = true }
                        app.opentv.ui.JaskGlassCircle(if (m.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            stringResource(R.string.jask_watchlist), active = m.favourite) {
                            viewModel.toggleMovieFavourite(m); movie = m.copy(favourite = !m.favourite)
                        }
                        JaskGlassDownload {
                            DownloadControl(mediaKey = "movie:${m.id}", compact = true, buildDownload = {
                                Download(mediaKey = "movie:${m.id}", kind = KIND_MOVIE, title = m.displayTitle,
                                    posterUrl = m.posterUrl, tag = m.sourceTag, sourceUrl = m.streamUrl, systemId = 0, createdMillis = 0)
                            })
                        }
                        if (hasAddons) app.opentv.ui.JaskGlassCircle(Icons.Filled.Extension, stringResource(R.string.vod_addon_sources)) {
                            addonLoading = true
                            scope.launch { addonStreams = viewModel.addonStreams(m); addonLoading = false }
                        }
                    }
                }
                if (wideHero) {
                    item(key = "wide-body") {
                        JaskMovieBody(pool.map { v -> SourceOption(providerName(v), listOf(quality(v).ifBlank { qualityUnknown }, versionNames[v.id].orEmpty())
                            .filter(String::isNotBlank).joinToString(" · ")) }, selected, { selectVersion(it) },
                            genre = m.genre, cast = m.cast ?: credits?.first, director = m.director ?: credits?.second, onOpenPerson = onOpenPerson) {
                            ExternalReviews(movie = m)
                        }
                    }
                    if (moreLike.isNotEmpty()) item(key = "more") {
                        JaskShelf(stringResource(R.string.vod_more_like_this), moreLike, { it.id }) { v ->
                            JaskPosterCard(v.displayTitle, v.posterUrl, PosterFacts(rating = v.rating)) { onOpenMovie(v) }
                        }
                    }
                } else {
                    item(key = "synopsis") { JaskSynopsis(m.plot) }
                    item(key = "credits") { JaskCredits(m.genre, m.cast ?: credits?.first, m.director ?: credits?.second) }
                    item(key = "review-scores") { ExternalReviews(movie = m) }
                }
            }
        } }) {
            app.opentv.ui.JaskBarScrim(Modifier.align(Alignment.BottomCenter), top = false)
            if (sourceSheet) SourceSheet(pool.map { version ->
                val q = quality(version)
                SourceOption(providerName(version), listOf(if (q.isBlank()) qualityUnknown else "$q (inferred)", versionNames[version.id].orEmpty())
                    .filter(String::isNotBlank).joinToString(" · "))
            }, selected, { selectVersion(it) }) { sourceSheet = false }
        }
    }

    if (showCollectionPicker) CollectionPickerDialog(
        kind = "movie", sourceId = m.sourceId, itemKey = m.streamId,
        onDismiss = { showCollectionPicker = false },
    )

    if (addonLoading || addonStreams != null) {
        AddonStreamPicker(
            loading = addonLoading,
            streams = addonStreams.orEmpty(),
            onPick = { stream ->
                addonStreams = null
                onPlayUrl("movie:${m.id}", stream.url, m.displayTitle)
            },
            onDismiss = { addonStreams = null; addonLoading = false },
        )
    }
}

/**
 * The picker shown when the user taps "Add-on sources" on a film: a spinner while streams are
 * fetched, then the list each add-on returned (already filtered to directly-playable URLs). Picking
 * one plays it through the normal VOD player.
 */
@Composable
private fun AddonStreamPicker(
    loading: Boolean,
    streams: List<StremioStream>,
    onPick: (StremioStream) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp),
        ) {
            Text(
                stringResource(R.string.vod_addon_pick_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(22.dp).width(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.vod_addon_loading), style = MaterialTheme.typography.bodyMedium)
                }
                streams.isEmpty() -> Text(
                    stringResource(R.string.vod_addon_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.height(360.dp),
                ) {
                    items(streams, key = { it.url }) { stream -> AddonStreamRow(stream, onPick) }
                }
            }
        }
    }
}

@Composable
private fun AddonStreamRow(stream: StremioStream, onPick: (StremioStream) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onPick(stream) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stream.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stream.addonName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * A show's detail page: the same cinematic header (backdrop, title, meta, synopsis, cast, favourite)
 * above the episode list grouped by season, with a "more like this" shelf at the bottom. Episodes
 * are fetched on demand — pulling every episode of every series up front is what makes a first sync
 * take twenty minutes on a large provider, and most of it is never looked at.
 */
@Composable
fun SeriesDetailScreen(
    seriesId: Long,
    onPlayEpisode: (mediaKey: String, url: String, title: String) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onOpenPerson: (String) -> Unit,
    onBack: () -> Unit = {},
    viewModel: VodViewModel = viewModel(),
) {
    var series by remember(seriesId) { mutableStateOf<Series?>(null) }
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val downloadDao = remember { graph.database.downloads() }
    val downloadScope = rememberCoroutineScope()
    var moreLike by remember(seriesId) { mutableStateOf<List<Series>>(emptyList()) }
    val favFocus = remember { FocusRequester() }
    var showCollectionPicker by remember(seriesId) { mutableStateOf(false) }
    var versions by remember(seriesId) { mutableStateOf<List<Series>>(emptyList()) }
    val providers by viewModel.sources.collectAsState()

    LaunchedEffect(seriesId) {
        val loaded = viewModel.seriesDetail(seriesId)
        series = loaded
        if (loaded != null) {
            viewModel.loadEpisodes(loaded)
            versions = viewModel.seriesVersions(loaded)
            moreLike = viewModel.moreLikeThisSeries(loaded)
        }
    }

    val s = series
    val episodesFlow = remember(s?.sourceId, s?.seriesId) { s?.let { viewModel.episodes(it) } ?: viewModel.noEpisodes }
    val episodes by episodesFlow.collectAsState(initial = emptyList())

    if (s == null) { LoadingDetail(); return }

    LaunchedEffect(s.id) {
        delay(60)
        runCatching { favFocus.requestFocus() }
    }

    // Group episodes by season (seasons ascending, episodes ordered within each) — the classic
    // "episode-by-season" list.
    val seasons = remember(episodes) {
        episodes.groupBy { it.season }
            .mapValues { (_, eps) -> eps.sortedBy { it.episodeNumber } }
            .toSortedMap()
    }

    if (LocalLayoutClass.current.usesJaskShell) {
        val tint = rememberArtworkTint(s.backdropUrl ?: s.posterUrl)
        val positionsFlow = remember(s.sourceId, s.seriesId) { viewModel.seriesPositions(s) }
        val positions by positionsFlow.collectAsState(initial = emptyList())
        val byKey = remember(positions) { positions.associateBy { it.mediaKey } }
        var seasonPosters by remember(s.id) { mutableStateOf<Map<Int, String>>(emptyMap()) }
        LaunchedEffect(s.id) { seasonPosters = runCatching { viewModel.seasonPosters(s) }.getOrDefault(emptyMap()) }
        // Specials (season 0) go last, as most players order them.
        val ordered = remember(seasons) { seasons.entries.sortedBy { if (it.key == 0) Int.MAX_VALUE else it.key }.flatMap { it.value } }
        val next = remember(ordered, positions) { nextEpisode(ordered, positions) }
        val title = cleanShowTitle(s.name)
        fun play(ep: app.opentv.data.model.Episode) {
            val shown = episodeDisplayTitle(ep.title, s.name)
            onPlayEpisode("ep:${ep.id}", ep.streamUrl, "S${ep.season}E${ep.episodeNumber} · $shown")
        }
        var tmdbShow by remember(s.id) { mutableStateOf<app.opentv.catalogue.CanonicalShow?>(null) }
        LaunchedEffect(s.id) { tmdbShow = viewModel.canonicalShow(s) }
        val seasonOrder = remember(seasons) { seasons.keys.sortedBy { if (it == 0) Int.MAX_VALUE else it } }
        var chosenSeason by remember(s.id) { mutableStateOf<Int?>(null) }
        val shownSeason = chosenSeason?.takeIf { it in seasons } ?: next?.first?.season ?: seasonOrder.firstOrNull()
        val summaries = remember(seasons, positions, seasonPosters) {
            seasonOrder.map { n ->
                val eps = seasons[n].orEmpty()
                SeasonSummary(n, eps.size, eps.count { byKey["ep:${it.id}"]?.isFinished == true }, seasonPosters[n])
            }
        }
        val unknownSource = stringResource(R.string.rec_source_unknown)
        val pool = versions.ifEmpty { listOf(s) }
        val providerName = { v: Series -> providers.firstOrNull { it.id == v.sourceId }?.name ?: unknownSource }
        var sourceSheet by remember { mutableStateOf(false) }
        val list = androidx.compose.foundation.lazy.rememberLazyListState()
        var heroHeight by remember { mutableStateOf(0) }
        val wideHero = app.opentv.ui.LocalLayoutClass.current != app.opentv.ui.LayoutClass.PHONE
        app.opentv.ui.JaskSurfaceHost(Modifier.fillMaxSize(), background = { Box(Modifier.fillMaxSize().jaskPageBackground(tint, list, heroHeight)) {
            LazyColumn(Modifier.fillMaxSize().jaskWheelScroll(list), state = list, contentPadding = PaddingValues(bottom = 32.dp +
                androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
                item(key = "hero") {
                    JaskHero(title, s.backdropUrl, s.posterUrl, HeroFacts(rating = s.rating, networkLogo = tmdbShow?.networkLogo, network = tmdbShow?.network,
                        text = listOfNotNull(s.year?.toString(), seasons.keys.count { it > 0 }.takeIf { it > 0 }?.let { if (it == 1) "1 season" else "$it seasons" },
                            tmdbShow?.statusLabel)),
                        tint, onBack, Modifier.onSizeChanged { heroHeight = it.height }, blurb = s.plot.takeIf { wideHero }) {
                        if (next == null) JaskPlayPill(stringResource(R.string.vod_loading_episodes)) {}
                        else {
                            val (ep, pos) = next
                            val resuming = pos != null && !pos.isFinished && pos.positionMillis > 0
                            JaskPlayPill((if (resuming) "Resume" else "Play") + " S${ep.season} · E${ep.episodeNumber}",
                                pos?.takeIf { resuming && it.durationMillis > 0 }?.let { it.positionMillis.toFloat() / it.durationMillis }) { play(ep) }
                        }
                        JaskSourceButton(providerName(s), pool.size) { sourceSheet = true }
                        app.opentv.ui.JaskGlassCircle(if (s.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            stringResource(R.string.jask_watchlist), active = s.favourite) {
                            viewModel.toggleSeriesFavourite(s); series = s.copy(favourite = !s.favourite)
                        }
                    }
                }
                if (!wideHero) item(key = "synopsis") { JaskSynopsis(s.plot) }
                item(key = "credits") { JaskCredits(s.genre, s.cast, null) }
                if (episodes.isEmpty()) item(key = "eploading") {
                    Text(stringResource(R.string.vod_loading_episodes), color = Color(0xFFBDBDBD), modifier = Modifier.padding(16.dp))
                }
                if (shownSeason != null) {
                    if (!wideHero) item(key = "seasons") { JaskSeasonStrip(summaries, shownSeason, s.posterUrl) { chosenSeason = it } }
                    val episodeCell: @Composable (Episode, Modifier, Boolean) -> Unit = { ep, mod, cell ->
                        JaskEpisodeRow(ep, s.name, byKey["ep:${ep.id}"], isNext = next?.first?.id == ep.id, onPlay = { play(ep) }, modifier = mod, cell = cell) {
                            DownloadControl(mediaKey = "ep:${ep.id}", buildDownload = { episodeDownload(s, ep) }, compact = true, small = true)
                        }
                    }
                    if (wideHero) {
                        // Wide screens: seasons in a side column, the chosen season's episodes two per row beside it.
                        item(key = "episodes-wide") {
                            Row(Modifier.fillMaxWidth().padding(start = jaskSide(), end = jaskSide(), top = 12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                if (summaries.size > 1) JaskSeasonList(summaries, shownSeason, s.posterUrl, Modifier.width(240.dp)) { chosenSeason = it }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (summaries.size == 1) Text("${summaries.first().episodes} episodes", color = Color.White,
                                        style = MaterialTheme.typography.titleSmall)
                                    seasons[shownSeason].orEmpty().chunked(2).forEach { pair ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            pair.forEach { key(it.id) { episodeCell(it, Modifier.weight(1f), true) } }
                                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    } else items(seasons[shownSeason].orEmpty(), key = { it.id }) { ep -> episodeCell(ep, Modifier, false) }
                }
                if (wideHero && moreLike.isNotEmpty()) item(key = "more") {
                    JaskShelf(stringResource(R.string.vod_more_like_this), moreLike, { it.id }) { v ->
                        JaskPosterCard(v.displayTitle, v.posterUrl, PosterFacts(rating = v.rating)) { onOpenSeries(v) }
                    }
                }
            }
        } }) {
            app.opentv.ui.JaskBarScrim(Modifier.align(Alignment.BottomCenter), top = false)
            if (sourceSheet) SourceSheet(pool.map { v -> SourceOption(providerName(v), v.name) }, pool.indexOfFirst { it.id == s.id }.coerceAtLeast(0), { index ->
                val version = pool[index]
                downloadScope.launch { series = viewModel.withCanonicalShow(version) }
                viewModel.loadEpisodes(version)
            }) { sourceSheet = false }
        }
    }
    if (showCollectionPicker) CollectionPickerDialog(
        kind = "show", sourceId = s.sourceId, itemKey = s.seriesId,
        onDismiss = { showCollectionPicker = false },
    )
}

// ---- Shared detail pieces ----------------------------------------------------------------------

/**
 * The hero header, Plex-style: the poster always shown as an elevated card on the left, with the
 * title, meta line and action buttons beside it, over a backdrop background that falls back to the
 * poster and then to a plain gradient.
 *
 * Built so the hero is **never blank**. Two independent failure modes are covered: a provider that
 * ships a non-null but dead `backdropUrl` (real on the user's box — a good poster, a blank blue
 * hero) now falls the *background* through backdrop → poster → gradient via Coil's `onError`; and
 * the poster *card* falls back to a titled gradient tile if its own URL is missing or dead. So even
 * a title with no backdrop at all and a broken poster still shows its name over the app gradient
 * beside an intentional-looking placeholder card, never an empty rectangle.
 */
@Composable
private fun DetailBackdrop(
    title: String,
    backdropUrl: String?,
    posterUrl: String?,
    meta: String,
    onBack: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit,
) {
    if (LocalLayoutClass.current == LayoutClass.PHONE) {
        PhoneDetailHeader(title, backdropUrl, posterUrl, meta, onBack, actions)
        return
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(360.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // Background: try the backdrop, fall back to the poster on load error, then to the gradient
        // alone. Coil silently renders nothing on a failed load, so we drive the fallback from the
        // error callback rather than trusting a non-null URL to actually resolve.
        val bgCandidates = remember(backdropUrl, posterUrl) { listOfNotNull(backdropUrl, posterUrl).distinct() }
        var bgIndex by remember(bgCandidates) { mutableStateOf(0) }
        bgCandidates.getOrNull(bgIndex)?.let { model ->
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                onError = { if (bgIndex < bgCandidates.lastIndex) bgIndex++ },
            )
        }
        // Scrim: keeps text readable over any art, and — with no art at all — is itself the tasteful
        // gradient the hero falls back to.
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.20f),
                    0.5f to Color.Black.copy(alpha = 0.45f),
                    1f to Color.Black.copy(alpha = 0.92f),
                ),
            ),
        )
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            HeroPosterCard(posterUrl = posterUrl, title = title)
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(meta, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f))
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

/**
 * The poster shown as an elevated, rounded card in the hero — the piece that guarantees the header
 * always has real art. When the poster URL is missing or fails to load, it falls back to a gradient
 * tile carrying the title's initial, so the card still reads as intentional rather than broken. Not
 * focusable by design: it is decoration, and the Watch/Resume and favourite buttons remain the
 * hero's d-pad targets.
 */
@Composable
private fun HeroPosterCard(posterUrl: String?, title: String) {
    var posterFailed by remember(posterUrl) { mutableStateOf(false) }
    Box(
        Modifier
            .width(128.dp)
            .height(192.dp)
            .shadow(12.dp, RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (posterUrl != null && !posterFailed) {
            AsyncImage(
                model = posterUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                onError = { posterFailed = true },
            )
        } else {
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                        1f to MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title.trim().take(1).uppercase(),
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White,
                )
            }
        }
    }
}








/**
 * The phone take on [DetailBackdrop]: art with the title over it, then the actions underneath in
 * rows that wrap — side by side with a poster they ran off a portrait screen (Download included).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PhoneDetailHeader(
    title: String,
    backdropUrl: String?,
    posterUrl: String?,
    meta: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = backdropUrl ?: posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.15f), 1f to Color.Black.copy(alpha = 0.9f)),
                ),
            )
            // Phones have no remote Back key in reach — give the page its own way out.
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(8.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
            }
            Column(
                Modifier.align(Alignment.BottomStart).statusBarsPadding().padding(16.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(meta, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
                }
            }
        }
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = actions,
        )
    }
}


/** The Downloads-tab entry for one episode of [series]. */
private fun episodeDownload(series: Series, ep: Episode) = Download(
    mediaKey = "ep:${ep.id}", kind = KIND_EPISODE, title = episodeDisplayTitle(ep.title, series.name), posterUrl = ep.stillUrl ?: series.posterUrl,
    tag = series.sourceTag, seriesTitle = series.displayTitle, season = ep.season, episode = ep.episodeNumber,
    sourceUrl = ep.streamUrl, systemId = 0, createdMillis = 0,
)

@Composable
private fun LoadingDetail() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.vod_detail_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Meta lines --------------------------------------------------------------------------------


/** "2021  ·  ★ 8.1" — series carry no runtime; genre is its own chip row. */
private fun seriesMeta(s: Series): String = listOfNotNull(
    s.sourceTag,
    s.year?.toString(),
    s.rating?.takeIf { it > 0.0 }?.let { "★ ${formatRating(it)}" },
).joinToString("  ·  ")

/** Seconds to a compact "1h 52m" / "45m". */
private fun formatRuntime(seconds: Int): String {
    val totalMinutes = seconds / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

internal data class SourceOption(val provider: String, val detail: String)


/** Bottom sheet listing every provider version; picking one closes it. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SourceSheet(options: List<SourceOption>, selected: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    app.opentv.ui.JaskSheet(onDismiss) {
        Text("Choose a source", style = MaterialTheme.typography.titleMedium, color = Color.White,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        LazyColumn {
            items(options.size) { index ->
                val option = options[index]
                Row(Modifier.fillMaxWidth().clickable { onDismiss(); onSelect(index) }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(option.provider, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                        if (option.detail.isNotBlank()) Text(option.detail, style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFC8C8C8), maxLines = 2)
                    }
                    if (index == selected) Text("✓", style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
            }
        }
    }
}
