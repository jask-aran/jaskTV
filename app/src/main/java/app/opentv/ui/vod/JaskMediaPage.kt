package app.opentv.ui.vod

import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.launch
import androidx.compose.foundation.focusable
import androidx.compose.foundation.indication
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.foundation.combinedClickable
import app.opentv.ui.jaskWhiteFill
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.ui.res.stringResource
import app.opentv.R
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import app.opentv.ui.JaskGlassSurface
import app.opentv.ui.jaskArtSource
import app.opentv.ui.jaskFocusLift
import app.opentv.ui.jaskRowEnds
import app.opentv.ui.jaskShelf
import androidx.compose.foundation.lazy.itemsIndexed
import app.opentv.ui.jaskShelfItem
import app.opentv.ui.JaskGlassCircle
import app.opentv.ui.JaskGlassHost
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.opentv.data.model.Episode
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.parser.episodeDisplayTitle
import app.opentv.ui.LayoutClass
import app.opentv.ui.LocalLayoutClass
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Touch media page (movie and show details), Plex-like: full-bleed art, one primary action,
 * small icon actions, and a page tinted from the artwork. TV keeps the older detail layout.
 */

internal val JaskPageCanvas = Color(0xFF141312)

/** A dark colour from the artwork's accent (see [app.opentv.ui.JaskColor]), animated in once decoded; canvas until then. */
@Composable
internal fun rememberArtworkTint(url: String?): Color {
    val context = LocalContext.current
    var target by remember(url) { mutableStateOf(JaskPageCanvas) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        app.opentv.ui.artworkAccent(context, url)?.let { target = app.opentv.ui.JaskColor.tint(it) }
    }
    return animateColorAsState(target, tween(300), label = "artworkTint").value
}

/**
 * The page behind a media page: the artwork tint continues from the hero's bottom edge (where the
 * hero's own gradient ends in exactly this colour) and eases into the canvas over [fadeLength].
 * Positioned in absolute pixels against the hero, so there is no seam while scrolling.
 */
@Composable
internal fun Modifier.jaskPageBackground(tint: Color, list: LazyListState, heroHeightPx: Int): Modifier {
    // Phone: no page of its own; the touch shell's dithered waves show through (the hero fades into them).
    if (LocalLayoutClass.current == LayoutClass.PHONE && app.opentv.ui.LocalJaskAmbient.current != null) return this
    val fade = with(LocalDensity.current) { 520.dp.toPx() }
    return drawBehind {
        val heroBottom = if (list.firstVisibleItemIndex == 0) (heroHeightPx - list.firstVisibleItemScrollOffset).toFloat() else -fade
        drawRect(JaskPageCanvas)
        if (heroBottom + fade > 0f) drawRect(Brush.verticalGradient(
            0f to lerp(tint, JaskPageCanvas, 0.15f), 0.35f to lerp(tint, JaskPageCanvas, 0.5f), 0.7f to lerp(tint, JaskPageCanvas, 0.85f), 1f to JaskPageCanvas,
            startY = heroBottom, endY = heroBottom + fade))
    }
}

/** Wide heroes: how far the art carries on under the content below, and how far it starts below the top. */
internal val HeroOverlap = 210.dp
internal val HeroShift = 44.dp

/**
 * Reports a height [overlap] shorter than the content measures, so the content draws on under the
 * next items of a list (which are drawn later, on top). Touches only reach the reported part.
 */
