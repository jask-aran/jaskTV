package app.opentv.ui.vod

import androidx.compose.material.icons.outlined.Schedule
import app.opentv.ui.jaskRowEnds
import app.opentv.ui.jaskShelf
import app.opentv.ui.jaskShelfItem
import app.opentv.ui.jaskSlot
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import app.opentv.ui.jaskWhiteFill
import app.opentv.ui.jaskFocusEntry
import app.opentv.ui.jaskCardArt
import app.opentv.ui.jaskCard
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import app.opentv.R
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.focus.focusProperties
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
import app.opentv.ui.jaskArtSource
import app.opentv.ui.jaskWheelScroll
import app.opentv.ui.jaskFocusLift
import app.opentv.ui.LocalLayoutClass
import app.opentv.ui.VodViewModel.CatalogueHome
import app.opentv.ui.VodViewModel.HomeStyle
import app.opentv.ui.VodViewModel.HomeTitle
import coil.compose.AsyncImage
import coil.imageLoader
import kotlinx.coroutines.delay
import java.util.Locale

/*
 * Movies/Shows home: a full-bleed featured pager (top trending titles you have), then ranked
 * sections (trending, new, popular) and the full A–Z grid underneath. All drawn inside the one
 * LazyVerticalGrid of PagedVodGrid, so there is a single vertical scroll.
 */

