/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.material.icons.filled.Layers
import androidx.compose.ui.text.font.FontWeight
import app.opentv.ui.jaskFocusLift
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.flow.first
import app.opentv.ui.jaskFocusRing
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
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.scrollBy
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
    /** Another title on your sources (collection, related), and a person by TMDB id. */
    onOpenMovieId: (Long) -> Unit = {},
    onOpenSeriesId: (Long) -> Unit = {},
    onOpenPersonId: (Int, String) -> Unit = { _, name -> onOpenPerson(name) },
) = JaskTheme {
    val nav = remember { TitleNav(onOpenMovieId, onOpenSeriesId, onOpenPersonId) }
    val providers by viewModel.sources.collectAsState()
    // Coming Back to a page shows what it last showed at once (DetailMemory) while it loads afresh underneath.
    val kept = remember(movieId) { DetailMemory.movies[movieId] }
    var versionNames by remember(movieId) { mutableStateOf<Map<Long, String>>(kept?.versionNames ?: emptyMap()) }
    var versions by remember(movieId) { mutableStateOf<List<Movie>>(kept?.versions ?: emptyList()) }
    var movie by remember(movieId) { mutableStateOf<Movie?>(kept?.movie) }
    var moreLike by remember(movieId) { mutableStateOf<List<Movie>>(kept?.moreLike ?: emptyList()) }
    var resumeExists by remember(movieId) { mutableStateOf(kept?.resumeExists ?: false) }
    val playFocus = remember { FocusRequester() }
    val hasFinders by viewModel.hasFinders.collectAsState()
    // Torrent copies, searched once the page opens (through a core on this network; none away from home).
    val torrentRoute = rememberTorrentRoute()
    var torrents by remember(movieId) { mutableStateOf<List<app.opentv.torrent.Release>?>(null) }
    var sourcesMode by remember(movieId) { mutableStateOf<SourceMode?>(null) }
    val scope = rememberCoroutineScope()
    var showCollectionPicker by remember(movieId) { mutableStateOf(false) }
    // TV: the cast member whose page was opened from here, so Back lands on them (kept across that trip: saveable).
    var castBack by androidx.compose.runtime.saveable.rememberSaveable(movieId) { mutableStateOf<String?>(null) }
    val wideLayout = LocalLayoutClass.current.isWide

    LaunchedEffect(movieId) {
        versions = viewModel.canonicalVersions(movieId)
        versionNames = versions.associate { it.id to viewModel.rawMovieName(it.id) }
        // Open on the version you were watching; otherwise an English one when the one asked for is another language.
        val asked = viewModel.movieDetail(movieId)
        val watching = versions.firstOrNull { viewModel.resumePosition("movie:${it.id}")?.let { p -> !p.isFinished } == true }
        val loaded = when {
            asked == null -> null
            watching != null -> viewModel.movieDetail(watching.id) ?: asked
            englishFirst(versionNames[asked.id].orEmpty()) < 2 -> asked
            else -> versions.minByOrNull { englishFirst(versionNames[it.id].orEmpty()) }
                ?.takeIf { englishFirst(versionNames[it.id].orEmpty()) < 2 }?.let { viewModel.movieDetail(it.id) } ?: asked
        }
        movie = loaded
        if (loaded != null) {
            resumeExists = viewModel.resumePosition("movie:${loaded.id}")?.let { !it.isFinished } == true
            // Wide jaskTV pages show "More like this" for every movie; the phone page doesn't show it.
            moreLike = if (versions.isEmpty() || wideLayout) viewModel.moreLikeThis(loaded)
                .filter { it.displayTitle != loaded.displayTitle }.distinctBy { it.displayTitle to it.year } else emptyList()
            DetailMemory.movies[movieId] = DetailMemory.MovieKept(loaded, versions, versionNames, moreLike, resumeExists)
        }
    }

    val m = movie ?: run { LoadingDetail(); return@JaskTheme }

    // Land focus on the primary action once the page is up, so a d-pad has somewhere to be.
    // TV only: on touch, taking focus scrolled the page back to its top (coming Back from a title further down).
    if (LocalLayoutClass.current == app.opentv.ui.LayoutClass.TV) LaunchedEffect(m.id) {
        delay(60)
        runCatching { playFocus.requestFocus() }
    }

    if (LocalLayoutClass.current.usesJaskShell) {
        // The film's name as the page shows it: no year or region tags (the facts line has the year), the same on
        // the page, in the player and on the download, whether the row is the catalogue's copy or the provider's own.
        val title = cleanShowTitle(m.name)
        // Played with that title (onPlay would title the player with the provider's name, year included).
        val playFilm = { v: Movie -> onPlayUrl("movie:${v.id}", v.streamUrl, title) }
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
        // TMDB details (through core when there is one): logo, age rating, cast, scores, collection, related.
        app.opentv.ui.JaskAmbientFrom(m.backdropUrl ?: m.posterUrl)
        val details = rememberTitleDetails(movie = true, tmdbId = m.tmdbId?.trim()?.toIntOrNull())
        val scores = rememberScores(details?.imdbId)
        val related = remember(details) { details?.let { it.collection + it.recommendations }.orEmpty() }
        val local = rememberOnSources(related, viewModel)
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
        // Back from a person: the page's details load again, so the saved scroll was clamped to the top before the cast
        // row existed. Bring the cast row back into view; it then gives focus back to that person.
        // TV, a fresh open: Play taking focus scrolled the page a little (the title went off the top); the hero fits, so
        // show the page from its top. Not when coming Back to a row (castBack set), which scrolls to that row instead.
        if (app.opentv.ui.LocalLayoutClass.current == app.opentv.ui.LayoutClass.TV) LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(350)
            if (castBack == null && list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset > 0) list.scrollToItem(0)
        }
        if (app.opentv.ui.LocalLayoutClass.current == app.opentv.ui.LayoutClass.TV) LaunchedEffect(castBack != null, details != null) {
            if (castBack == null || details == null) return@LaunchedEffect
            // castBack is "<row>|<card>": the row (cast, related, collection, more) to bring back into view.
            val row = castBack?.substringBefore('|')
            for (i in 0 until 40) {
                if (list.layoutInfo.visibleItemsInfo.any { it.key == row }) break
                if (!list.canScrollForward) break
                list.scrollBy(300f); kotlinx.coroutines.delay(16)
            }
            // Then forget it: a row scrolled away and back later mustn't pull focus again.
            kotlinx.coroutines.delay(3000); castBack = null
        }
        var heroHeight by remember { mutableStateOf(0) }
        val wideHero = app.opentv.ui.LocalLayoutClass.current != app.opentv.ui.LayoutClass.PHONE
        app.opentv.ui.JaskSurfaceHost(Modifier.fillMaxSize(), background = { Box(Modifier.fillMaxSize().jaskPageBackground(tint, list, heroHeight)) {
            LazyColumn(Modifier.fillMaxSize().jaskWheelScroll(list), state = list, contentPadding = PaddingValues(bottom = 32.dp +
                androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
                item(key = "hero") {
                    JaskHero(title, m.backdropUrl, m.posterUrl, HeroFacts(rating = m.rating, quality = quality(m).takeIf { it.length <= 6 },
                        text = listOfNotNull(m.year?.toString()) + detailFacts(details, m.durationSeconds?.takeIf { it > 0 }?.let { (it + 30) / 60 }, movie = true),
                        scores = scoreBadges(scores)),
                        tint, onBack, Modifier.onSizeChanged { heroHeight = it.height }, blurb = (m.plot ?: details?.overview).takeIf { wideHero },
                        logo = details?.logo, tagline = details?.tagline.takeIf { wideHero },
                        phoneActions = {
                            val playLabel = stringResource(if (resumeExists) R.string.vod_resume else R.string.vod_watch_now)
                            Row(Modifier.fillMaxWidth()) {
                                JaskPlayPill(minutesLeft?.takeIf { resumeExists }?.let { "$playLabel · " + if (it >= 60) "${it / 60}h ${it % 60}m left" else "${it}m left" } ?: playLabel, progress) { playFilm(m) }
                                Spacer(Modifier.width(8.dp))
                                // Counts your playlists' versions, as the sheet's "Show all N versions" does (torrents arrive later and aren't counted).
                                JaskSourceButton("Sources", pool.size) { sourcesMode = SourceMode.WATCH }
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                JaskActionTile(if (m.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline, if (m.favourite) "On watchlist" else "Watchlist", active = m.favourite) {
                                    viewModel.toggleMovieFavourite(m); movie = m.copy(favourite = !m.favourite)
                                }
                                JaskActionSlot("Download") {
                                    JaskGlassDownload {
                                        DownloadControl(mediaKey = "movie:${m.id}", compact = true, buildDownload = {
                                            Download(mediaKey = "movie:${m.id}", kind = KIND_MOVIE, title = title,
                                                posterUrl = m.posterUrl, tag = m.sourceTag, sourceUrl = m.streamUrl, systemId = 0, createdMillis = 0)
                                        }, onStart = { sourcesMode = SourceMode.DOWNLOAD })
                                    }
                                }
                                app.opentv.ui.JaskPlayOn("movie:${m.id}") { open -> JaskActionTile(Icons.Filled.Cast, "Play on", onClick = open) }
                            }
                        }) {
                        val playLabel = stringResource(if (resumeExists) R.string.vod_resume else R.string.vod_watch_now)
                        JaskPlayPill(minutesLeft?.takeIf { resumeExists }?.let { "$playLabel · " + if (it >= 60) "${it / 60}h ${it % 60}m left" else "${it}m left" } ?: playLabel, progress) { playFilm(m) }
                        JaskSourceButton("Sources", pool.size) { sourcesMode = SourceMode.WATCH }
                        app.opentv.ui.JaskGlassCircle(if (m.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            stringResource(R.string.jask_watchlist), active = m.favourite) {
                            viewModel.toggleMovieFavourite(m); movie = m.copy(favourite = !m.favourite)
                        }
                        // Play on another of your devices, from where you left off here.
                        app.opentv.ui.JaskPlayOn("movie:${m.id}") { open -> app.opentv.ui.JaskGlassCircle(Icons.Filled.Cast, "Play on another device", onClick = open) }
                        JaskGlassDownload {
                            DownloadControl(mediaKey = "movie:${m.id}", compact = true, buildDownload = {
                                Download(mediaKey = "movie:${m.id}", kind = KIND_MOVIE, title = title,
                                    posterUrl = m.posterUrl, tag = m.sourceTag, sourceUrl = m.streamUrl, systemId = 0, createdMillis = 0)
                            }, onStart = { sourcesMode = SourceMode.DOWNLOAD })
                        }
                    }
                }
                if (!wideHero) item(key = "synopsis") { JaskSynopsis(m.plot ?: details?.overview) }
                // Without TMDB details (no key, no match): the provider's credits as text, as before.
                if (details == null || details.cast.isEmpty()) item(key = "credits") {
                    if (wideHero) JaskMovieBody(emptyList(), 0, {}, genre = m.genre, cast = m.cast ?: credits?.first, director = m.director ?: credits?.second, onOpenPerson = onOpenPerson) {}
                    else JaskCredits(m.genre, m.cast ?: credits?.first, m.director ?: credits?.second)
                }
                if (details != null) {
                    item(key = "cast") { JaskCastRow(details.cast, details.crew, castBack?.takeIf { it.startsWith("cast|") }?.substringAfter('|'), { castBack = it?.let { k -> "cast|$k" } }) { p -> onOpenPersonId(p.id, p.name) } }
                    if (details.collection.size > 1) item(key = "collection") {
                        JaskTitleShelf(details.collectionName ?: "The collection", details.collection, local, nav, currentKey = "m:${details.tmdbId}",
                            returnTo = castBack?.takeIf { it.startsWith("collection|") }?.substringAfter('|'), onReturnTo = { castBack = it?.let { k -> "collection|$k" } })
                    }
                    if (details.recommendations.any { it.key in local }) item(key = "related") {
                        JaskTitleShelf(stringResource(R.string.vod_more_like_this), details.recommendations, local, nav, onlyOnSources = true,
                            returnTo = castBack?.takeIf { it.startsWith("related|") }?.substringAfter('|'), onReturnTo = { castBack = it?.let { k -> "related|$k" } })
                    }
                }
                if (wideHero) {
                    if (details?.recommendations?.any { it.key in local } != true && moreLike.isNotEmpty()) item(key = "more") {
                        JaskShelf(stringResource(R.string.vod_more_like_this), moreLike, { it.id }, returnTo = castBack?.takeIf { it.startsWith("more|") }?.substringAfter('|')) { v ->
                            JaskPosterCard(v.displayTitle, v.posterUrl, PosterFacts(rating = v.rating)) { castBack = "more|${v.id}"; onOpenMovie(v) }
                        }
                    }
                }
                if (details?.review != null) item(key = "review") { JaskReviewCard(details.review) }
            }
        } }) {
            app.opentv.ui.JaskBarScrim(Modifier.align(Alignment.BottomCenter), top = false)
            if (sourceSheet) SourceSheet(pool.map { version ->
                val q = quality(version)
                SourceOption(providerName(version), listOf(if (q.isBlank()) qualityUnknown else "$q (inferred)", versionNames[version.id].orEmpty())
                    .filter(String::isNotBlank).joinToString(" · "))
            }, selected, { selectVersion(it) }) { sourceSheet = false }
            // Sources: every copy (your playlists, then Prowlarr's torrents through a core), to watch or to download.
            sourcesMode?.let { mode ->
                SourcesSheet(title, null, mode, null, { sourcesMode = null }) {
                        LaunchedEffect(m.id, torrentRoute) {
                            if (torrentRoute != null && torrents == null) torrents = runCatching {
                                viewModel.findReleases(m.displayTitle, m.year, m.tmdbId?.trim()?.toIntOrNull(), viewModel.imdbIdFor(m))
                            }.getOrDefault(emptyList())
                        }
                        val template = Download(mediaKey = "movie:${m.id}", kind = KIND_MOVIE, title = title,
                            posterUrl = m.posterUrl, tag = null, sourceUrl = "", systemId = 0, createdMillis = 0)
                        val best = remember(torrents) { torrents?.let { bestForDevice(it, episode = false) } }
                        val ordered = pool.withIndex().sortedWith(compareBy({ it.value.id != m.id }, { englishFirst(versionNames[it.value.id].orEmpty()) }))
                        // In the order shown, so the rows at the top get their details first.
                        FetchProviderTech(ordered.map { it.value })
                        SourcesPanel(mode = mode,
                            provider = ordered
                                .map { (i, v) -> val (label, detail) = versionLines(versionNames[v.id].orEmpty(), quality(v), providerName(v))
                                    // The copy's picture, codec and size (from the provider), then whose it is.
                                    SourceRow(label, listOfNotNull(ProviderTech.line(v.id)?.takeIf(String::isNotBlank), detail.takeIf(String::isNotBlank)).joinToString(" · "),
                                    recommended = if (v.id == m.id) providerRecommended(mode) else null,
                                    play = { selectVersion(i); playFilm(v); true },
                                    download = { viewModel.downloadProvider(template.copy(sourceUrl = v.streamUrl, tag = v.sourceTag)) }) },
                            torrentRoute = torrentRoute,
                            torrents = torrents?.map { r -> SourceRow(r.name, torrentDetail(r), recommended = if (r == best && pool.isEmpty()) recommendReason(r) else if (r == best) "Best torrent for this device" else null,
                                play = { viewModel.playableRelease(r)?.let { url -> onPlayUrl("movie:${m.id}", url, title); true } ?: false },
                                download = { viewModel.downloadRelease(r, template) }) },
                            playlist = null)

                }
            }


        }
    }

    if (showCollectionPicker) CollectionPickerDialog(
        kind = "movie", sourceId = m.sourceId, itemKey = m.streamId,
        onDismiss = { showCollectionPicker = false },
    )


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
    onOpenMovieId: (Long) -> Unit = {},
    onOpenSeriesId: (Long) -> Unit = {},
    onOpenPersonId: (Int, String) -> Unit = { _, name -> onOpenPerson(name) },
) {
    val nav = remember { TitleNav(onOpenMovieId, onOpenSeriesId, onOpenPersonId) }
    val kept = remember(seriesId) { DetailMemory.shows[seriesId] }
    var series by remember(seriesId) { mutableStateOf<Series?>(kept?.series) }
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val downloadDao = remember { graph.database.downloads() }
    val downloadScope = rememberCoroutineScope()
    var moreLike by remember(seriesId) { mutableStateOf<List<Series>>(kept?.moreLike ?: emptyList()) }
    val favFocus = remember { FocusRequester() }
    var showCollectionPicker by remember(seriesId) { mutableStateOf(false) }
    var castBack by androidx.compose.runtime.saveable.rememberSaveable(seriesId) { mutableStateOf<String?>(null) }
    var versions by remember(seriesId) { mutableStateOf<List<Series>>(kept?.versions ?: emptyList()) }
    val providers by viewModel.sources.collectAsState()

    LaunchedEffect(seriesId) {
        // Opens on an English version when the one asked for is tagged as another language (as films do).
        val asked = viewModel.seriesDetail(seriesId)
        val all = asked?.let { viewModel.seriesVersions(it) }.orEmpty()
        // The provider's own name carries the language tag ("AR-SUBS - …"); the canonical one is TMDB's clean title.
        val rawName = asked?.let { a -> all.firstOrNull { it.id == a.id }?.name ?: a.name }.orEmpty()
        // Where you're up to wins: the version opened if you've watched it, else any version you've watched; only
        // then English over a version tagged as another language.
        suspend fun watched(v: Series) = runCatching { viewModel.seriesPositions(v).first().isNotEmpty() }.getOrDefault(false)
        val loaded = when {
            asked == null -> null
            watched(asked) -> asked
            else -> all.firstOrNull { it.id != asked.id && watched(it) }?.let { viewModel.withCanonicalShow(it) }
                ?: if (englishFirst(rawName) == 2) all.filter { englishFirst(it.name) < 2 }.minByOrNull { englishFirst(it.name) }
                    ?.let { viewModel.withCanonicalShow(it) } ?: asked else asked
        }
        series = loaded
        if (loaded != null) {
            viewModel.loadEpisodes(loaded)
            versions = all
            moreLike = viewModel.moreLikeThisSeries(loaded)
            DetailMemory.shows[seriesId] = DetailMemory.ShowKept(loaded, all, moreLike)
        }
    }

    val s = series
    val episodesFlow = remember(s?.sourceId, s?.seriesId) { s?.let { viewModel.episodes(it) } ?: viewModel.noEpisodes }
    val episodes by episodesFlow.collectAsState(initial = emptyList())

    if (s == null) { LoadingDetail(); return }

    if (LocalLayoutClass.current == app.opentv.ui.LayoutClass.TV) LaunchedEffect(s.id) {
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
        // "S1 E1 · A Beating Heart": the sheets', the player's and the menu's title for an episode (never the provider's
        // "(2025) (US) - S01E01 - …").
        fun episodeTitle(ep: Episode) = "S${ep.season} E${ep.episodeNumber} · " + cleanEpisodeTitle(episodeDisplayTitle(ep.title, s.name), ep.episodeNumber)
        fun play(ep: app.opentv.data.model.Episode) {
            onPlayEpisode("ep:${ep.id}", ep.streamUrl, episodeTitle(ep))
        }
        var tmdbShow by remember(s.id) { mutableStateOf<app.opentv.catalogue.CanonicalShow?>(null) }
        LaunchedEffect(s.id) { tmdbShow = viewModel.canonicalShow(s) }
        val showTmdb = s.tmdbId?.trim()?.toIntOrNull() ?: tmdbShow?.tmdbId
        app.opentv.ui.JaskAmbientFrom(s.backdropUrl ?: s.posterUrl)
        val details = rememberTitleDetails(movie = false, tmdbId = showTmdb)
        val scores = rememberScores(details?.imdbId)
        val local = rememberOnSources(details?.recommendations.orEmpty(), viewModel)
        val seasonOrder = remember(seasons) { seasons.keys.sortedBy { if (it == 0) Int.MAX_VALUE else it } }
        // Saveable: the season you opened is still open when you come back from playing one of its episodes.
        var chosenSeason by androidx.compose.runtime.saveable.rememberSaveable(s.id) { mutableStateOf<Int?>(null) }
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
        // Sources for one episode (its download icon), and the show's Download (an episode, a season or the whole show),
        // both in the Sources panel: the provider's copy and core's torrents, with Play and Download on each.
        val torrentRoute = rememberTorrentRoute()
        var sourcesFor by remember { mutableStateOf<Episode?>(null) }
        // An episode's menu (hold it): play, watch another copy, download just it.
        var menuFor by remember { mutableStateOf<Episode?>(null) }
        // Downloaded only: the episodes saved (or saving) on this device, for watching away from home.
        val saved by remember { graph.downloadRepository.observe() }.collectAsState(initial = emptyList())
        val savedKeys = remember(saved) { saved.map { it.download.mediaKey }.toSet() }
        val savedEps = remember(episodes, savedKeys) { episodes.filter { "ep:${it.id}" in savedKeys }.sortedWith(compareBy({ it.season }, { it.episodeNumber })) }
        var downloadedOnly by remember(s.id) { mutableStateOf(false) }
        var episodeMode by remember { mutableStateOf(SourceMode.DOWNLOAD) }
        var showDownload by remember { mutableStateOf(false) }
        // A torrent row for [ep]: an episode's own file, or that episode's file inside a season or series pack.
        fun torrentRow(r: app.opentv.torrent.Release, ep: Episode, best: app.opentv.torrent.Release?): SourceRow {
            val pack = packScope(r.name)
            val inPack = if (pack == PackScope.EPISODE) null else episodeInPack(r, ep.season, ep.episodeNumber)
            return SourceRow(r.name, torrentDetail(r, when (pack) { PackScope.SEASON -> "season pack: this episode's file"; PackScope.SHOW -> "series pack: this episode's file"; else -> null }),
                recommended = if (r == best) "Best torrent for this device" else null,
                play = { (inPack ?: viewModel.playableRelease(r))?.let { url -> onPlayEpisode("ep:${ep.id}", url, episodeTitle(ep)); true } ?: false },
                download = if (pack != PackScope.EPISODE && inPack == null) null else ({
                    if (inPack != null) viewModel.downloadUrl(inPack, episodeDownload(s, ep)) else viewModel.downloadRelease(r, episodeDownload(s, ep)) }))
        }
        // Drawn inside the page's surface host (below), so the sheets get its glass, not the plain fallback sheet.
        val showSheets: @Composable () -> Unit = {
        menuFor?.let { ep ->
            val item = saved.firstOrNull { it.download.mediaKey == "ep:${ep.id}" }
            val pos = byKey["ep:${ep.id}"]
            app.opentv.ui.JaskSheet({ menuFor = null }) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text(episodeTitle(ep),
                        color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(title, color = Color(0xFFBDBDBD), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    val resuming = pos != null && !pos.isFinished && pos.positionMillis > 0
                    EpisodeMenuRow(Icons.Filled.PlayArrow, if (resuming) "Resume" else "Play", null) { menuFor = null; play(ep) }
                    EpisodeMenuRow(Icons.Filled.Layers, "Sources", "Your playlists' versions and torrents") {
                        menuFor = null; episodeMode = SourceMode.WATCH; sourcesFor = ep }
                    EpisodeMenuRow(if (item?.state == app.opentv.data.repo.DownloadRepository.State.DONE) Icons.Filled.DownloadDone else Icons.Filled.Download,
                        "Download", when (item?.state) { null -> "Choose where from"
                            app.opentv.data.repo.DownloadRepository.State.DONE -> "Already on this device"
                            else -> "Downloading · ${(item.progress * 100).toInt()}%" }) {
                        menuFor = null; episodeMode = SourceMode.DOWNLOAD; sourcesFor = ep }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        sourcesFor?.let { ep ->
            var found by remember(ep.id) { mutableStateOf<List<app.opentv.torrent.Release>?>(null) }
            LaunchedEffect(ep.id) { if (torrentRoute != null) found = runCatching { viewModel.findEpisodeReleases(s, ep.season, ep.episodeNumber) }.getOrDefault(emptyList()) }
            val best = remember(found) { found?.filter { packScope(it.name) == PackScope.EPISODE }?.let { bestForDevice(it, episode = true) } }
            SourcesSheet(episodeTitle(ep), title, episodeMode, null, { sourcesFor = null }) {
                SourcesPanel(mode = episodeMode, provider = listOf(SourceRow(providerName(s), "This episode", recommended = providerRecommended(episodeMode),
                    play = { sourcesFor = null; play(ep); true }, download = { viewModel.downloadProvider(episodeDownload(s, ep)) })),
                    torrentRoute = torrentRoute, torrents = found?.map { torrentRow(it, ep, best) })
            }
        }
        if (showDownload) {
            val ordered = episodes.filter { it.season > 0 }.sortedWith(compareBy({ it.season }, { it.episodeNumber }))
            val seasonList = ordered.map { it.season }.distinct()
            val savedItem = remember(saved) { saved.associateBy { it.download.mediaKey } }
            // Everything downloading happens here: a season (or the whole show), which of its episodes, then where from.
            var pick by remember { mutableStateOf<Int?>(next?.first?.season?.takeIf { it in seasonList } ?: seasonList.firstOrNull()) }
            val seasonEps = pick?.let { n -> ordered.filter { it.season == n } } ?: ordered
            // The season's (or show's) episodes not already on this device; one episode is from its own menu (hold it).
            // Only finished ones count: a queued or running one can be switched to another source by picking it again.
            val targets = seasonEps.filter { savedItem["ep:${it.id}"]?.state != DownloadRepository.State.DONE }
            var found by remember { mutableStateOf<List<app.opentv.torrent.Release>?>(null) }
            val single: Episode? = null
            LaunchedEffect(pick, single?.id) {
                found = null
                if (torrentRoute == null) return@LaunchedEffect
                val n = pick
                val all = runCatching { viewModel.findEpisodeReleases(s, single?.season ?: n ?: 1, single?.episodeNumber ?: 1) }.getOrDefault(emptyList())
                found = when {
                    single != null -> all
                    n == null -> all.filter { packScope(it.name) == PackScope.SHOW }
                    else -> { val tag = Regex("""\bS0?$n\b|SEASON\s*0?$n\b""", RegexOption.IGNORE_CASE)
                        all.filter { packScope(it.name) == PackScope.SEASON && tag.containsMatchIn(it.name) } }
                }
            }
            SourcesSheet(title, null, SourceMode.DOWNLOAD, null, { showDownload = false }, top = {
                androidx.compose.foundation.lazy.LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(seasonList) { n -> app.opentv.ui.settings.JaskChoicePill("Season $n", selected = pick == n) { pick = n } }
                    item { app.opentv.ui.settings.JaskChoicePill("Whole show", selected = pick == null) { pick = null } }
                }
                val have = seasonEps.size - targets.size
                Text((if (pick == null) "Whole show" else "Season $pick") + " · ${targets.size} episodes to download" +
                    (if (have > 0) " ($have already on this device)" else "") + ". For one episode, hold it.",
                    color = Color(0xFFBDBDBD), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            }) {
                // The season's size from the provider, so its row can be weighed against the torrents.
                var providerSize by remember(targets) { mutableStateOf<String?>(null) }
                if (single == null) LaunchedEffect(targets) { providerSize = ProviderTech.seasonSize(context, targets) }
                val best = remember(found, targets.size) { found?.let { bestForDevice(it, episode = true, episodes = if (single != null) 1 else targets.size) } }
                SourcesPanel(mode = SourceMode.DOWNLOAD,
                    provider = if (targets.isEmpty()) emptyList() else listOf(SourceRow(providerName(s),
                        if (single != null) "This episode" else "${targets.size} episodes, one after another" + (providerSize?.let { " · $it" } ?: ""),
                        recommended = providerRecommended(SourceMode.DOWNLOAD),
                        download = { viewModel.downloadAll(targets.map { episodeDownload(s, it) }) })),
                    torrentRoute = torrentRoute,
                    providerNone = if (targets.isEmpty()) "They're all on this device already." else "None of your playlists has these.",
                    torrents = if (targets.isEmpty()) emptyList() else found?.map { r ->
                        if (single != null) torrentRow(r, single, best)
                        else SourceRow(r.name, torrentDetail(r, "${targets.size} episodes from this pack"), recommended = if (r == best) "Best pack for this device" else null,
                            download = if (episodeInPack(r, 1, 1) == null) null else ({
                                viewModel.downloadAll(targets.mapNotNull { ep -> episodeInPack(r, ep.season, ep.episodeNumber)?.let { episodeDownload(s, ep).copy(sourceUrl = it) } })
                            }))
                    })
            }
        }
        // Sources (beside Play): the playlist's versions of the show, and torrents of the episode Play would start.
        if (sourceSheet) {
            val target = next?.first
            var found by remember(target?.id) { mutableStateOf<List<app.opentv.torrent.Release>?>(null) }
            LaunchedEffect(target?.id) {
                if (torrentRoute != null && target != null) found = runCatching { viewModel.findEpisodeReleases(s, target.season, target.episodeNumber) }.getOrDefault(emptyList())
            }
            val best = remember(found) { found?.filter { packScope(it.name) == PackScope.EPISODE }?.let { bestForDevice(it, episode = true) } }
            // Your playlists first, then the torrents (of the episode Play starts), under the same headers as a film's.
            SourcesSheet(title, null, SourceMode.WATCH, null, { sourceSheet = false }) {
                SourcesPanel(mode = SourceMode.WATCH, torrentsOf = target?.let { "S${it.season} E${it.episodeNumber}, the episode Play starts" },
                    provider = pool.sortedWith(compareBy({ it.id != s.id }, { englishFirst(it.name) })).map { v ->
                        val (label, detail) = versionLines(v.name, "", providerName(v))
                        SourceRow(label, detail, recommended = if (v.id == s.id) "Showing these episodes" else null,
                        play = { downloadScope.launch { series = viewModel.withCanonicalShow(v) }; viewModel.loadEpisodes(v); sourceSheet = false; true }) },
                    torrentRoute = torrentRoute,
                    torrents = if (target == null) emptyList() else found?.map { torrentRow(it, target, best) })
            }
        }
        }
        // TMDB's stills and synopses where the provider gave none.
        val seasonMeta by androidx.compose.runtime.produceState(emptyMap<Int, app.opentv.catalogue.EpisodeMeta>(), showTmdb, shownSeason) {
            val n = shownSeason
            value = if (showTmdb == null || n == null) emptyMap() else graph.titleMeta.season(showTmdb, n).associateBy { it.number }
        }
        val list = androidx.compose.foundation.lazy.rememberLazyListState()
        // Back from a person: the page's details load again, so the saved scroll was clamped to the top before the cast
        // row existed. Bring the cast row back into view; it then gives focus back to that person.
        // TV, a fresh open: Play taking focus scrolled the page a little (the title went off the top); the hero fits, so
        // show the page from its top. Not when coming Back to a row (castBack set), which scrolls to that row instead.
        if (app.opentv.ui.LocalLayoutClass.current == app.opentv.ui.LayoutClass.TV) LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(350)
            if (castBack == null && list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset > 0) list.scrollToItem(0)
        }
        if (app.opentv.ui.LocalLayoutClass.current == app.opentv.ui.LayoutClass.TV) LaunchedEffect(castBack != null, details != null) {
            if (castBack == null || details == null) return@LaunchedEffect
            // castBack is "<row>|<card>": the row (cast, related, collection, more) to bring back into view.
            val row = castBack?.substringBefore('|')
            for (i in 0 until 40) {
                if (list.layoutInfo.visibleItemsInfo.any { it.key == row }) break
                if (!list.canScrollForward) break
                list.scrollBy(300f); kotlinx.coroutines.delay(16)
            }
            // Then forget it: a row scrolled away and back later mustn't pull focus again.
            kotlinx.coroutines.delay(3000); castBack = null
        }
        var heroHeight by remember { mutableStateOf(0) }
        val wideHero = app.opentv.ui.LocalLayoutClass.current != app.opentv.ui.LayoutClass.PHONE
        app.opentv.ui.JaskSurfaceHost(Modifier.fillMaxSize(), background = { Box(Modifier.fillMaxSize().jaskPageBackground(tint, list, heroHeight)) {
            LazyColumn(Modifier.fillMaxSize().jaskWheelScroll(list), state = list, contentPadding = PaddingValues(bottom = 32.dp +
                androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
                item(key = "hero") {
                    JaskHero(title, s.backdropUrl, s.posterUrl, HeroFacts(rating = s.rating, networkLogo = tmdbShow?.networkLogo, network = tmdbShow?.network,
                        text = listOfNotNull(s.year?.toString(), seasons.keys.count { it > 0 }.takeIf { it > 0 }?.let { if (it == 1) "1 season" else "$it seasons" },
                            tmdbShow?.statusLabel.takeIf { details == null }) + detailFacts(details, null, movie = false, networkShown = tmdbShow?.networkLogo != null || tmdbShow?.network != null),
                        scores = scoreBadges(scores)),
                        tint, onBack, Modifier.onSizeChanged { heroHeight = it.height }, blurb = (s.plot ?: details?.overview).takeIf { wideHero },
                        logo = details?.logo, tagline = details?.tagline.takeIf { wideHero },
                        phoneActions = {
                            Row(Modifier.fillMaxWidth()) {
                                if (next == null) JaskPlayPill(stringResource(R.string.vod_loading_episodes)) {}
                                else {
                                    val (ep, pos) = next
                                    val resuming = pos != null && !pos.isFinished && pos.positionMillis > 0
                                    JaskPlayPill((if (resuming) "Resume" else "Play") + " S${ep.season} · E${ep.episodeNumber}",
                                        pos?.takeIf { resuming && it.durationMillis > 0 }?.let { it.positionMillis.toFloat() / it.durationMillis }) { play(ep) }
                                }
                                Spacer(Modifier.width(8.dp))
                                JaskSourceButton("Sources", pool.size) { sourceSheet = true }
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                JaskActionTile(if (s.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline, if (s.favourite) "On watchlist" else "Watchlist", active = s.favourite) {
                                    viewModel.toggleSeriesFavourite(s); series = s.copy(favourite = !s.favourite)
                                }
                                JaskActionTile(androidx.compose.material.icons.Icons.Filled.Download, "Download") { showDownload = true }
                                next?.first?.let { ep -> app.opentv.ui.JaskPlayOn("ep:${ep.id}") { open -> JaskActionTile(Icons.Filled.Cast, "Play on", onClick = open) } }
                            }
                        }) {
                        if (next == null) JaskPlayPill(stringResource(R.string.vod_loading_episodes)) {}
                        else {
                            val (ep, pos) = next
                            val resuming = pos != null && !pos.isFinished && pos.positionMillis > 0
                            JaskPlayPill((if (resuming) "Resume" else "Play") + " S${ep.season} · E${ep.episodeNumber}",
                                pos?.takeIf { resuming && it.durationMillis > 0 }?.let { it.positionMillis.toFloat() / it.durationMillis }) { play(ep) }
                        }
                        JaskSourceButton("Sources", pool.size) { sourceSheet = true }
                        app.opentv.ui.JaskGlassCircle(if (s.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            stringResource(R.string.jask_watchlist), active = s.favourite) {
                            viewModel.toggleSeriesFavourite(s); series = s.copy(favourite = !s.favourite)
                        }
                        next?.first?.let { ep -> app.opentv.ui.JaskPlayOn("ep:${ep.id}") { open -> app.opentv.ui.JaskGlassCircle(Icons.Filled.Cast, "Play on another device", onClick = open) } }
                        app.opentv.ui.JaskGlassCircle(androidx.compose.material.icons.Icons.Filled.Download, "Download an episode, a season or the show") { showDownload = true }
                    }
                }
                if (!wideHero) item(key = "synopsis") { JaskSynopsis(s.plot ?: details?.overview) }
                if (details == null || details.cast.isEmpty()) item(key = "credits") { JaskCredits(s.genre, s.cast, null) }
                if (episodes.isEmpty()) item(key = "eploading") {
                    Text(stringResource(R.string.vod_loading_episodes), color = Color(0xFFBDBDBD), modifier = Modifier.padding(16.dp))
                }
                if (savedEps.isNotEmpty()) item(key = "downloaded-only") {
                    Row(Modifier.padding(start = jaskSide(), end = jaskSide(), top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        app.opentv.ui.settings.JaskPill(if (downloadedOnly) "Downloaded only · ${savedEps.size}  ✕" else "Downloaded only · ${savedEps.size}",
                            primary = downloadedOnly) { downloadedOnly = !downloadedOnly }
                    }
                }
                if (downloadedOnly && !wideHero) items(savedEps, key = { "saved" + it.id }) { ep ->
                    JaskEpisodeRow(ep, s.name, byKey["ep:${ep.id}"], isNext = next?.first?.id == ep.id, onPlay = { play(ep) }, withSeason = true, onLongPress = { menuFor = ep }) {
                        EpisodeSavedMark(saved.firstOrNull { it.download.mediaKey == "ep:${ep.id}" })
                    }
                }
                if (shownSeason != null && !(downloadedOnly && !wideHero)) {
                    // Phone: season posters, closed; tapping one opens its episodes (Play already knows where you're up to).
                    if (!wideHero) item(key = "seasons") {
                        Column(Modifier.padding(top = 10.dp)) {
                            Box(Modifier.padding(start = jaskSide(), bottom = 6.dp)) { JaskSectionTitle(if (summaries.size == 1) "1 season" else "${summaries.size} seasons") }
                            JaskSeasonStrip(summaries, chosenSeason ?: -1, s.posterUrl) { chosenSeason = if (chosenSeason == it) null else it }
                        }
                    }
                    val episodeCell: @Composable (Episode, Modifier, Boolean) -> Unit = { raw, mod, cell ->
                        val ep = seasonMeta[raw.episodeNumber]?.let { t -> raw.copy(stillUrl = raw.stillUrl ?: t.still,
                            plot = raw.plot?.takeIf(String::isNotBlank) ?: t.overview,
                            durationSeconds = raw.durationSeconds?.takeIf { it > 0 } ?: t.runtimeMinutes?.let { it * 60 }) } ?: raw
                        JaskEpisodeRow(ep, s.name, byKey["ep:${ep.id}"], isNext = next?.first?.id == ep.id, onPlay = { play(ep) }, modifier = mod, cell = cell,
                            onLongPress = { menuFor = ep }) {
                            // Downloading is all in the Download sheet; the row only says whether it's on this device.
                            EpisodeSavedMark(saved.firstOrNull { it.download.mediaKey == "ep:${ep.id}" })
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
                                    seasons[shownSeason].orEmpty().filter { !downloadedOnly || "ep:${it.id}" in savedKeys }.chunked(2).forEach { pair ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            pair.forEach { key(it.id) { episodeCell(it, Modifier.weight(1f), true) } }
                                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    } else if (chosenSeason != null) items(seasons[shownSeason].orEmpty(), key = { it.id }) { ep -> episodeCell(ep, Modifier, false) }
                }
                if (details != null) {
                    item(key = "cast") { JaskCastRow(details.cast, details.crew, castBack?.takeIf { it.startsWith("cast|") }?.substringAfter('|'), { castBack = it?.let { k -> "cast|$k" } }) { p -> onOpenPersonId(p.id, p.name) } }
                    if (details.recommendations.any { it.key in local }) item(key = "related") {
                        JaskTitleShelf(stringResource(R.string.vod_more_like_this), details.recommendations, local, nav, onlyOnSources = true,
                            returnTo = castBack?.takeIf { it.startsWith("related|") }?.substringAfter('|'), onReturnTo = { castBack = it?.let { k -> "related|$k" } })
                    }
                }
                if (wideHero && details?.recommendations?.any { it.key in local } != true && moreLike.isNotEmpty()) item(key = "more") {
                    JaskShelf(stringResource(R.string.vod_more_like_this), moreLike, { it.id }, returnTo = castBack?.takeIf { it.startsWith("more|") }?.substringAfter('|')) { v ->
                        JaskPosterCard(v.displayTitle, v.posterUrl, PosterFacts(rating = v.rating)) { castBack = "more|${v.id}"; onOpenSeries(v) }
                    }
                }
                if (details?.review != null) item(key = "review") { JaskReviewCard(details.review) }
            }
        } }) {
            app.opentv.ui.JaskBarScrim(Modifier.align(Alignment.BottomCenter), top = false)
            showSheets()
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
                modifier = Modifier.jaskFocusRing().then(Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(8.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))),
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
    mediaKey = "ep:${ep.id}", kind = KIND_EPISODE, title = cleanEpisodeTitle(episodeDisplayTitle(ep.title, series.name), ep.episodeNumber), posterUrl = ep.stillUrl ?: series.posterUrl,
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


/** "2021 · TMDB 8.1" — series carry no runtime; genre is its own chip row. The score is named by its source. */
private fun seriesMeta(s: Series): String = listOfNotNull(
    s.sourceTag,
    s.year?.toString(),
    s.rating?.takeIf { it > 0.0 }?.let { "${if (!s.tmdbId.isNullOrBlank()) TmdbRating else ProviderRating} ${formatRating(it)}" },
).joinToString(" · ")

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

/** An episode row's download status: ✓ on this device, or how far it's got. Not a button (downloading is in the sheet). */
@Composable
private fun EpisodeSavedMark(item: app.opentv.data.repo.DownloadRepository.Item?) {
    item ?: return
    Box(Modifier.padding(start = 6.dp).size(28.dp), contentAlignment = Alignment.Center) {
        when (item.state) {
            app.opentv.data.repo.DownloadRepository.State.DONE -> Icon(Icons.Filled.DownloadDone, "On this device",
                tint = Color.White, modifier = Modifier.size(20.dp))
            app.opentv.data.repo.DownloadRepository.State.FAILED -> Icon(Icons.Outlined.ErrorOutline, "Download failed",
                tint = Color(0xFFFFB4A8), modifier = Modifier.size(20.dp))
            else -> androidx.compose.material3.CircularProgressIndicator(progress = { item.progress }, modifier = Modifier.size(20.dp),
                color = Color.White, strokeWidth = 2.dp, trackColor = Color.White.copy(alpha = .25f))
        }
    }
}

@Composable
private fun EpisodeMenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, detail: String?, onClick: () -> Unit) {
    // No lift: the lifted episode row underneath stays the one focus returns to when the sheets close.
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .06f))
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, style = MaterialTheme.typography.titleSmall)
            if (detail != null) Text(detail, color = Color(0xFFA8A8A8), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The last few title pages as they were shown, so Back to one doesn't sit on "Loading…" while it reloads. */
internal object DetailMemory {
    data class MovieKept(val movie: Movie, val versions: List<Movie>, val versionNames: Map<Long, String>, val moreLike: List<Movie>,
        val resumeExists: Boolean)
    data class ShowKept(val series: Series, val versions: List<Series>, val moreLike: List<Series>)
    private fun <K, V> lru(max: Int) = java.util.Collections.synchronizedMap(object : LinkedHashMap<K, V>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
    })
    val movies: MutableMap<Long, MovieKept> = lru(24)
    val shows: MutableMap<Long, ShowKept> = lru(24)
}