internal fun Modifier.heroOverhang(overlap: androidx.compose.ui.unit.Dp) = this.then(Modifier.layout { measurable, constraints ->
    val extra = overlap.roundToPx()
    val placeable = measurable.measure(constraints.copy(maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
    layout(placeable.width, (placeable.height - extra).coerceAtLeast(0)) { placeable.place(0, 0) }
})

/** Fades this content out from [startPx] down to its bottom edge (the art under the shelf). */
internal fun Modifier.heroFadeOut(startPx: Float) = graphicsLayer {
    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
}.drawWithContent {
    drawContent()
    drawRect(Brush.verticalGradient(0f to Color.Black, 0.45f to Color.Black.copy(alpha = 0.6f), 1f to Color.Transparent,
        startY = startPx, endY = size.height), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
}

/**
 * Wide hero scrims over art that starts [shiftPx] down: a dark band above the art under the header,
 * a left scrim behind the text, a little darkening for the title near [heroPx], then [ink] (the
 * page's colour) only in the part under the next row.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.heroScrims(heroPx: Float, shiftPx: Float, ink: Color) {
    drawRect(Color(0xFF0C0B0A), size = androidx.compose.ui.geometry.Size(size.width, shiftPx + 1f))
    drawRect(Brush.verticalGradient(0f to Color(0xFF0C0B0A), 0.35f to Color.Black.copy(alpha = 0.45f), 1f to Color.Transparent,
        startY = shiftPx, endY = shiftPx + heroPx * 0.35f))
    drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.6f), 0.45f to Color.Black.copy(alpha = 0.15f), 0.7f to Color.Transparent))
    val start = heroPx * 0.55f
    val f = ((heroPx - start) / (size.height - start)).coerceIn(0.01f, 0.99f)
    drawRect(Brush.verticalGradient(0f to Color.Transparent, f to Color.Black.copy(alpha = 0.22f), 1f to ink.copy(alpha = 0.55f),
        startY = start, endY = size.height))
}

/** TMDB's largest backdrop: phones crop a wide image to a near-square hero, so w1280 looked soft. */
private fun heroArt(url: String?): String? = url?.replace("image.tmdb.org/t/p/w1280/", "image.tmdb.org/t/p/original/")

/**
 * Full-bleed art under the status bar fading into the page tint; title, chips and one inline row
 * of actions sit on the art. The art is a glass host, so the round actions blur it.
 */
@Composable
internal fun JaskHero(title: String, artUrl: String?, fallbackArt: String?, facts: HeroFacts, tint: Color, onBack: () -> Unit,
    modifier: Modifier = Modifier, blurb: String? = null, logo: String? = null, tagline: String? = null,
    /** Phone: its own controls (a full-width Play, then labelled buttons) in place of the [actions] row. */
    phoneActions: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit) {
    val wide = LocalLayoutClass.current != LayoutClass.PHONE
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val candidates = remember(artUrl, fallbackArt) { listOfNotNull(heroArt(artUrl), artUrl, fallbackArt).distinct() }
    var index by remember(candidates) { mutableIntStateOf(0) }
    val model = candidates.getOrNull(index)
    // Wide: the item reports [heroH], but the art carries on [HeroOverlap] further under the
    // content below and fades out there, over the page tint. It starts at the top: a title page has no
    // header bar, only the Back circle over the art (the home hero is the one shifted by [HeroShift]). Phone: the art fills the hero and eases into exactly `tint` at the bottom
    // edge, where jaskPageBackground picks it up.
    val density = LocalDensity.current
    val heroH = (screenH * 0.8f).coerceIn(340f, 760f).dp
    val shift = 0.dp
    val heroPx = with(density) { heroH.toPx() }
    val shiftPx = with(density) { shift.toPx() }
    val scrim: androidx.compose.ui.graphics.drawscope.DrawScope.(androidx.compose.ui.geometry.Size) -> Unit = { size ->
        if (wide) {
            val artH = heroPx - shiftPx
            drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.5f), 1f to Color.Transparent, endY = artH * 0.3f), size = size)
            drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.45f to Color.Black.copy(alpha = 0.12f),
                0.7f to Color.Transparent), size = size)
            val start = artH * 0.55f
            val f = ((artH - start) / (size.height - start)).coerceIn(0.01f, 0.99f)
            drawRect(Brush.verticalGradient(0f to Color.Transparent, f to Color.Black.copy(alpha = 0.25f), 1f to tint.copy(alpha = 0.7f),
                startY = start, endY = size.height), size = size)
        } else drawRect(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.40f), 0.18f to Color.Transparent, 0.40f to Color.Transparent,
            0.55f to tint.copy(alpha = 0.22f), 0.68f to tint.copy(alpha = 0.55f), 0.80f to tint.copy(alpha = 0.82f),
            0.90f to tint.copy(alpha = 0.95f), 1f to tint), size = size)
    }
    // The hero's buttons are glass over the art: a pre-blurred copy of it, with the same scrims.
    val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val art = app.opentv.ui.rememberArtBackdrop(model, cells = (screenW / 12).coerceIn(24, 128))
    art?.scrim = scrim
    if (!wide) {
        // Phone: the art keeps its top three quarters clear (a protected area nothing covers); the title, facts and
        // controls start over its bottom quarter, where it has faded into the page colour. That colour is the dithered
        // page's own average (the touch shell's waves), and below the hero it fades out into the waves, so there's no edge.
        val wash = app.opentv.ui.LocalJaskAmbient.current?.wash ?: tint
        val artH = (screenW / 0.92f).dp
        val overlap = artH * 0.26f
        val tail = with(density) { 280.dp.toPx() }
        val phoneScrim: androidx.compose.ui.graphics.drawscope.DrawScope.(androidx.compose.ui.geometry.Size) -> Unit = { size ->
            drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.16f to Color.Transparent, 0.62f to Color.Transparent,
                0.8f to wash.copy(alpha = 0.6f), 0.93f to wash.copy(alpha = 0.95f), 1f to wash), size = size)
        }
        art?.scrim = phoneScrim
        Box(modifier.fillMaxWidth().drawBehind {
            val artPx = artH.toPx()
            if (size.height > artPx) drawRect(wash, topLeft = androidx.compose.ui.geometry.Offset(0f, artPx - 1f),
                size = androidx.compose.ui.geometry.Size(size.width, size.height - artPx + 1f))
            drawRect(Brush.verticalGradient(listOf(wash, wash.copy(alpha = 0f)), startY = size.height - 1f, endY = size.height + tail),
                topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 1f), size = androidx.compose.ui.geometry.Size(size.width, tail))
        }) {
            Box(Modifier.fillMaxWidth().height(artH).jaskArtSource(art)) {
                model?.let {
                    AsyncImage(it, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.matchParentSize(),
                        onError = { if (index < candidates.lastIndex) index++ })
                }
                Box(Modifier.matchParentSize().drawBehind { phoneScrim(size) })
            }
            CompositionLocalProvider(app.opentv.ui.LocalJaskBackdrop provides art) {
                JaskGlassCircle(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
                    size = 44.dp, onClick = onBack)
                Column(Modifier.fillMaxWidth().padding(top = artH - overlap).padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    HeroTitle(title, logo, wide = false)
                    Spacer(Modifier.height(8.dp))
                    JaskHeroBadges(facts, center = true)
                    Spacer(Modifier.height(14.dp))
                    if (phoneActions != null) Column(Modifier.fillMaxWidth().widthIn(max = 560.dp)) { phoneActions() }
                    else Row(Modifier.fillMaxWidth().widthIn(max = 560.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically, content = actions)
                }
            }
        }
        return
    }
    Box(modifier.fillMaxWidth().then(if (wide) Modifier.heroOverhang(HeroOverlap).height(heroH + HeroOverlap) else Modifier.aspectRatio(0.92f))) {
        Box(Modifier.matchParentSize().then(if (wide) Modifier.heroFadeOut(heroPx - with(density) { 24.dp.toPx() }).drawBehind {
            drawRect(Color(0xFF0C0B0A), size = androidx.compose.ui.geometry.Size(size.width, shiftPx + 1f))
        } else Modifier)) {
            Box(Modifier.matchParentSize().padding(top = shift).jaskArtSource(art)) {
                model?.let {
                    AsyncImage(it, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.matchParentSize(),
                        onError = { if (index < candidates.lastIndex) index++ })
                }
                Box(Modifier.matchParentSize().drawBehind { scrim(size) })
            }
        }
        CompositionLocalProvider(app.opentv.ui.LocalJaskBackdrop provides art) {
        JaskGlassCircle(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
            size = 44.dp, onClick = onBack)
        if (wide) Box(Modifier.fillMaxWidth().height(heroH)) {
            // Wide screens: title, badges and blurb in a left column; the actions sit on the right,
            // centred against that column.
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 44.dp, end = 44.dp, bottom = 28.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Column(Modifier.widthIn(max = 620.dp)) {
                        HeroTitle(title, logo, wide = true)
                        if (!tagline.isNullOrBlank()) Text(tagline, color = Color(0xFFD8D8D8), style = MaterialTheme.typography.titleSmall,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp))
                        Spacer(Modifier.height(10.dp))
                        JaskHeroBadges(facts, center = false)
                        if (!blurb.isNullOrBlank()) {
                            var expanded by remember { mutableStateOf(false) }
                            Spacer(Modifier.height(12.dp))
                            Text(blurb, color = Color(0xFFE2E2E2), style = MaterialTheme.typography.bodyMedium,
                                maxLines = if (expanded) 12 else 3, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.animateContentSize().focusProperties { canFocus = false }.clickable { expanded = !expanded })
                        }
                        // The buttons sit under the blurb, in the same column (not floating over the art).
                        // TV: Left/Right stop at the row's ends (Netflix); nothing beside them, so a search would fall diagonally.
                        Spacer(Modifier.height(18.dp))
                        Row(Modifier.width(520.dp).then(if (LocalLayoutClass.current == LayoutClass.TV) Modifier.jaskRowEnds() else Modifier),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically, content = actions)
                    }
                }
            }
        } else Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            HeroTitle(title, logo, wide = false)
            Spacer(Modifier.height(8.dp))
            JaskHeroBadges(facts, center = true)
            Spacer(Modifier.height(14.dp))
            if (phoneActions != null) Column(Modifier.fillMaxWidth().widthIn(max = 560.dp)) { phoneActions() }
            else Row(Modifier.fillMaxWidth().widthIn(max = 560.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}
}

