package app.opentv.ui.vod

import androidx.compose.foundation.background
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
        JaskFeatured(home.featured, Modifier.bleed(16.dp), onOpen)
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
private fun JaskFeatured(titles: List<HomeTitle>, modifier: Modifier, onOpen: (HomeTitle) -> Unit) {
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
                    Text(listOfNotNull(t.rank?.let { "#$it this week" }, t.year?.toString(), t.network, t.status, rating(t.rating)).joinToString("  ·  "),
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
