package app.opentv.ui.vod

import androidx.compose.foundation.background
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

internal fun LazyGridScope.jaskHome(home: CatalogueHome, columns: Int, onOpen: (HomeTitle) -> Unit) {
    if (home.featured.isNotEmpty()) item(key = "home-featured", span = { GridItemSpan(maxLineSpan) }) {
        JaskFeatured(home.featured, Modifier.bleed(16.dp), onOpen = onOpen)
    } else item(key = "home-top", span = { GridItemSpan(maxLineSpan) }) {
        Spacer(Modifier.statusBarsPadding().height(64.dp))
    }
    home.sections.forEach { section ->
        val first = section == home.sections.first() && home.featured.isNotEmpty()
        item(key = "home-h-${section.key}", span = { GridItemSpan(maxLineSpan) }) {
            Text(section.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = Color(0xFFF4F4F4), modifier = Modifier.padding(top = if (first) 0.dp else 10.dp))
        }
        when (section.style) {
            HomeStyle.POSTERS -> items(section.items, key = { "home-${section.key}-${it.key}" }) { title ->
                JaskPosterCard(title.title, title.poster, PosterFacts(rank = title.rank, rating = title.rating, status = title.status,
                    quality = title.quality, networkLogo = title.networkLogo, network = title.network)) { onOpen(title) }
            }
            HomeStyle.WIDE -> {
                val perRow = if (columns <= 3) 2 else 3
                section.items.chunked(perRow).forEachIndexed { row, pair ->
                    item(key = "home-${section.key}-row$row", span = { GridItemSpan(maxLineSpan) }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { JaskWideCard(it, Modifier.weight(1f)) { onOpen(it) } }
                            repeat(perRow - pair.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/** Swipeable full-bleed hero: backdrop, title, facts and synopsis; tap opens the page; advances every 14 seconds. */
@Composable
internal fun JaskFeatured(titles: List<HomeTitle>, modifier: Modifier, caption: String? = null, onOpen: (HomeTitle) -> Unit) {
    val wide = LocalLayoutClass.current != LayoutClass.PHONE
    val pager = rememberPagerState { titles.size }
    LaunchedEffect(pager.settledPage, titles.size) {
        delay(14_000)
        if (!pager.isScrollInProgress && titles.size > 1)
            pager.animateScrollToPage((pager.settledPage + 1) % titles.size, animationSpec = androidx.compose.animation.core.tween(1_100))
    }
    Box(modifier.fillMaxWidth().then(if (wide) Modifier.height(440.dp) else Modifier.aspectRatio(0.9f))) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { titles[it].key }) { page ->
            val t = titles[page]
            Box(Modifier.fillMaxSize().clickable { onOpen(t) }) {
                AsyncImage(t.backdrop?.replace("/t/p/w1280/", "/t/p/original/"), null, contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.55f), 0.2f to Color.Transparent, 0.5f to Color.Transparent,
                    0.68f to JaskPageCanvas.copy(alpha = 0.45f), 0.82f to JaskPageCanvas.copy(alpha = 0.82f), 0.93f to JaskPageCanvas.copy(alpha = 0.97f), 1f to JaskPageCanvas)))
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t.title, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text(listOfNotNull(caption ?: t.rank?.let { "#$it this week" }, t.year?.toString(), t.network, t.status, rating(t.rating)).joinToString("  ·  "),
                        color = Color(0xFFDADADA), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    t.overview?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = Color(0xFFCFCFCF), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (titles.size > 1) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(titles.size) { i ->
                Box(Modifier.size(if (i == pager.currentPage) 7.dp else 5.dp).clip(CircleShape)
                    .background(if (i == pager.currentPage) Color.White else Color.White.copy(alpha = 0.4f)))
            }
        }
    }
}

/** Landscape backdrop card with the title on the art (new releases / airing now). */
@Composable
private fun JaskWideCard(t: HomeTitle, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF282522)).clickable(onClick = onClick)) {
        AsyncImage(t.backdrop ?: t.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
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
                        JaskPosterCard(t.title, t.poster, PosterFacts(rating = t.rating, status = t.status, quality = t.quality,
                            networkLogo = t.networkLogo, network = t.network)) { onOpen(t) }
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