/** The title as its logo art (TMDB), or as text when there's none or it doesn't load. */
@Composable
private fun HeroTitle(title: String, logo: String?, wide: Boolean) {
    var failed by remember(logo) { mutableStateOf(false) }
    // While the logo loads, the title as text in the logo's space, so the page never opens without a name.
    if (logo != null && !failed) coil.compose.SubcomposeAsyncImage(logo, title, contentScale = ContentScale.Fit,
        alignment = if (wide) Alignment.BottomStart else Alignment.BottomCenter, onError = { failed = true },
        loading = { Box(Modifier.fillMaxSize(), contentAlignment = if (wide) Alignment.BottomStart else Alignment.BottomCenter) {
            Text(title, color = Color.White, style = if (wide) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = if (wide) TextAlign.Start else TextAlign.Center) } },
        modifier = Modifier.padding(horizontal = if (wide) 0.dp else 24.dp).widthIn(max = if (wide) 460.dp else 320.dp).height(if (wide) 140.dp else 96.dp))
    else if (wide) Text(title, color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
    else Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
}

/**
 * What a hero shows about a title, drawn as badges: trending rank, quality, network logo, rating,
 * then plain facts (year, runtime, status). [noSource] marks titles no enabled source has.
 */
internal data class HeroFacts(
    val rank: Int? = null, val rating: Double? = null, val quality: String? = null, val networkLogo: String? = null,
    val network: String? = null, val text: List<String> = emptyList(), val noSource: Boolean = false, val caption: String? = null,
    /** "Coming 17 Jul" (see HomeTitle.comingSoon): shown instead of "Not on your sources". */
    val comingSoon: String? = null,
    /** Critics and audiences, on their own line: ("IMDb", "7.3"), ("RT", "92%"), ("MC", "72"). */
    val scores: List<Pair<String, String>> = emptyList(),
    /** Who [rating] comes from, named on its chip. */
    val ratingSource: String = "TMDB",
)

/** The hero's score line from OMDb's answer. */
internal fun scoreBadges(s: app.opentv.catalogue.ReviewScores?): List<Pair<String, String>> = listOfNotNull(
    s?.imdb?.let { "IMDb" to it }, s?.rottenTomatoes?.let { "RT" to it }, s?.metacritic?.let { "MC" to it })

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JaskHeroBadges(facts: HeroFacts, center: Boolean) {
    Column(horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
    FactBadges(facts, center)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FactBadges(facts: HeroFacts, center: Boolean) {
    // One line of plain text, wrapping only when it must: no bubbles. Small tags name what needs naming (4K, and
    // where each rating comes from); a dot separates the rest.
    val strong = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
    val plain = MaterialTheme.typography.labelLarge
    val parts = buildList<@Composable () -> Unit> {
        facts.caption?.let { add { Text(it, color = Color.White, style = strong) } }
        facts.rank?.let { rank -> add { Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.TrendingUp, null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp)); Text("#$rank this week", color = Color.White, style = strong) } } }
        if (facts.comingSoon != null) add { Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(androidx.compose.material.icons.Icons.Outlined.Schedule, null, tint = Color.White, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp)); Text(facts.comingSoon, color = Color.White, style = strong) } }
        else if (facts.noSource) add { Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, null, tint = Color(0xFFFFC7BD), modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.jask_not_on_sources), color = Color(0xFFFFDDD6), style = plain) } }
        val logo = facts.networkLogo?.takeIf(String::isNotBlank)
        // The network's name stands in while its logo loads (or if it fails), so the line never shows an empty gap.
        if (logo != null) add { coil.compose.SubcomposeAsyncImage(logo, facts.network, contentScale = ContentScale.Fit,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White), modifier = Modifier.height(14.dp).widthIn(max = 64.dp),
            loading = { facts.network?.let { Text(it, color = Color(0xFFEDEDED), style = plain, maxLines = 1) } },
            error = { facts.network?.let { Text(it, color = Color(0xFFEDEDED), style = plain, maxLines = 1) } }) }
        else facts.network?.let { n -> add { Text(n, color = Color(0xFFEDEDED), style = plain) } }
        facts.quality?.takeIf(String::isNotBlank)?.let { q -> add { Text(q, color = Color.White, style = strong.copy(letterSpacing = 0.4.sp),
            modifier = Modifier.border(1.dp, Color.White.copy(alpha = .85f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp)) } }
        facts.text.forEach { t -> add { Text(t, color = Color(0xFFEDEDED), style = plain, maxLines = 1) } }
    }
    JaskDotLine(parts.size, center, { Text("  ·  ", color = Color(0xFF9E9E9E), style = plain) }) { parts[it]() }
    // The ratings on a line of their own, each named by its source's tag: TMDB, IMDb, Rotten Tomatoes, Metacritic.
    val ratings = listOfNotNull(facts.rating?.takeIf { it > 0 }?.let { facts.ratingSource to String.format(java.util.Locale.getDefault(), "%.1f", it) }) + facts.scores
    if (ratings.isNotEmpty()) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ratings.forEach { (source, value) -> Row(verticalAlignment = Alignment.CenterVertically) {
            val (bg, ink) = when (source) { "IMDb" -> Color(0xFFF5C518) to Color.Black; "RT" -> Color(0xFFFA320A) to Color.White
                "TMDB" -> Color(0xFF01B4E4) to Color.Black; "MC" -> Color(0xFF66CC33) to Color.Black
                else -> Color(0xFFB8B8B8) to Color.Black }
            Text(source, color = ink, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black,
                modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(bg).padding(horizontal = 4.dp))
            Spacer(Modifier.width(6.dp)); Text(value, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        } }
    }
}

