package app.opentv.ui.vod

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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import app.opentv.ui.JaskFrostSurface
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

/** A dark colour sampled from the artwork, animated in once decoded; canvas until then. */
@Composable
internal fun rememberArtworkTint(url: String?): Color {
    val context = LocalContext.current
    var target by remember(url) { mutableStateOf(JaskPageCanvas) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(url).size(48).allowHardware(false).build()
        val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
            ?: return@LaunchedEffect
        target = withContext(Dispatchers.Default) { artworkTint(bitmap) }
    }
    return animateColorAsState(target, tween(500), label = "artworkTint").value
}

/** Most weighted hue bucket (by saturation × brightness), darkened to a background tone. */
private fun artworkTint(bitmap: Bitmap): Color {
    val buckets = Array(12) { FloatArray(4) } // weight, r, g, b
    val hsv = FloatArray(3)
    val stepX = maxOf(1, bitmap.width / 32); val stepY = maxOf(1, bitmap.height / 32)
    for (y in 0 until bitmap.height step stepY) for (x in 0 until bitmap.width step stepX) {
        val c = bitmap.getPixel(x, y)
        android.graphics.Color.colorToHSV(c, hsv)
        if (hsv[2] < 0.12f || hsv[2] > 0.97f && hsv[1] < 0.1f) continue
        val w = hsv[1] * hsv[2] + 0.02f
        val b = buckets[((hsv[0] / 30f).toInt()).coerceIn(0, 11)]
        b[0] += w; b[1] += w * android.graphics.Color.red(c); b[2] += w * android.graphics.Color.green(c); b[3] += w * android.graphics.Color.blue(c)
    }
    val best = buckets.maxByOrNull { it[0] }?.takeIf { it[0] > 0f } ?: return JaskPageCanvas
    val avg = android.graphics.Color.rgb((best[1] / best[0]).toInt(), (best[2] / best[0]).toInt(), (best[3] / best[0]).toInt())
    android.graphics.Color.colorToHSV(avg, hsv)
    hsv[1] = hsv[1].coerceAtMost(0.6f)
    hsv[2] = 0.30f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * The page behind a media page: the artwork tint continues from the hero's bottom edge (where the
 * hero's own gradient ends in exactly this colour) and eases into the canvas over [fadeLength].
 * Positioned in absolute pixels against the hero, so there is no seam while scrolling.
 */
@Composable
internal fun Modifier.jaskPageBackground(tint: Color, list: LazyListState, heroHeightPx: Int): Modifier {
    val fade = with(LocalDensity.current) { 520.dp.toPx() }
    return drawBehind {
        val heroBottom = if (list.firstVisibleItemIndex == 0) (heroHeightPx - list.firstVisibleItemScrollOffset).toFloat() else -fade
        drawRect(JaskPageCanvas)
        if (heroBottom + fade > 0f) drawRect(Brush.verticalGradient(
            0f to tint, 0.35f to lerp(tint, JaskPageCanvas, 0.45f), 0.7f to lerp(tint, JaskPageCanvas, 0.85f), 1f to JaskPageCanvas,
            startY = heroBottom, endY = heroBottom + fade))
    }
}

/** TMDB's largest backdrop: phones crop a wide image to a near-square hero, so w1280 looked soft. */
private fun heroArt(url: String?): String? = url?.replace("image.tmdb.org/t/p/w1280/", "image.tmdb.org/t/p/original/")

/**
 * Full-bleed art under the status bar fading into the page tint; title, chips and one inline row
 * of actions sit on the art. The art is a glass host, so the round actions blur it.
 */
@Composable
internal fun JaskHero(title: String, artUrl: String?, fallbackArt: String?, chips: List<String>, tint: Color, onBack: () -> Unit,
    modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit) {
    val wide = LocalLayoutClass.current != LayoutClass.PHONE
    JaskGlassHost(modifier.fillMaxWidth().then(if (wide) Modifier.height(480.dp) else Modifier.aspectRatio(0.92f)), background = {
        val candidates = remember(artUrl, fallbackArt) { listOfNotNull(heroArt(artUrl), artUrl, fallbackArt).distinct() }
        var index by remember(candidates) { mutableIntStateOf(0) }
        candidates.getOrNull(index)?.let { model ->
            AsyncImage(model, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.matchParentSize(),
                onError = { if (index < candidates.lastIndex) index++ })
        }
        // Eased ramp into exactly `tint` at the bottom edge, where jaskPageBackground picks it up.
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.40f), 0.18f to Color.Transparent, 0.40f to Color.Transparent,
            0.55f to tint.copy(alpha = 0.22f), 0.68f to tint.copy(alpha = 0.55f), 0.80f to tint.copy(alpha = 0.82f),
            0.90f to tint.copy(alpha = 0.95f), 1f to tint)))
    }) {
        JaskGlassCircle(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
            size = 44.dp, onClick = onBack)
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
            if (chips.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) { chips.forEach { JaskMetaChip(it) } }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().widthIn(max = 560.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** White play pill sized for the inline hero row; resume progress runs along its bottom edge. */
@Composable
internal fun RowScope.JaskPlayPill(label: String, progress: Float? = null, onClick: () -> Unit) {
    Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(50)).background(Color.White).clickable(onClick = onClick)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = Color.Black, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
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
    JaskFrostSurface(Modifier.height(48.dp).widthIn(max = 150.dp), RoundedCornerShape(50)) {
        Row(Modifier.fillMaxHeight().clickable(onClick = onClick).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Layers, null, tint = Color(0xFFF0F0F0), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (count > 1) Text(" · $count", color = Color(0xFFCFCFCF), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Glass circle holding the download control (icon, progress ring or done tick). */
@Composable
internal fun JaskGlassDownload(control: @Composable () -> Unit) {
    JaskFrostSurface(Modifier.size(48.dp), CircleShape) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color(0xFFF0F0F0)) { control() }
        }
    }
}

/** Genres, cast and director as quiet text lines instead of chip rails. */
@Composable
internal fun JaskCredits(genre: String?, cast: String?, director: String?) {
    val lines = listOfNotNull(
        genre?.split(',', '/', '|')?.map(String::trim)?.filter(String::isNotEmpty)?.distinct()?.takeIf { it.isNotEmpty() }?.joinToString(" · "),
        cast?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.take(6)?.takeIf { it.isNotEmpty() }?.let { "Starring " + it.joinToString(", ") },
        director?.takeIf(String::isNotBlank)?.let { "Directed by $it" },
    )
    if (lines.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { Text(it, color = Color(0xFFB8B8B8), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun JaskMetaChip(text: String) {
    Text(text, color = Color(0xFFEDEDED), style = MaterialTheme.typography.labelMedium, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp))
}

/** Three lines, tap to expand. */
@Composable
internal fun JaskSynopsis(text: String?) {
    if (text.isNullOrBlank()) return
    var expanded by remember { mutableStateOf(false) }
    Text(text, color = Color(0xFFE6E6E6), style = MaterialTheme.typography.bodyMedium,
        maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().animateContentSize().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 12.dp))
}

internal data class SeasonSummary(val season: Int, val episodes: Int, val watched: Int, val poster: String?)

private fun seasonName(season: Int) = if (season == 0) "Specials" else "Season $season"

/**
 * Seasons side by side (poster, name, progress); the selected one's episodes list underneath, so
 * reaching season 4 is one tap instead of scrolling past three seasons. A single season is one line.
 */
@Composable
internal fun JaskSeasonStrip(seasons: List<SeasonSummary>, selected: Int, fallbackPoster: String?, onSelect: (Int) -> Unit) {
    if (seasons.size == 1) {
        val only = seasons.first()
        Text("${seasonName(only.season)} · ${only.episodes} episodes", color = Color.White, style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        return
    }
    val state = rememberLazyListState()
    LaunchedEffect(seasons.size) {
        val index = seasons.indexOfFirst { it.season == selected }
        if (index > 1) state.scrollToItem(index - 1)
    }
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(seasons, key = { it.season }) { s ->
            val isSelected = s.season == selected
            Column(Modifier.width(76.dp).clip(RoundedCornerShape(8.dp)).clickable { onSelect(s.season) }) {
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

/** Episode: still with progress, number + title, runtime and two lines of plot. */
@Composable
internal fun JaskEpisodeRow(ep: Episode, seriesTitle: String, position: PlaybackPosition?, isNext: Boolean,
    onPlay: () -> Unit, trailing: @Composable () -> Unit) {
    val title = remember(ep.title, seriesTitle) { cleanEpisodeTitle(episodeDisplayTitle(ep.title, seriesTitle), ep.episodeNumber) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onPlay)
        .background(if (isNext) Color.White.copy(alpha = 0.06f) else Color.Transparent)
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2826))) {
            if (ep.stillUrl != null) AsyncImage(ep.stillUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text("E${ep.episodeNumber}", color = Color(0xFF9E9E9E), modifier = Modifier.align(Alignment.Center))
            if (position?.isFinished == true) Icon(Icons.Filled.CheckCircle, "Watched", tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape))
            val progress = position?.takeIf { !it.isFinished }?.let { if (it.durationMillis > 0) it.positionMillis.toFloat() / it.durationMillis else 0f }
            if (progress != null && progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.3f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Color.White))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${ep.episodeNumber}. $title", color = Color.White, style = MaterialTheme.typography.titleSmall,
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
