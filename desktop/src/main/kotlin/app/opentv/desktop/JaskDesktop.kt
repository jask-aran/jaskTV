/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadXmlImageVector
import androidx.compose.ui.res.useResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xml.sax.InputSource
import java.util.Locale

private val Canvas = Color(0xFF0B0B0C)
private val Ink = Color(0xFFF4F1ED)
private val Dim = Color(0xFFA9A49F)

enum class Destination(val label: String, val icon: ImageVector) {
    MOVIES("Movies", Icons.Outlined.Movie), SHOWS("Shows", Icons.Outlined.Tv),
    LIVE("Live TV", Icons.Outlined.LiveTv), LIBRARY("Library", Icons.Outlined.CollectionsBookmark),
}

/** The jaskTV desktop app: sidebar, ambient waves background, Movies/Shows homes, search and media pages. */
@Composable
fun JaskDesktopApp(onPlay: (url: String, title: String) -> Unit) {
    var destination by remember { mutableStateOf(Destination.MOVIES) }
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<Title?>(null) }
    var art by remember { mutableStateOf<String?>(null) }
    MaterialTheme(colorScheme = darkColorScheme(background = Canvas, surface = Color(0xFF1B1A19), primary = Ink, onPrimary = Canvas)) {
        Row(Modifier.fillMaxSize().background(Canvas)) {
            Sidebar(destination, query, { query = it }) { destination = it; open = null; query = "" }
            Box(Modifier.weight(1f).fillMaxHeight().wavesBackground(art)) {
                val title = open
                when {
                    title != null -> MediaPage(title, onBack = { open = null }, onArt = { art = it }, onPlay = onPlay)
                    query.isNotBlank() -> SearchResults(query, onOpen = { open = it }, onArt = { art = it })
                    destination == Destination.MOVIES -> HomeScreen("movies", { DesktopCatalogue.movies() }, { open = it }, { art = it })
                    destination == Destination.SHOWS -> HomeScreen("shows", { DesktopCatalogue.shows() }, { open = it }, { art = it })
                    destination == Destination.LIBRARY -> LibraryScreen(onOpen = { open = it }, onArt = { art = it })
                    else -> Placeholder("Live TV comes next on desktop.")
                }
            }
        }
    }
}

// ---- Sidebar -------------------------------------------------------------------------------

private val wordmark: ImageVector? by lazy {
    runCatching { useResource("jask_wordmark.xml") { loadXmlImageVector(InputSource(it), Density(1f)) } }.getOrNull()
}

@Composable
private fun Sidebar(current: Destination, query: String, onQuery: (String) -> Unit, onSelect: (Destination) -> Unit) {
    Column(Modifier.width(232.dp).fillMaxHeight().background(Color(0xFF080808)).padding(horizontal = 16.dp, vertical = 22.dp)) {
        wordmark?.let { Icon(it, "jaskTV", tint = Ink, modifier = Modifier.padding(start = 8.dp).height(22.dp).width(128.dp)) }
            ?: Text("jaskTV", color = Ink, fontWeight = FontWeight.Black, fontSize = 22.sp)
        Spacer(Modifier.height(26.dp))
        Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .08f)).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, null, tint = Dim, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search", color = Dim, fontSize = 14.sp)
                BasicTextField(query, onQuery, singleLine = true, textStyle = LocalTextStyle.current.copy(color = Ink, fontSize = 14.sp),
                    cursorBrush = SolidColor(Ink), modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(20.dp))
        Destination.entries.forEach { d ->
            val selected = d == current
            Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp))
                .background(if (selected) Color.White.copy(alpha = .12f) else Color.Transparent)
                .clickable { onSelect(d) }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(d.icon, null, tint = if (selected) Ink else Dim, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(d.label, color = if (selected) Ink else Dim, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF5B5BD6)), contentAlignment = Alignment.Center) {
                Text("M", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Me", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Library from phone", color = Dim, fontSize = 11.sp)
            }
        }
    }
}

// ---- Movies / Shows home --------------------------------------------------------------------

@Composable
private fun HomeScreen(key: String, load: suspend () -> Home, onOpen: (Title) -> Unit, onArt: (String?) -> Unit) {
    var home by remember(key) { mutableStateOf<Home?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key) { runCatching { load() }.onSuccess { home = it }.onFailure { error = it.message ?: it.toString(); it.printStackTrace() } }
    val h = home
    if (h == null) { if (error != null) Placeholder("Couldn't load: $error") else Loading(); return }
    val grid = rememberLazyGridState()
    LazyVerticalGrid(GridCells.Adaptive(168.dp), state = grid, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 40.dp, end = 40.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            if (h.featured.isNotEmpty()) Hero(h.featured, onOpen, onArt) else { Spacer(Modifier.height(32.dp)); onArt(h.all.firstOrNull()?.backdrop) }
        }
        h.sections.forEach { s ->
            item(span = { GridItemSpan(maxLineSpan) }) { Shelf(s, onOpen) }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(if (key == "shows") "All shows" else "All movies", Modifier.padding(top = 12.dp)) }
        items(h.all, key = { "all-" + it.key }) { PosterCard(it) { onOpen(it) } }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)