@Composable
private fun HeroBadge(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.height(28.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.38f))
        .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(50)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, content = content)
}

/** The phone hero's second row of controls: a small glass button with its name underneath. */
@Composable
internal fun JaskActionTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        app.opentv.ui.JaskGlassCircle(icon, label, active = active, size = 44.dp, onClick = onClick)
        Text(label, color = Color(0xFFD6D6D6), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

/** A labelled tile holding any control (the download button keeps its own progress ring). */
@Composable
internal fun JaskActionSlot(label: String, control: @Composable () -> Unit) {
    Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { control() }
        Text(label, color = Color(0xFFD6D6D6), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

/** White play pill sized for the inline hero row; resume progress runs along its bottom edge. */
@Composable
internal fun RowScope.JaskPlayPill(label: String, progress: Float? = null, onClick: () -> Unit) {
    // TV: a title's page opens with focus on its primary action, as on Netflix (not on the back arrow in the corner).
    val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    // Once per visit to the page: the list re-creates the hero (off screen) while the d-pad searches below, and
    // grabbing again then pulled focus back up to Play. The flag belongs to the screen (JaskTheme), so it outlives
    // that re-creation but resets when you come Back to the page (which then lands on Play, or on the cast member).
    val grabbed = LocalPlayGrabbed.current ?: androidx.compose.runtime.remember { mutableStateOf(false) }
    if (app.opentv.ui.LocalLayoutClass.current == app.opentv.ui.LayoutClass.TV && !grabbed.value) androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80); grabbed.value = true; runCatching { focus.requestFocus() }
    }
    Box(Modifier.weight(1f).jaskFocusLift().focusRequester(focus).height(48.dp).clip(RoundedCornerShape(50))
        .jaskWhiteFill(RoundedCornerShape(50)).clickable(onClick = onClick)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (progress != null && progress > 0f) Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 22.dp, vertical = 6.dp)
            .fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.Black.copy(alpha = 0.15f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Color.Black.copy(alpha = 0.7f)))
        }
    }
}

