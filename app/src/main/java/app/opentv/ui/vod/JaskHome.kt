package app.opentv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.ui.draw.drawBehind
import kotlinx.coroutines.launch
import app.opentv.R
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.ui.LayoutClass
import app.opentv.ui.LocalLayoutClass
import app.opentv.ui.VodViewModel.CatalogueHome
import app.opentv.ui.VodViewModel.HomeStyle
import app.opentv.ui.VodViewModel.HomeTitle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Locale

/*
 * Movies/Shows home: a full-bleed featured pager (top trending titles you have), then ranked
 * sections (trending, new, popular) and the full A–Z grid underneath. All drawn inside the one
 * LazyVerticalGrid of PagedVodGrid, so there is a single vertical scroll.
 */

private fun rating(value: Double?) = value?.takeIf { it > 0 }?.let { "★ " + String.format(Locale.getDefault(), "%.1f", it) }

/** Lets a full-span grid item ignore the grid's 16dp side padding. */
private fun Modifier.bleed(side: androidx.compose.ui.unit.Dp) = layout { measurable, constraints ->
    val extra = (side * 2).roundToPx()
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

internal fun LazyGridScope.jaskHome(home: CatalogueHome, columns: Int, watchlist: HomeWatchlist? = null, onOpen: (HomeTitle) -> Unit) {
    if (home.featured.isNotEmpty()) item(key = "home-featured", span = { GridItemSpan(maxLineSpan) }) {
        JaskFeatured(home.featured, Modifier.bleed(16.dp), watchlist = watchlist, onOpen = onOpen)
    } else item(key = "home-top", span = { GridItemSpan(maxLineSpan) }) {
        Spacer(Modifier.statusBarsPadding().height(64.dp))
    }
    home.sections.forEach { section ->
        val first = section == home.sections.first() && home.featured.isNotEmpty()
        item(key = "home-h-${section.key}", span = { GridItemSpan(maxLineSpan) }) {
            Text(section.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = Color(0xFFF4F4F4), modifier = Modifier.padding(top = if (first) 0.dp else 10.dp))
        }
        val phone = columns <= 3
        when {
            !phone -> item(key = "home-shelf-${section.key}", span = { GridItemSpan(maxLineSpan) }) {
                // Wide screens: one sideways-scrolling shelf per section, edge to edge.
                androidx.compose.foundation.lazy.LazyRow(Modifier.bleed(16.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(section.items.size, key = { "shelf-${section.key}-${section.items[it].key}" }) { i ->
                        val title = section.items[i]
                        if (section.style == HomeStyle.WIDE) JaskWideCard(title, Modifier.width(340.dp)) { onOpen(title) }
                        else Box(Modifier.width(158.dp)) { JaskPosterCard(title.title, title.poster, title.facts()) { onOpen(title) } }
                    }
                }
            }
            section.style == HomeStyle.POSTERS -> items(section.items.take(6), key = { "home-${section.key}-${it.key}" }) { title ->
                JaskPosterCard(title.title, title.poster, title.facts()) { onOpen(title) }
            }
            else -> section.items.take(4).chunked(2).forEachIndexed { row, pair ->
                item(key = "home-${section.key}-row$row", span = { GridItemSpan(maxLineSpan) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { JaskWideCard(it, Modifier.weight(1f)) { onOpen(it) } }
                        repeat(2 - pair.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** Swipeable full-bleed hero: backdrop, title, facts and synopsis; tap opens the page; advances every 14 seconds. */
@Composable
internal fun JaskFeatured(titles: List<HomeTitle>, modifier: Modifier, caption: String? = null, watchlist: HomeWatchlist? = null,
    onOpen: (HomeTitle) -> Unit) {
    val wide = LocalLayoutClass.current != LayoutClass.PHONE
    val pager = rememberPagerState { titles.size }
    app.opentv.ui.JaskAmbientFrom(titles.getOrNull(pager.settledPage)?.let { it.backdrop ?: it.poster })
    LaunchedEffect(pager.settledPage, titles.size) {
        delay(14_000)
        if (!pager.isScrollInProgress && titles.size > 1)
            pager.animateScrollToPage((pager.settledPage + 1) % titles.size, animationSpec = androidx.compose.animation.core.tween(1_100))
    }
    val wash = app.opentv.ui.LocalJaskAmbient.current?.wash ?: JaskPageCanvas
    val tail = with(androidx.compose.ui.platform.LocalDensity.current) { 200.dp.toPx() }
    // Below the hero (outside the pager, which clips), its colour fades into the dithered page.
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    Box(modifier.fillMaxWidth().then(if (wide) Modifier.height((screenH * 0.64f).coerceIn(360f, 600f).dp) else Modifier.aspectRatio(0.9f)).drawBehind {
        drawRect(Brush.verticalGradient(listOf(wash, wash.copy(alpha = 0f)), startY = size.height - 1f, endY = size.height + tail),
            topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 1f), size = androidx.compose.ui.geometry.Size(size.width, tail))
    }) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { titles[it].key }) { page ->
            val t = titles[page]
            Box(Modifier.fillMaxSize().clickable { onOpen(t) }) {
                // Art → the ambient colour (flat, no dots over the art) → below the hero, that colour
                // fades into the dithered background (drawn past the item's bottom, under the rows).
                val wash = app.opentv.ui.LocalJaskAmbient.current?.wash ?: JaskPageCanvas
                AsyncImage(t.backdrop?.replace("/t/p/w1280/", "/t/p/original/"), null, contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.55f), 0.2f to Color.Transparent, 0.55f to Color.Transparent,
                    0.8f to Color.Black.copy(alpha = 0.18f), 1f to Color.Transparent)))
                // Wide screens: text in a left column over a soft side scrim, not stretched across the art.
                if (wide) Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.6f), 0.45f to Color.Black.copy(alpha = 0.15f), 0.7f to Color.Transparent)))
                // Last over the art and scrims, so the hero's bottom edge is exactly the wash colour (no seam).
                Box(Modifier.fillMaxSize().background(if (wide) Brush.verticalGradient(0.68f to wash.copy(alpha = 0f), 0.88f to wash.copy(alpha = .7f), 1f to wash)
                    else Brush.verticalGradient(0.5f to wash.copy(alpha = 0f), 0.8f to wash.copy(alpha = .75f), 1f to wash)))
                val facts = HeroFacts(rank = t.rank.takeIf { caption == null }, rating = t.rating, quality = qualityBadge(t.quality),
                    networkLogo = t.networkLogo, network = t.network, text = listOfNotNull(t.year?.toString(), t.status),
                    noSource = !t.available, caption = caption)
                if (wide) Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 44.dp, end = 44.dp, bottom = 40.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Column(Modifier.widthIn(max = 600.dp)) {
                            Text(t.title, color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(10.dp))
                            JaskHeroBadges(facts, center = false)
                            t.overview?.let {
                                Spacer(Modifier.height(12.dp))
                                Text(it, color = Color(0xFFDADADA), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Spacer(Modifier.width(32.dp))
                    HeroActions(t, watchlist) { onOpen(t) }
                } else Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t.title, color = Color.White, style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    JaskHeroBadges(facts, center = true)
                    t.overview?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = Color(0xFFCFCFCF), style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (titles.size > 1) Row(Modifier.align(if (wide) Alignment.BottomStart else Alignment.BottomCenter)
            .padding(start = if (wide) 44.dp else 0.dp, bottom = if (wide) 16.dp else 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(titles.size) { i ->
                Box(Modifier.size(if (i == pager.currentPage) 7.dp else 5.dp).clip(CircleShape)
                    .background(if (i == pager.currentPage) Color.White else Color.White.copy(alpha = 0.4f)))
            }
        }
    }
}

/** Saves a home title to the Watchlist without opening it; supplied by the Movies and Shows homes. */
internal class HomeWatchlist(val isSaved: suspend (HomeTitle) -> Boolean, val toggle: suspend (HomeTitle) -> Unit)

/** Wide hero, right side: Play (opens the title) and a Watchlist toggle. */
@Composable
private fun HeroActions(t: HomeTitle, watchlist: HomeWatchlist?, onOpen: () -> Unit) {
    val scope = rememberCoroutineScope()
    var saved by remember(t.key) { mutableStateOf<Boolean?>(null) }
    if (watchlist != null && t.available) LaunchedEffect(t.key) { saved = watchlist.isSaved(t) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (t.available) Row(Modifier.height(52.dp).clip(RoundedCornerShape(50)).background(Color.White).clickable(onClick = onOpen)
            .padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(androidx.compose.material.icons.Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(6.dp))
            Text("Play", color = Color.Black, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        saved?.let { on ->
            Box(Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.38f))
                .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                .clickable { saved = !on; scope.launch { watchlist?.toggle(t) } }, contentAlignment = Alignment.Center) {
                Icon(if (on) androidx.compose.material.icons.Icons.Filled.Star else androidx.compose.material.icons.Icons.Outlined.StarOutline,
                    stringResource(R.string.jask_watchlist), tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** Landscape backdrop card with the title on the art (new releases / airing now). */
@Composable
private fun JaskWideCard(t: HomeTitle, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF282522)).clickable(onClick = onClick)) {
        AsyncImage(t.backdrop ?: t.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            colorFilter = if (t.available) null else UnavailableArt, alpha = if (t.available) 1f else .7f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
        if (!t.available) UnavailableMark(Modifier.align(Alignment.TopEnd).padding(6.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
            Text(t.title, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(t.network, t.status, t.year?.toString().takeIf { t.network == null }, rating(t.rating)).joinToString("  ·  "), color = Color(0xFFD0D0D0),
                style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}


/**
 * Search layout: the most relevant hit as the full-bleed hero, hits 2–10 as detailed rows (rank
 * circle, facts, description), then the rest as posters. [hits] null = still searching.
 */
@Composable
internal fun JaskSearchResults(hits: List<HomeTitle>?, emptyText: String, onOpen: (HomeTitle) -> Unit) {
    val padding = app.opentv.ui.LocalJaskContentPadding.current
    when {
        hits == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
        hits.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
            Text(emptyText, color = Color(0xFFBDBDBD), style = MaterialTheme.typography.bodyLarge)
        }
        else -> {
            app.opentv.ui.JaskAmbientFrom(hits.first().let { it.backdrop ?: it.poster })
            val phone = LocalLayoutClass.current == LayoutClass.PHONE
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = if (phone) androidx.compose.foundation.lazy.grid.GridCells.Fixed(3) else androidx.compose.foundation.lazy.grid.GridCells.Adaptive(120.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = padding.calculateBottomPadding() + 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "top", span = { GridItemSpan(maxLineSpan) }) {
                    JaskFeatured(listOf(hits.first()), Modifier.bleed(16.dp), caption = "Top result", onOpen = onOpen)
                }
                items(hits.drop(1).take(9), key = { "row-" + it.key }, span = { GridItemSpan(maxLineSpan) }) { JaskHitRow(it) { onOpen(it) } }
                if (hits.size > 10) {
                    item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                        Text("More results", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFF4F4F4), modifier = Modifier.padding(top = 8.dp))
                    }
                    items(hits.drop(10), key = { "grid-" + it.key }) { t ->
                        JaskPosterCard(t.title, t.poster, t.facts().copy(rank = null)) { onOpen(t) }
                    }
                }
            }
        }
    }
}

/** A detailed search hit: poster with its rank circle, title, facts and description. */
@Composable
private fun JaskHitRow(t: HomeTitle, onClick: () -> Unit) {
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    Row(Modifier.fillMaxWidth().height(124.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E1C1B)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxHeight().aspectRatio(2f / 3f).background(Color(0xFF282522)).onSizeChanged { size = it }) {
            AsyncImage(t.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            t.rank?.let { rank ->
                PosterFrost(t.poster, size, CircleShape, Modifier.align(Alignment.TopStart).padding(5.dp).size(22.dp)) {
                    Text("$rank", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.Center))
                }
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(t.title, color = Color(0xFFF6F3EF), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rating(t.rating)?.let { Text(it, color = Color(0xFFF5C518), style = MaterialTheme.typography.labelMedium) }
                t.year?.let { Text("$it", color = Color(0xFFBDBDBD), style = MaterialTheme.typography.labelMedium) }
                qualityBadge(t.quality)?.let {
                    Text(it, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.border(1.dp, Color.White.copy(alpha = .7f), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp))
                }
                t.status?.let { Text(it, color = Color(0xFFBDBDBD), style = MaterialTheme.typography.labelMedium) }
                if (!t.networkLogo.isNullOrBlank()) AsyncImage(t.networkLogo, t.network, contentScale = ContentScale.Fit,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFFE0E0E0)), modifier = Modifier.height(11.dp).widthIn(max = 40.dp))
                else t.network?.let { Text(it, color = Color(0xFFBDBDBD), style = MaterialTheme.typography.labelMedium, maxLines = 1) }
            }
            t.overview?.takeIf(String::isNotBlank)?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Color(0xFFB0B0B0), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}


/** Card badges for a home/search title, the same everywhere a [HomeTitle] is drawn. */
internal fun HomeTitle.facts() = PosterFacts(rank = rank, rating = rating, status = status, quality = quality,
    networkLogo = networkLogo, network = network, noSource = !available)

/** Small frosted "not on your sources" mark for wide art and rows. */
@Composable
internal fun UnavailableMark(modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(androidx.compose.material.icons.Icons.Outlined.CloudOff, null, tint = Color(0xFFFFC7BD), modifier = Modifier.size(12.dp))
        Text(stringResource(R.string.jask_not_on_sources), color = Color(0xFFF2F2F2), style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Opens a home/search title: available ones through [open] (the media page); unavailable ones
 * show a frosted preview sheet instead, since there is nothing to play or pick a source from.
 */
@Composable
internal fun rememberHomeOpener(open: suspend (HomeTitle) -> Unit): (HomeTitle) -> Unit {
    var preview by remember { mutableStateOf<HomeTitle?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    preview?.let { UnavailableSheet(it) { preview = null } }
    return { t -> if (!t.available) preview = t else scope.launch { open(t) } }
}

@Composable
private fun UnavailableSheet(t: HomeTitle, onDismiss: () -> Unit) {
    app.opentv.ui.JaskSheet(onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            AsyncImage(t.poster, null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(96.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF282522)))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(listOfNotNull(t.year?.toString(), rating(t.rating)).joinToString("  ·  "), color = Color(0xFFCFCFCF),
                    style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                UnavailableMark()
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.jask_not_on_sources_body), color = Color(0xFFBDBDBD), style = MaterialTheme.typography.bodySmall)
            }
        }
        t.overview?.takeIf(String::isNotBlank)?.let {
            Text(it, color = Color(0xFFD6D6D6), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        }
    }
}