/** A titled horizontal row with arrow buttons (mouse users can't swipe). */
@Composable
private fun Shelf(s: Section, onOpen: (Title) -> Unit) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(s.title, Modifier.weight(1f))
            ArrowButton(Icons.Default.ChevronLeft) { scope.launch { list.animateScrollBy(-900f) } }
            Spacer(Modifier.width(8.dp))
            ArrowButton(Icons.Default.ChevronRight) { scope.launch { list.animateScrollBy(900f) } }
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(end = 8.dp)) {
            items(s.items, key = { it.key }) { t ->
                if (s.wide) WideCard(t, Modifier.width(380.dp)) { onOpen(t) }
                else Box(Modifier.width(176.dp)) { PosterCard(t) { onOpen(t) } }
            }
        }
    }
}

@Composable
private fun ArrowButton(icon: ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = .1f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Ink, modifier = Modifier.size(22.dp))
    }
}

/** Full-width featured hero: art on the right fading into the page, text and actions on the left. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Hero(titles: List<Title>, onOpen: (Title) -> Unit, onArt: (String?) -> Unit) {
    val pager = rememberPagerState { titles.size }
    LaunchedEffect(pager.settledPage) {
        onArt(titles[pager.settledPage].backdrop)
        delay(12_000)
        if (titles.size > 1) pager.animateScrollToPage((pager.settledPage + 1) % titles.size, animationSpec = tween(900))
    }
    Box(Modifier.fillMaxWidth().height(540.dp).padding(top = 0.dp)) {
        HorizontalPager(pager, Modifier.fillMaxSize().clip(RoundedCornerShape(bottomStart = 0.dp))) { page ->
            val t = titles[page]
            Box(Modifier.fillMaxSize().clickable { onOpen(t) }) {
                AsyncImage(t.backdrop?.replace("/w1280/", "/original/"), null, contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize().fadeEdges())
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.5f).padding(start = 8.dp, bottom = 46.dp)) {
                    Text(t.title, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Black, lineHeight = 46.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    Text(listOfNotNull(t.rank?.let { "#$it this week" }, t.year?.toString(), t.network, t.status, rating(t.rating)).joinToString("  ·  "),
                        color = Color(0xFFDADADA), fontSize = 15.sp)
                    t.overview?.let { Spacer(Modifier.height(12.dp)); Text(it, color = Color(0xFFCFCFCF), fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 21.sp) }
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton("Watch now", Icons.Default.PlayArrow, primary = true) { onOpen(t) }
                        PillButton("Details", Icons.Outlined.Info, primary = false) { onOpen(t) }
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(titles.size) { i ->
                Box(Modifier.size(if (i == pager.currentPage) 22.dp else 7.dp, 7.dp).clip(RoundedCornerShape(50))
                    .background(if (i == pager.currentPage) Color.White else Color.White.copy(alpha = .35f)))
            }
        }
    }
}

/** Art fades out to the left (behind the text) and at the bottom, into the waves page. */
private fun Modifier.fadeEdges() = graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
    drawContent()
    drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha = .15f), 0.45f to Color.Black, 1f to Color.Black), blendMode = BlendMode.DstIn)
    drawRect(Brush.verticalGradient(0.55f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
}

@Composable
private fun PillButton(label: String, icon: ImageVector, primary: Boolean, onClick: () -> Unit) {
    Row(Modifier.height(46.dp).clip(RoundedCornerShape(50)).background(if (primary) Color.White else Color.White.copy(alpha = .16f))
        .border(1.dp, if (primary) Color.Transparent else Color.White.copy(alpha = .25f), RoundedCornerShape(50))
        .clickable(onClick = onClick).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (primary) Color.Black else Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (primary) Color.Black else Color.White, fontWeight = FontWeight.SemiBold)
    }
}

// ---- Cards ------------------------------------------------------------------------------------

private fun rating(r: Double?) = r?.takeIf { it > 0 }?.let { "★ " + String.format(Locale.getDefault(), "%.1f", it) }

private fun qualityBadge(rank: Int): String? = when { rank >= 500 -> "8K"; rank >= 400 -> "4K"; rank >= 200 -> "HD"; rank >= 100 -> "SD"; else -> null }