/** Glass source chooser for the hero row: what plays now ("4K", provider), and how many choices. */
@Composable
internal fun JaskSourceButton(label: String, count: Int, onClick: () -> Unit) {
    JaskGlassSurface(Modifier.jaskFocusLift().height(48.dp).widthIn(max = 150.dp), RoundedCornerShape(50), showFocus = true) {
        Row(Modifier.fillMaxHeight().clickable(onClick = onClick).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Layers, null, tint = Color(0xFFF0F0F0), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            // The count always, so every title page reads the same ("Sources · 1" too).
            if (count > 0) Text(" · $count", color = Color(0xFFCFCFCF), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Glass circle holding the download control (icon, progress ring or done tick). */
@Composable
internal fun JaskGlassDownload(control: @Composable () -> Unit) {
    JaskGlassSurface(Modifier.size(48.dp), CircleShape, showFocus = true) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color(0xFFF0F0F0)) { control() }
        }
    }
}

/** Page side margin: wider on tablet/desktop/TV so content lines up with the hero's text column. */
@Composable
internal fun jaskSide() = if (LocalLayoutClass.current != LayoutClass.PHONE) 44.dp else 16.dp

private val CompanyWords = Regex("""\b(Pictures|Productions?|Entertainment|Studios?|Films?|Media|Company|Corporation|Inc\.?|Ltd\.?|LLC|Television|Animation|Releasing|Distribution)\b""", RegexOption.IGNORE_CASE)

/** Genres, cast and director as quiet text lines instead of chip rails. */
@Composable
internal fun JaskCredits(genre: String?, cast: String?, director: String?) {
    val lines = listOfNotNull(
        genre?.split(',', '/', '|')?.map(String::trim)?.filter(String::isNotEmpty)?.distinct()?.takeIf { it.isNotEmpty() }?.joinToString(" · "),
        cast?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.take(6)?.takeIf { it.isNotEmpty() }?.let { "Starring " + it.joinToString(", ") },
        // Providers sometimes put the studios in the director field ("Columbia Pictures, Marvel Entertainment"): people only.
        director?.split(',')?.map(String::trim)?.filter { it.isNotEmpty() && !CompanyWords.containsMatchIn(it) }?.takeIf { it.isNotEmpty() }
            ?.let { "Directed by " + it.joinToString(", ") },
    )
    if (lines.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = jaskSide(), vertical = 4.dp).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { Text(it, color = Color(0xFFB8B8B8), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/** Three lines, tap to expand. */
@Composable
internal fun JaskSynopsis(text: String?) {
    if (text.isNullOrBlank()) return
    var expanded by remember { mutableStateOf(false) }
    Text(text, color = Color(0xFFE6E6E6), style = MaterialTheme.typography.bodyMedium,
        maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().animateContentSize().focusProperties { canFocus = false }.clickable { expanded = !expanded }.padding(horizontal = jaskSide(), vertical = 12.dp).widthIn(max = 900.dp))
}

internal data class SeasonSummary(val season: Int, val episodes: Int, val watched: Int, val poster: String?)

private fun seasonName(season: Int) = if (season == 0) "Specials" else "Season $season"

/**
 * Seasons side by side (poster, name, progress); the selected one's episodes list underneath, so
 * reaching season 4 is one tap instead of scrolling past three seasons. A single season is one line.
 */
@Composable
internal fun JaskSeasonStrip(seasons: List<SeasonSummary>, selected: Int, fallbackPoster: String?, onSelect: (Int) -> Unit) {
    // One season is a card like any other: it opens and closes its episodes the same way.
    val state = rememberLazyListState()
    LaunchedEffect(seasons.size) {
        val index = seasons.indexOfFirst { it.season == selected }
        if (index > 1) state.scrollToItem(index - 1)
    }
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = jaskSide(), vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(seasons, key = { it.season }) { s ->
            val isSelected = s.season == selected
            Column(Modifier.jaskFocusLift(1.06f).width(76.dp).clip(RoundedCornerShape(8.dp)).clickable { onSelect(s.season) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2826))
                    .then(if (isSelected) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)) {
                    val art = s.poster ?: fallbackPoster
                    if (art != null) AsyncImage(art, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()
                        .graphicsLayer { alpha = if (isSelected) 1f else 0.6f })
                    if (s.poster == null) Text(if (s.season == 0) "SP" else "S${s.season}", color = Color.White, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp))
                    if (s.watched > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(s.watched.toFloat() / s.episodes.coerceAtLeast(1)).background(Color.White))
                    }
                }
                Text(seasonName(s.season), color = if (isSelected) Color.White else Color(0xFFB0B0B0), style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, modifier = Modifier.padding(top = 5.dp))
                Text(if (s.watched >= s.episodes && s.episodes > 0) "Watched" else if (s.watched > 0) "${s.watched}/${s.episodes} watched" else "${s.episodes} episodes",
                    color = Color(0xFF9A9A9A), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

/**
 * Wide screens: seasons as a vertical list in a side column next to the episode grid (a strip
 * across a landscape screen would hold a few posters and leave the rest empty).
 */
@Composable
internal fun JaskSeasonList(seasons: List<SeasonSummary>, selected: Int, fallbackPoster: String?, modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        seasons.forEach { s ->
            val isSelected = s.season == selected
            Row(Modifier.fillMaxWidth().jaskFocusLift(1.03f).clip(RoundedCornerShape(10.dp))
                .background(if (isSelected) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                .clickable { onSelect(s.season) }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(40.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(5.dp)).background(Color(0xFF2A2826))) {
                    (s.poster ?: fallbackPoster)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()
                        .graphicsLayer { alpha = if (isSelected) 1f else 0.6f }) }
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(seasonName(s.season), color = if (isSelected) Color.White else Color(0xFFC4C4C4), style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                    Text(if (s.watched >= s.episodes && s.episodes > 0) "Watched" else if (s.watched > 0) "${s.watched}/${s.episodes} watched" else "${s.episodes} episodes",
                        color = Color(0xFF9A9A9A), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    if (s.watched > 0) Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.2f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(s.watched.toFloat() / s.episodes.coerceAtLeast(1)).background(Color.White))
                    }
                }
            }
        }
    }
}

/** Episode: still with progress, number + title, runtime and two lines of plot. */
@Composable
internal fun JaskEpisodeRow(ep: Episode, seriesTitle: String, position: PlaybackPosition?, isNext: Boolean,
    onPlay: () -> Unit, modifier: Modifier = Modifier, cell: Boolean = false,
    /** Hold (press and hold on a phone): the episode's other copies, to play or save one. */
    onLongPress: (() -> Unit)? = null,
    /** Shows its season too ("S3 E5 · Memory"), where episodes of several seasons are listed together. */
    withSeason: Boolean = false, trailing: @Composable () -> Unit) {
    val title = remember(ep.title, seriesTitle) { cleanEpisodeTitle(episodeDisplayTitle(ep.title, seriesTitle), ep.episodeNumber) }
    val hold = remember { androidx.compose.animation.core.Animatable(0f) }
    // cell = one tile of the wide two-column episode grid (rounded, own padding) instead of a full-width row.
    Row(modifier.jaskFocusLift(1.02f).fillMaxWidth().then(if (cell) Modifier.clip(RoundedCornerShape(12.dp)) else Modifier)
        .then(if (onLongPress == null) Modifier.clickable(onClick = onPlay) else Modifier.jaskHold(hold, onPlay, onLongPress))
        .background(if (cell) Color.White.copy(alpha = 0.03f) else Color.Transparent)
        .padding(horizontal = if (cell) 10.dp else jaskSide(), vertical = if (cell) 10.dp else 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(if (cell) 136.dp else 132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2826))) {
            if (ep.stillUrl != null) AsyncImage(ep.stillUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text("E${ep.episodeNumber}", color = Color(0xFF9E9E9E), modifier = Modifier.align(Alignment.Center))
            if (position?.isFinished == true) Icon(Icons.Filled.CheckCircle, "Watched", tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape))
            val progress = position?.takeIf { !it.isFinished }?.let { if (it.durationMillis > 0) it.positionMillis.toFloat() / it.durationMillis else 0f }
            // Holding the episode: a ring fills on its still, then its menu opens.
            if (hold.value > 0f) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .35f)), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(progress = { hold.value }, modifier = Modifier.size(34.dp), color = Color.White,
                    strokeWidth = 3.dp, trackColor = Color.White.copy(alpha = .25f))
            }
            if (progress != null && progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.3f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Color.White))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (withSeason) "S${ep.season} E${ep.episodeNumber} · $title" else "${ep.episodeNumber}. $title", color = Color.White, style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(ep.durationSeconds?.takeIf { it > 0 }?.let { "${(it + 30) / 60} min" },
                when { position?.isFinished == true -> "Watched"; isNext -> "Up next"; else -> null }).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, color = Color(0xFFBDBDBD), style = MaterialTheme.typography.labelMedium)
            ep.plot?.takeIf(String::isNotBlank)?.let {
                Text(it, color = Color(0xFFA8A8A8), style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        trailing()
    }
}