/** Whose score a home title carries: TMDB's for titles matched to TMDB, the provider's for raw ("raw:…") or unmatched ones. */
internal val HomeTitle.ratingSource get() =
    if (key.startsWith("movie:") || key.startsWith("show:") || tmdbId != null) TmdbRating else ProviderRating

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
                // Prefetch a screenful of cards when the shelf is about to scroll in, so a new shelf
                // isn't composed all at once in the frame it appears.
                // The strategy is a key of the remembered state: a new instance on each recomposition reset the shelf
                // to its start every time the page recomposed (each d-pad move), so it never scrolled.
                @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                val prefetch = androidx.compose.runtime.remember { androidx.compose.foundation.lazy.LazyListPrefetchStrategy(nestedPrefetchItemCount = 12) }
                @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                val shelf = androidx.compose.foundation.lazy.rememberLazyListState(prefetchStrategy = prefetch)
                val shelfFocus = app.opentv.ui.rememberJaskShelfFocus(shelf)
                androidx.compose.foundation.lazy.LazyRow(Modifier.bleed(16.dp).then(if (section.items.isNotEmpty()) Modifier.jaskSlot("home-shelf-${section.key}") else Modifier).jaskShelf(shelfFocus), state = shelf,
                    contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(section.items.size, key = { "shelf-${section.key}-${section.items[it].key}" }) { i ->
                        val title = section.items[i]
                        val entry = Modifier.jaskShelfItem(shelfFocus, i)
                        if (section.style == HomeStyle.WIDE) JaskWideCard(title, entry.width(340.dp)) { onOpen(title) }
                        else Box(entry.width(158.dp)) { JaskPosterCard(title.title, title.poster, title.facts()) { onOpen(title) } }
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
    // D-pad: the hero's buttons are its slot; Left/Right past them change title. It holds still while you're on it.
    val hero = remember { HeroFocus() }
    val heroScope = rememberCoroutineScope()
    LaunchedEffect(pager.settledPage, titles.size, hero.focused) {
        if (hero.focused) return@LaunchedEffect
        delay(14_000)
        if (!pager.isScrollInProgress && titles.size > 1)
            pager.animateScrollToPage((pager.settledPage + 1) % titles.size, animationSpec = androidx.compose.animation.core.tween(1_100))
    }
    // The page colour, read only while drawing (it animates after each change of title; see JaskGlassSurface).
    val ambient = app.opentv.ui.LocalJaskAmbient.current
    val wash = { ambient?.wash ?: JaskPageCanvas }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val tail = with(density) { 200.dp.toPx() }
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    // Wide: the item ends where the first shelf's title starts, but the art carries on [overlap]
    // further, under that shelf, and fades out there, so the colour change happens behind the
    // cards. The art also starts [shift] lower, clear of the header.
    val heroH = (screenH * 0.74f).coerceIn(340f, 680f).dp
    val overlap = HeroOverlap
    val shift = HeroShift
    var top by remember { mutableFloatStateOf(0f) }
    Box(modifier.fillMaxWidth().then(if (wide) Modifier.heroOverhang(overlap).height(heroH + overlap)
        .onGloballyPositioned { top = it.positionInRoot().y }
    else Modifier.aspectRatio(0.9f).drawBehind {
        // Phone: below the hero (outside the pager, which clips), its colour fades into the dithered page.
        val wash = wash()
        drawRect(Brush.verticalGradient(listOf(wash, wash.copy(alpha = 0f)), startY = size.height - 1f, endY = size.height + tail),
            topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 1f), size = androidx.compose.ui.geometry.Size(size.width, tail))
    })) {
        // Wide: the art fades out as the hero scrolls away, so the part under the shelf doesn't
        // vanish at once when the hero item leaves the list.
        val heroPx = with(density) { heroH.toPx() }
        HorizontalPager(pager, Modifier.fillMaxSize().then(if (wide) Modifier.graphicsLayer {
            alpha = ((heroPx + top) / (heroPx * 0.6f)).coerceIn(0f, 1f)
        } else Modifier), key = { titles[it].key },
            // The next title is composed (and its art loaded) before it slides in, not during the slide.
            beyondViewportPageCount = 1) { page ->
            val t = titles[page]
            Box(Modifier.fillMaxSize()) {
                // The art fades into the page colour under the shelf by painting that colour over it, not by masking
                // it through an off-screen layer (heroFadeOut): two full-screen off-screen buffers per frame of a slide.
                if (wide) Box(Modifier.fillMaxSize().drawWithContent {
                    drawContent()
                    val wash = wash(); val start = heroPx - 24.dp.toPx()
                    drawRect(Brush.verticalGradient(0f to wash.copy(alpha = 0f), 0.45f to wash.copy(alpha = 0.4f), 1f to wash,
                        startY = start, endY = size.height), topLeft = androidx.compose.ui.geometry.Offset(0f, start))
                }) {
                    // Wide screens show the backdrop near its own shape, and a TV's UI is 1080p: TMDB's 1280-wide
                    // copy, not the original (often 3840 wide, ~9x the pixels to download and decode per title).
                    AsyncImage(t.backdrop, null, contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize().padding(top = shift))
                    Box(Modifier.fillMaxSize().drawBehind { heroScrims(heroPx, with(density) { shift.toPx() }, wash()) })
                } else {
                    // Art → the ambient colour (flat, no dots over the art) → below the hero, that colour
                    // fades into the dithered background (drawn past the item's bottom, under the rows).
                    AsyncImage(t.backdrop?.replace("/t/p/w1280/", "/t/p/original/"), null, contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.55f), 0.2f to Color.Transparent, 0.55f to Color.Transparent,
                        0.8f to Color.Black.copy(alpha = 0.18f), 1f to Color.Transparent)))
                    Box(Modifier.fillMaxSize().drawBehind { val wash = wash()
                        drawRect(Brush.verticalGradient(0.5f to wash.copy(alpha = 0f), 0.8f to wash.copy(alpha = .75f), 1f to wash)) })
                }
                // Touch: tapping the art opens the title. Only the part above the shelf; the d-pad uses the buttons.
                Box(Modifier.fillMaxWidth().then(if (wide) Modifier.height(heroH) else Modifier.fillMaxHeight())
                    .focusProperties { canFocus = false }.clickable { onOpen(t) })
            }
        }
        // Only the art slides. The title, facts, synopsis and buttons are drawn once, over the pager, and change in place
        // (a short fade) when the next title takes over: moving and re-laying them out on every frame of the slide was
        // most of its cost. Nothing here takes touches, so swipes and taps reach the art.
        val t = titles[pager.currentPage.coerceIn(0, titles.lastIndex)]
        val textAlpha = remember { androidx.compose.animation.core.Animatable(1f) }
        LaunchedEffect(t.key) { textAlpha.snapTo(0f); textAlpha.animateTo(1f, androidx.compose.animation.core.tween(260)) }
        // The network logo only once it has loaded: until then (or if it fails) the network's name, so the facts
        // line never shows an empty slot between two dots while the logo downloads.
        val context = androidx.compose.ui.platform.LocalContext.current
        val logoUrl = t.networkLogo?.takeIf(String::isNotBlank)
        var logoReady by remember(logoUrl) { mutableStateOf(false) }
        LaunchedEffect(logoUrl) {
            if (logoUrl != null) logoReady = runCatching {
                context.imageLoader.execute(coil.request.ImageRequest.Builder(context).data(logoUrl).build()) is coil.request.SuccessResult
            }.getOrDefault(false)
        }
        // The hero's tag colours are the media page's (FactBadges), which has no neutral tag for the provider's own
        // score: those titles show no score here, as before they showed a wrong "TMDB".
        val facts = HeroFacts(rank = t.rank.takeIf { caption == null }, rating = t.rating.takeIf { t.ratingSource == TmdbRating },
            quality = qualityBadge(t.quality),
            networkLogo = logoUrl.takeIf { logoReady }, network = t.network, text = listOfNotNull(t.year?.toString(), t.status),
            noSource = !t.available, caption = caption, comingSoon = t.comingSoon)
        Box(Modifier.fillMaxWidth().then(if (wide) Modifier.height(heroH) else Modifier.fillMaxHeight())) {
            if (wide) Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 44.dp, end = 44.dp, bottom = 36.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).graphicsLayer { alpha = textAlpha.value * ((heroPx + top) / (heroPx * 0.6f)).coerceIn(0f, 1f) }) {
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
                // Pinned to the bottom of the text: the button stays put whatever the title's length or synopsis.
                HeroActions(t, watchlist, hero, current = true, modifier = Modifier.align(Alignment.Bottom),
                    onStep = { d -> if (titles.size > 1) { hero.wanted = true
                        heroScope.launch { pager.animateScrollToPage((pager.currentPage + d + titles.size) % titles.size,
                            animationSpec = androidx.compose.animation.core.tween(450)) } } }) { onOpen(t) }
            } else Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 18.dp)
                .graphicsLayer { alpha = textAlpha.value }, horizontalAlignment = Alignment.CenterHorizontally) {
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
        if (titles.size > 1) Row(Modifier.align(if (wide) Alignment.BottomStart else Alignment.BottomCenter)
            .padding(start = if (wide) 44.dp else 0.dp, bottom = if (wide) overlap + 14.dp else 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(titles.size) { i ->
                Box(Modifier.size(if (i == pager.currentPage) 7.dp else 5.dp).clip(CircleShape)
                    .background(if (i == pager.currentPage) Color.White else Color.White.copy(alpha = 0.4f)))
            }
        }
    }
}