/** Poster with the phone's badges: rank circle and quality/network pill overhanging the top corners. */
@Composable
fun PosterCard(t: Title, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val o = 7.dp
    Column(Modifier.fillMaxWidth().hoverable(hover).clickable(hover, null, onClick = onClick).padding(top = o + 1.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).graphicsLayer { val s = if (hovered) 1.04f else 1f; scaleX = s; scaleY = s }) {
            Box(Modifier.matchParentSize().shadow(if (hovered) 18.dp else 6.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(Color(0xFF26231F))
                .then(if (hovered) Modifier.border(2.dp, Color.White.copy(alpha = .8f), RoundedCornerShape(10.dp)) else Modifier)) {
                AsyncImage(t.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Row(Modifier.align(Alignment.BottomStart).padding(7.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    t.rating?.takeIf { it > 0 }?.let { r ->
                        Row(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000)).padding(horizontal = 5.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, null, tint = Color(0xFFF5C518), modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(3.dp))
                            Text(String.format(Locale.getDefault(), "%.1f", r), color = Color.White, fontSize = 11.sp)
                        }
                    }
                    t.status?.let { Chip(it) }
                    if (t.networkLogo.isNullOrBlank()) t.network?.let { Chip(it) }
                }
            }
            t.rank?.let { rank ->
                Badge(t.poster, Modifier.align(Alignment.TopStart).offset(-o, -o).size(30.dp), CircleShape) {
                    Text("$rank", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
                }
            }
            val q = qualityBadge(t.quality)
            val logo = t.networkLogo?.takeIf(String::isNotBlank)
            if (q != null || logo != null) Badge(t.poster, Modifier.align(Alignment.TopEnd).offset(o, -o).height(24.dp), RoundedCornerShape(50)) {
                Row(Modifier.fillMaxHeight().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    q?.let { Text(it, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.border(1.dp, Color.White.copy(alpha = .8f), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp)) }
                    logo?.let { AsyncImage(it, t.network, contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(Color.White),
                        modifier = Modifier.height(12.dp).widthIn(max = 44.dp)) }
                }
            }
        }
        Text(t.title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp))
    }
}

/** Frosted badge: the poster blurred behind a light/dark glaze and a hairline. */
@Composable
private fun Badge(image: String?, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.shadow(4.dp, shape).clip(shape)) {
        AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().blur(8.dp))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .18f), Color.Black.copy(alpha = .42f)))))
        Box(Modifier.matchParentSize().border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .5f), Color.White.copy(alpha = .1f))), shape))
        content()
    }
}

@Composable
private fun Chip(text: String) = Text(text, color = Color.White, fontSize = 11.sp, maxLines = 1,
    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000)).padding(horizontal = 6.dp, vertical = 2.dp))

@Composable
private fun WideCard(t: Title, modifier: Modifier, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(modifier.aspectRatio(16f / 9f).shadow(if (hovered) 16.dp else 4.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
        .background(Color(0xFF26231F)).hoverable(hover).clickable(onClick = onClick)
        .then(if (hovered) Modifier.border(2.dp, Color.White.copy(alpha = .8f), RoundedCornerShape(12.dp)) else Modifier)) {
        AsyncImage(t.backdrop ?: t.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = .85f))))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(t.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(t.network, t.status, t.year?.toString(), rating(t.rating)).joinToString("  ·  "), color = Color(0xFFD0D0D0), fontSize = 12.sp)
        }
    }
}

// ---- Search, Library -------------------------------------------------------------------------

@Composable
private fun SearchResults(query: String, onOpen: (Title) -> Unit, onArt: (String?) -> Unit) {
    var hits by remember { mutableStateOf<List<Title>?>(null) }
    LaunchedEffect(query) { delay(200); hits = DesktopCatalogue.search(query.trim()); onArt(hits?.firstOrNull()?.backdrop) }
    val h = hits ?: return Loading()
    if (h.isEmpty()) return Placeholder("Nothing matches “$query”")
    LazyVerticalGrid(GridCells.Adaptive(168.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(40.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Results for “$query”") }
        items(h, key = { it.key }) { PosterCard(it) { onOpen(it) } }
    }
}

@Composable
private fun LibraryScreen(onOpen: (Title) -> Unit, onArt: (String?) -> Unit) {
    var items by remember { mutableStateOf<List<Pair<Title, Float>>?>(null) }
    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) {
            val db = DesktopGraph.database
            db.positions().forProfile(1L).sortedByDescending { it.updatedAtMillis }.filter { !it.isFinished }.mapNotNull { pos ->
                val (kind, id) = pos.mediaKey.split(":", limit = 2).let { it[0] to (it.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null) }
                val progress = if (pos.durationMillis > 0) pos.positionMillis.toFloat() / pos.durationMillis else 0f
                when (kind) {
                    "movie" -> db.movies().byId(id)?.let { m ->
                        val link = db.canonicalMovies().match(m.sourceId, m.streamId)
                        val c = link?.tmdbId?.let { db.canonicalMovies().title(it) }
                        Title("lib-" + pos.mediaKey, c?.title ?: DesktopCatalogue.cleanTitle(m.name), c?.posterUrl ?: m.posterUrl, c?.backdropUrl ?: m.backdropUrl,
                            c?.year ?: m.year, c?.rating ?: m.rating, c?.synopsis ?: m.plot, movieId = m.id) to progress
                    }
                    "ep" -> db.episodes().byId(id)?.let { e ->
                        db.series().byProviderId(e.sourceId, e.seriesId)?.let { s ->
                            Title("lib-" + pos.mediaKey, DesktopCatalogue.cleanTitle(s.name) + "  ·  S${e.season} E${e.episodeNumber}", s.posterUrl,
                                e.stillUrl ?: s.backdropUrl, s.year, s.rating, e.plot ?: s.plot, seriesId = s.id, isShow = true) to progress
                        }
                    }
                    else -> null
                }
            }
        }
        onArt(items?.firstOrNull()?.first?.backdrop)
    }
    val list = items ?: return Loading()
    LazyVerticalGrid(GridCells.Adaptive(420.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(40.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Continue watching") }
        if (list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("Nothing in progress.", color = Dim) }
        items(list, key = { it.first.key }) { (t, p) ->
            Column {
                WideCard(t, Modifier.fillMaxWidth()) { onOpen(t) }
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .15f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).background(Color.White))
                }
            }
        }
    }
}