/**
 * Items joined by [dot], wrapping like a FlowRow, but a dot only ever sits between two items on the same line:
 * never left hanging at a line's end or starting the next.
 */
@Composable
internal fun JaskDotLine(count: Int, center: Boolean, dot: @Composable () -> Unit, item: @Composable (Int) -> Unit) {
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    androidx.compose.ui.layout.Layout({
        repeat(count) { i -> Box { item(i) } }
        repeat((count - 1).coerceAtLeast(0)) { Box { dot() } }
    }) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val items = measurables.take(count).map { it.measure(loose) }
        val dots = measurables.drop(count).map { it.measure(loose) }
        val max = constraints.maxWidth
        // Lines of (item index, with a dot before it).
        val lines = mutableListOf(mutableListOf<Pair<Int, Boolean>>())
        var x = 0
        items.forEachIndexed { i, p ->
            val line = lines.last()
            val dotW = if (line.isEmpty()) 0 else dots[i - 1].width
            if (line.isNotEmpty() && x + dotW + p.width > max) { lines += mutableListOf(i to false); x = p.width }
            else { line += i to line.isNotEmpty(); x += dotW + p.width }
        }
        fun lineW(l: List<Pair<Int, Boolean>>) = l.sumOf { (i, d) -> items[i].width + if (d) dots[i - 1].width else 0 }
        fun lineH(l: List<Pair<Int, Boolean>>) = l.maxOfOrNull { (i, d) -> maxOf(items[i].height, if (d) dots[i - 1].height else 0) } ?: 0
        val width = if (center) max.coerceAtMost(lines.maxOf { lineW(it) }.coerceAtLeast(constraints.minWidth)).let { if (constraints.hasBoundedWidth) max else it }
            else lines.maxOf { lineW(it) }.coerceIn(constraints.minWidth, max)
        val height = lines.sumOf { lineH(it) } + gap * (lines.size - 1).coerceAtLeast(0)
        layout(width, height) {
            var y = 0
            lines.forEach { l ->
                val h = lineH(l)
                var cx = if (center) (width - lineW(l)) / 2 else 0
                l.forEach { (i, d) ->
                    if (d) { val dp = dots[i - 1]; dp.place(cx, y + (h - dp.height) / 2); cx += dp.width }
                    val p = items[i]; p.place(cx, y + (h - p.height) / 2); cx += p.width
                }
                y += h + gap
            }
        }
    }
}