/** Saves a home title to the Watchlist without opening it; supplied by the Movies and Shows homes. */
internal class HomeWatchlist(val isSaved: suspend (HomeTitle) -> Boolean, val toggle: suspend (HomeTitle) -> Unit)

/** The hero's d-pad state: whether its buttons have focus, and whether the next title should take it. */
private class HeroFocus { var focused by mutableStateOf(false); var wanted = false }

/**
 * Wide hero, right side: Play (or Details, for a title not on your sources) and a Watchlist toggle. There's always
 * a button, so the hero can always be reached with the d-pad. Left/Right past the ends change title ([onStep]).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun HeroActions(t: HomeTitle, @Suppress("UNUSED_PARAMETER") watchlist: HomeWatchlist?, hero: HeroFocus, current: Boolean,
    modifier: Modifier = Modifier, onStep: (Int) -> Unit, onOpen: () -> Unit) {
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    // The title just stepped to with Left/Right: take focus as soon as it's the current page.
    LaunchedEffect(current) { if (current && hero.wanted) { hero.wanted = false; androidx.compose.runtime.withFrameNanos { }; runCatching { requester.requestFocus() } } }
    // One button, the same for every title: it opens the title's page (where Play and the Watchlist are).
    Box(modifier.then(if (current) Modifier.jaskSlot("home-featured") else Modifier).focusRequester(requester)
        .onFocusChanged { if (current) hero.focused = it.hasFocus }
        .focusProperties {
            exit = { d ->
                when (d) {
                    androidx.compose.ui.focus.FocusDirection.Left -> { onStep(-1); androidx.compose.ui.focus.FocusRequester.Cancel }
                    androidx.compose.ui.focus.FocusDirection.Right -> { onStep(+1); androidx.compose.ui.focus.FocusRequester.Cancel }
                    else -> androidx.compose.ui.focus.FocusRequester.Default
                }
            }
        }.focusGroup()) {
        Row(Modifier.jaskFocusLift(key = "hero:open").jaskFocusEntry().height(52.dp).clip(RoundedCornerShape(50)).jaskWhiteFill(RoundedCornerShape(50)).clickable(onClick = onOpen)
            .padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null, tint = Color.White, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(6.dp))
            Text("Details", color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        // No corner mark: the hero's facts line already says "Coming 16 Dec" or "Not on your sources".
    }
}

/** Landscape backdrop card with the title on the art (new releases / airing now). */
@Composable
private fun JaskWideCard(t: HomeTitle, modifier: Modifier, onClick: () -> Unit) {
    val source = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(modifier.jaskCard(source, key = "wide:${t.key}", onClick = onClick).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))
        .background(Color(0xFF282522)).jaskCardArt(source)) {
        AsyncImage(t.backdrop?.replace("/t/p/w1280/", "/t/p/w780/") ?: t.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            colorFilter = if (t.available) null else UnavailableArt, alpha = if (t.available) 1f else .7f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
        if (!t.available) UnavailableMark(Modifier.align(Alignment.TopEnd).padding(6.dp), t.comingSoon)
        Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
            Text(t.title, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            JaskFactsLine(listOfNotNull(t.network, t.status, t.year?.toString().takeIf { t.network == null }), t.rating, t.ratingSource,
                color = Color(0xFFD0D0D0), style = MaterialTheme.typography.labelSmall, small = true)
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
            val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = if (phone) androidx.compose.foundation.lazy.grid.GridCells.Fixed(3) else androidx.compose.foundation.lazy.grid.GridCells.Adaptive(120.dp),
                modifier = Modifier.fillMaxSize().jaskWheelScroll(grid), state = grid,
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
    val art = app.opentv.ui.rememberArtBackdrop(t.poster, cells = 16)
    Row(Modifier.fillMaxWidth().jaskFocusLift(1.03f, key = "hit:${t.key}").height(124.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E1C1B)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxHeight().aspectRatio(2f / 3f).background(Color(0xFF282522)).then(Modifier.jaskArtSource(art))) {
            AsyncImage(t.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            t.rank?.let { rank -> CompositionLocalProvider(app.opentv.ui.LocalJaskBackdrop provides art) {
                PosterFrost(CircleShape, Modifier.align(Alignment.TopStart).padding(5.dp).size(22.dp)) {
                    Text("$rank", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.Center))
                }
            } }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(t.title, color = Color(0xFFF6F3EF), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            // One plain line, like the cards: year · quality · status · network, then the tagged score.
            JaskFactsLine(listOfNotNull(t.year?.toString(), qualityBadge(t.quality), t.status, t.network), t.rating, t.ratingSource)
            t.overview?.takeIf(String::isNotBlank)?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Color(0xFFB0B0B0), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}


/** Card badges for a home/search title, the same everywhere a [HomeTitle] is drawn. */
internal fun HomeTitle.facts() = PosterFacts(rank = rank, rating = rating, status = status, quality = quality,
    networkLogo = networkLogo, network = network, noSource = !available, comingSoon = comingSoon, ratingSource = ratingSource)

/** Small frosted "not on your sources" mark for wide art and rows. */
@Composable
internal fun UnavailableMark(modifier: Modifier = Modifier, comingSoon: String? = null) {
    Row(modifier.clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(if (comingSoon != null) androidx.compose.material.icons.Icons.Outlined.Schedule else androidx.compose.material.icons.Icons.Outlined.CloudOff,
            null, tint = if (comingSoon != null) Color.White else Color(0xFFFFC7BD), modifier = Modifier.size(12.dp))
        Text(comingSoon ?: stringResource(R.string.jask_not_on_sources), color = Color(0xFFF2F2F2), style = MaterialTheme.typography.labelSmall)
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
    preview?.let { t -> UnavailableSheet(t) { preview = null } }
    return { t -> if (!t.available) preview = t else scope.launch { open(t) } }
}

@Composable
private fun UnavailableSheet(t: HomeTitle, onDismiss: () -> Unit) {
    val vm: app.opentv.ui.VodViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val finders by vm.hasFinders.collectAsState()
    val play = LocalJaskPlayUrl.current
    // Not on your sources: torrents through a core on this network may still have it (films; shows come later).
    val route = rememberTorrentRoute()
    var found by remember { mutableStateOf<List<app.opentv.torrent.Release>?>(null) }
    LaunchedEffect(t.key) { if (route != null && t.key.startsWith("movie:")) found = runCatching { vm.findReleases(t.title, t.year, t.tmdbId) }.getOrDefault(emptyList()) }
    val template = app.opentv.data.model.Download(mediaKey = "tmdb:${t.tmdbId}", kind = "MOVIE", title = t.title,
        posterUrl = t.poster, tag = null, sourceUrl = "", systemId = 0, createdMillis = 0)
    app.opentv.ui.JaskSheet(onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            AsyncImage(t.poster, null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(96.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF282522)))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                JaskFactsLine(listOfNotNull(t.year?.toString()), t.rating, t.ratingSource, color = Color(0xFFCFCFCF),
                    style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                UnavailableMark(comingSoon = t.comingSoon)
                Spacer(Modifier.height(8.dp))
                Text(if (t.comingSoon != null) "Not out on digital yet, so none of your sources has it. Copies sometimes turn up early: the torrents below are checked now."
                    else stringResource(R.string.jask_not_on_sources_body), color = Color(0xFFBDBDBD), style = MaterialTheme.typography.bodySmall)
            }
        }
        t.overview?.takeIf(String::isNotBlank)?.let {
            Text(it, color = Color(0xFFD6D6D6), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        }
        if (t.key.startsWith("movie:")) {
            val best = remember(found) { found?.let { bestForDevice(it, episode = false) } }
            var mode by remember { mutableStateOf(SourceMode.WATCH) }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                app.opentv.ui.settings.JaskChoicePill("Watch", selected = mode == SourceMode.WATCH) { mode = SourceMode.WATCH }
                app.opentv.ui.settings.JaskChoicePill("Download", selected = mode == SourceMode.DOWNLOAD) { mode = SourceMode.DOWNLOAD }
            }
            SourcesPanel(mode = mode, provider = emptyList(), torrentRoute = route,
                torrents = found?.map { r -> SourceRow(r.name, torrentDetail(r), recommended = if (r == best) recommendReason(r) else null,
                    play = { vm.playableRelease(r)?.let { url -> onDismiss(); play("tmdb:${t.tmdbId}", url, t.title); true } ?: false },
                    download = { vm.downloadRelease(r, template) }) },
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp))
        }
    }
}