// ---- Media page ---------------------------------------------------------------------------------

@Composable
private fun MediaPage(t: Title, onBack: () -> Unit, onArt: (String?) -> Unit, onPlay: (String, String) -> Unit) {
    LaunchedEffect(t.key) { onArt(t.backdrop ?: t.poster) }
    var episodes by remember(t.key) { mutableStateOf<List<app.opentv.data.model.Episode>>(emptyList()) }
    var streamUrl by remember(t.key) { mutableStateOf<String?>(null) }
    LaunchedEffect(t.key) {
        withContext(Dispatchers.IO) {
            val db = DesktopGraph.database
            t.movieId?.let { streamUrl = db.movies().byId(it)?.streamUrl }
            t.seriesId?.let { id -> db.series().byId(id)?.let { s -> episodes = db.episodes().forSeries(s.sourceId, s.seriesId).sortedWith(compareBy({ it.season }, { it.episodeNumber })) } }
        }
    }
    LazyVerticalGrid(GridCells.Adaptive(300.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 40.dp, end = 40.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().height(560.dp)) {
                AsyncImage((t.backdrop ?: t.poster)?.replace("/w1280/", "/original/"), null, contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize().fadeEdges())
                Box(Modifier.padding(top = 20.dp).size(42.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f)).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Row(Modifier.align(Alignment.BottomStart).padding(bottom = 30.dp), verticalAlignment = Alignment.Bottom) {
                    AsyncImage(t.poster, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(200.dp).aspectRatio(2f / 3f).shadow(18.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)))
                    Spacer(Modifier.width(28.dp))
                    Column(Modifier.widthIn(max = 720.dp)) {
                        Text(t.title, color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Black, lineHeight = 48.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(listOfNotNull(t.year?.toString(), t.network, t.status, rating(t.rating), qualityBadge(t.quality)).joinToString("  ·  "),
                            color = Color(0xFFDADADA), fontSize = 15.sp)
                        t.overview?.let { Spacer(Modifier.height(12.dp)); Text(it, color = Color(0xFFD0D0D0), fontSize = 15.sp, lineHeight = 22.sp, maxLines = 5, overflow = TextOverflow.Ellipsis) }
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val url = streamUrl ?: episodes.firstOrNull()?.streamUrl
                            PillButton(if (t.isShow) "Play S${episodes.firstOrNull()?.season ?: 1} E${episodes.firstOrNull()?.episodeNumber ?: 1}" else "Watch now",
                                Icons.Default.PlayArrow, primary = true) { url?.let { onPlay(it, t.title) } }
                            PillButton("Watchlist", Icons.Outlined.BookmarkBorder, primary = false) {}
                        }
                    }
                }
            }
        }
        if (episodes.isNotEmpty()) {
            episodes.groupBy { it.season }.forEach { (season, eps) ->
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(if (season == 0) "Specials" else "Season $season", Modifier.padding(top = 8.dp)) }
                items(eps, key = { "ep-${it.id}" }) { e ->
                    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { onPlay(e.streamUrl, "${t.title} · S${e.season} E${e.episodeNumber}") }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF26231F))) {
                            AsyncImage(e.stillUrl ?: t.backdrop, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        Text("${e.episodeNumber}. ${e.title}", color = Ink, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp))
                        e.plot?.let { Text(it, color = Dim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

// ---- Small states ---------------------------------------------------------------------------------

@Composable
private fun Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Ink) }

@Composable
private fun Placeholder(text: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, color = Dim, fontSize = 16.sp) }