/** "Silo (2023) (US)" → "Silo": provider decoration after the cleaned name (year, region/quality tags). */
internal fun cleanShowTitle(raw: String): String {
    val cleaned = app.opentv.data.parser.VodTitleCleaner.clean(raw)
    val stripped = cleaned.replace(Regex("""(\s*[(\[]\s*(?:(?:19|20)\d{2}|[A-Za-z]{2,3}|4K|8K|UHD|FHD|HD|SD|MULTI)\s*[)\]])+\s*$""", RegexOption.IGNORE_CASE), "").trim()
    return stripped.ifBlank { cleaned }
}

/** Drops provider prefixes like "(2025) (DE) - S01E01 - " so only the episode's own name remains. */
internal fun cleanEpisodeTitle(raw: String, number: Int): String {
    val afterCode = Regex("""(?i)^.*?\bS\d{1,3}\s*E\d{1,4}\b\s*[-–:|.]*\s*""").replace(raw, "")
    val cleaned = afterCode.replace(Regex("""^(\(\w{2,4}\)\s*|\(\d{4}\)\s*|[-–:|]\s*)+"""), "").trim()
    return cleaned.ifBlank { "Episode $number" }
}

/**
 * The episode the show button should offer: an unfinished episode most recently watched, else the
 * one after the most recently finished, else the first episode.
 */
internal fun nextEpisode(ordered: List<Episode>, positions: List<PlaybackPosition>): Pair<Episode, PlaybackPosition?>? {
    if (ordered.isEmpty()) return null
    val byKey = positions.associateBy { it.mediaKey }
    val latest = positions.maxByOrNull { it.updatedAtMillis } ?: return ordered.first() to null
    val index = ordered.indexOfFirst { "ep:${it.id}" == latest.mediaKey }
    if (index < 0) return ordered.first() to null
    if (!latest.isFinished) return ordered[index] to latest
    val next = ordered.getOrNull(index + 1) ?: return ordered[index] to latest
    return next to byKey["ep:${next.id}"]
}

/**
 * Wide movie page body: the sources this title can play from (tap to switch) beside the details
 * (genres, cast and director as tappable names, review scores in [extra]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JaskMovieBody(sources: List<SourceOption>, selected: Int, onSelect: (Int) -> Unit, genre: String?, cast: String?,
    director: String?, onOpenPerson: (String) -> Unit, extra: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = jaskSide(), end = jaskSide(), top = 12.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        // The film page lists its sources in the Sources panel above (with torrents): none here then.
        if (sources.isNotEmpty()) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            JaskSectionTitle(if (sources.size == 1) "Source" else "Sources · ${sources.size}")
            sources.forEachIndexed { i, option ->
                val on = i == selected
                Row(Modifier.fillMaxWidth().jaskFocusLift(1.02f).clip(RoundedCornerShape(12.dp))
                    .background(if (on) Color.White.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.03f))
                    .clickable { onSelect(i) }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (on) Icons.Filled.CheckCircle else Icons.Filled.Layers, null, tint = if (on) Color.White else Color(0xFF9A9A9A),
                        modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(option.provider, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (option.detail.isNotBlank()) Text(option.detail, color = Color(0xFFA8A8A8), style = MaterialTheme.typography.labelMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (on) Text("Playing", color = Color(0xFFCFCFCF), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val genres = genre?.split(',', '/', '|')?.map(String::trim)?.filter(String::isNotEmpty)?.distinct().orEmpty()
            val people = cast?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.take(8).orEmpty()
            val directors = director?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
            if (genres.isNotEmpty() || people.isNotEmpty() || directors.isNotEmpty()) JaskSectionTitle("Details")
            if (genres.isNotEmpty()) Text(genres.joinToString(" · "), color = Color(0xFFCFCFCF), style = MaterialTheme.typography.bodyMedium)
            if (people.isNotEmpty()) {
                Text("Cast", color = Color(0xFF9A9A9A), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    people.forEach { JaskPersonPill(it) { onOpenPerson(it) } }
                }
            }
            if (directors.isNotEmpty()) {
                Text("Director", color = Color(0xFF9A9A9A), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    directors.forEach { JaskPersonPill(it) { onOpenPerson(it) } }
                }
            }
            extra()
        }
    }
}

@Composable
internal fun JaskSectionTitle(text: String) {
    Text(text, color = Color(0xFFF4F4F4), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 2.dp))
}

@Composable
private fun JaskPersonPill(name: String, onClick: () -> Unit) {
    Text(name, color = Color(0xFFEDEDED), style = MaterialTheme.typography.labelLarge, maxLines = 1,
        modifier = Modifier.jaskFocusLift(1.06f).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp))
}

/** A titled, sideways-scrolling row of posters across a wide page. */
@Composable
internal fun <T> JaskShelf(title: String, items: List<T>, key: (T) -> Any, returnTo: String? = null,
    card: @Composable (T) -> Unit) {
    val returnAtEntry = remember { returnTo }
    Column(Modifier.fillMaxWidth().padding(top = 28.dp)) {
        Box(Modifier.padding(horizontal = jaskSide())) { JaskSectionTitle(title) }
        Spacer(Modifier.height(8.dp))
        // Down from the row above lands on the card beneath it (plain focus search); Left/Right stop at the ends.
        LazyRow(Modifier.jaskRowStops(), contentPadding = PaddingValues(horizontal = jaskSide()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(items, key = { _, it -> key(it) }) { _, it ->
                val k = key(it).toString()
                // The caller's click records this card (onReturnTo) so Back lands on it (TV).
                Box(Modifier.width(150.dp).jaskReturnFocus(k, returnTo, returnAtEntry)) { card(it) }
            }
        }
    }
}

/**
 * Tap plays, hold opens a menu: D-pad centre or Enter held (TV), or a press held (touch). [progress] runs 0 → 1 while
 * held, for the ring; released early it's a tap.
 */
@Composable
internal fun Modifier.jaskHold(progress: androidx.compose.animation.core.Animatable<Float, *>, onTap: () -> Unit, onHold: () -> Unit): Modifier {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val tap by androidx.compose.runtime.rememberUpdatedState(onTap)
    val held by androidx.compose.runtime.rememberUpdatedState(onHold)
    val state = remember { object { var job: kotlinx.coroutines.Job? = null; var fired = false } }
    // The same focus wash and ring as clickable rows (JaskIndication), so the highlight follows the d-pad.
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    fun start() {
        state.fired = false; state.job?.cancel()
        state.job = scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, androidx.compose.animation.core.tween(600, easing = androidx.compose.animation.core.LinearEasing))
            state.fired = true; progress.snapTo(0f); held()
        }
    }
    fun release(): Boolean { val f = state.fired; state.job?.cancel(); state.job = null; scope.launch { progress.snapTo(0f) }; return f }
    return this.onPreviewKeyEvent { e ->
        val k = e.key
        if (k != androidx.compose.ui.input.key.Key.DirectionCenter && k != androidx.compose.ui.input.key.Key.Enter &&
            k != androidx.compose.ui.input.key.Key.NumPadEnter) return@onPreviewKeyEvent false
        when (e.type) {
            androidx.compose.ui.input.key.KeyEventType.KeyDown -> {
                when {
                    e.nativeKeyEvent.repeatCount == 0 -> start()
                    // The system's own long press (about half a second): that's the hold, even if the ring isn't full.
                    e.nativeKeyEvent.isLongPress && !state.fired -> { state.job?.cancel(); state.fired = true; scope.launch { progress.snapTo(0f) }; held() }
                }
                true
            }
            androidx.compose.ui.input.key.KeyEventType.KeyUp -> { if (!release()) tap(); true }
            else -> false
        }
    }.pointerInput(Unit) {
        detectTapGestures(onPress = {
            start()
            val up = tryAwaitRelease()
            if (!release() && up) tap()
        })
    }.indication(source, app.opentv.ui.JaskIndication).focusable(interactionSource = source)
}
