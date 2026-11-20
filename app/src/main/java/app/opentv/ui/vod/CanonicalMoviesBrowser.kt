package app.opentv.ui.vod

import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.draw.drawWithCache
import app.opentv.ui.jaskFocusRing
import androidx.compose.ui.draw.drawWithContent
import app.opentv.ui.jaskFocusLift
import app.opentv.ui.jaskArtSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.paging.compose.collectAsLazyPagingItems
import app.opentv.R
import app.opentv.catalogue.CatalogueSort
import app.opentv.data.model.Movie
import app.opentv.ui.VodViewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.util.Locale

/** App-owned primitives; no external component/theme dependency leaks into the feature. */
private val JaskCanvas = Color(0xFF141312)
private val JaskAccent = Color(0xFFE4E4E4)

@Composable
internal fun CanonicalMoviesBrowser(vm: VodViewModel, onOpen: (Movie) -> Unit,
    kindControl: @Composable () -> Unit, onInventory: () -> Unit) = JaskTheme {
    CanonicalMoviesContent(vm, onOpen, kindControl, onInventory)
}

@Composable
private fun CanonicalMoviesContent(vm: VodViewModel, onOpen: (Movie) -> Unit,
    kindControl: @Composable () -> Unit, onInventory: () -> Unit) {
    val query by vm.browseQuery.collectAsState()
    val options by vm.movieOptions.collectAsState()
    val source by vm.selectedVodSource.collectAsState()
    val sources by vm.sources.collectAsState()
    val languages by vm.catalogueLanguages.collectAsState()
    var filters by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shell = app.opentv.ui.LocalJaskTouchShell.current
    val filterRequest = app.opentv.ui.LocalJaskFilterRequest.current
    LaunchedEffect(filterRequest) { if (filterRequest > 0) filters = true }
    Column(Modifier.fillMaxSize().then(if (shell) Modifier else Modifier.background(JaskCanvas))) {
        if (!shell) Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            kindControl()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { filters = true }, modifier = Modifier.jaskFocusRing()) {
                    Text(stringResource(if (options.languages.isEmpty()) R.string.catalogue_all_languages else R.string.catalogue_filters))
                }
                TextButton(onClick = onInventory, modifier = Modifier.jaskFocusRing()) { Text(stringResource(R.string.catalogue_inventory)) }
            }
            OutlinedTextField(query, vm::setBrowseQuery, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(stringResource(R.string.catalogue_search)) })
        }
        val home by vm.movieHome.collectAsState()
        val h = home
        val openTitle = rememberHomeOpener { title -> vm.openHomeMovie(title)?.let(onOpen) }
        val watchlist = remember(vm) { HomeWatchlist(vm::homeOnWatchlist, vm::toggleHomeWatchlist) }
        val header: (androidx.compose.foundation.lazy.grid.LazyGridScope.(Int) -> Unit)? = if (h != null && shell && query.isBlank()) { columns ->
            jaskHome(h, columns, watchlist, openTitle)
        } else null
        val hits by vm.movieSearch.collectAsState()
        if (shell && query.isNotBlank()) {
            JaskSearchResults(hits, stringResource(R.string.jask_no_matches, query.trim()), openTitle)
            return@Column
        }
        // The home is its shelves only (no "All movies" grid under them): the full catalogue query isn't run at all.
        val pages = if (header != null) remember { kotlinx.coroutines.flow.flowOf(androidx.paging.PagingData.empty<app.opentv.catalogue.CanonicalMovieCard>()) }
            .collectAsLazyPagingItems() else vm.canonicalMoviePages.collectAsLazyPagingItems()
        PagedVodGrid(pages, "$source/$query/$options", { it.title.tmdbId }, poster = { it.title.posterUrl?.let(::cardPoster) }, emptyText = if (query.isNotBlank()) stringResource(R.string.jask_no_matches, query.trim()) else stringResource(if (vm.metadataConfigured) R.string.vod_paging_empty else R.string.catalogue_empty),
            header = header, showEmpty = header == null) { card ->
            JaskPosterCard(card.title.title, card.title.posterUrl, PosterFacts(rating = card.title.rating, quality = card.bestQuality)) {
                scope.launch { vm.defaultMovieVersion(vm.canonicalVersions(card.movieId))?.let(onOpen) }
            }
        }
    }
    if (filters) {
        var draft by remember { mutableStateOf(options) }
        var draftSource by remember { mutableStateOf(source) }
        JaskFilterSheet(onDismiss = { filters = false },
            onApply = { vm.applyMovieOptions(draft); vm.selectVodSource(draftSource); filters = false },
            onReset = { draft = VodViewModel.MovieOptions(); draftSource = null }) {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    item { TextButton(onClick = { filters = false; onInventory() }, modifier = Modifier.jaskFocusRing()) { Text(stringResource(R.string.catalogue_inventory)) } }
                    item { Text(stringResource(R.string.catalogue_original_language), style = MaterialTheme.typography.titleSmall) }
                    item { FilterOption(stringResource(R.string.catalogue_all_languages), draft.languages.isEmpty()) {
                        draft = draft.copy(languages = emptySet()) } }
                    items(languages) { code -> FilterOption(Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()), code in draft.languages) {
                        draft = draft.copy(languages = if (code in draft.languages) draft.languages - code else draft.languages + code)
                    } }
                    item { FilterOption(stringResource(R.string.catalogue_unknown), draft.includeUnknown) {
                        draft = draft.copy(includeUnknown = !draft.includeUnknown) } }
                    item { Text(stringResource(R.string.catalogue_sort), style = MaterialTheme.typography.titleSmall) }
                    items(CatalogueSort.entries) { sort -> FilterOption(stringResource(when(sort) {
                        CatalogueSort.TITLE -> R.string.catalogue_sort_title
                        CatalogueSort.YEAR -> R.string.catalogue_sort_year
                        CatalogueSort.RATING -> R.string.catalogue_sort_rating
                        CatalogueSort.ADDED -> R.string.catalogue_sort_added
                    }), draft.sort == sort) { draft = draft.copy(sort = sort) } }
                    if (sources.size > 1) {
                        item { FilterOption(stringResource(R.string.catalogue_all_sources), draftSource == null) { draftSource = null } }
                        items(sources, key = { it.id }) { provider -> FilterOption(provider.name, draftSource == provider.id) { draftSource = provider.id } }
                    }
                }
            }
    }
}

internal val LocalPlayGrabbed = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.MutableState<Boolean>?> { null }

@Composable
internal fun JaskTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(background = JaskCanvas, surface = Color(0xFF22201F),
        primaryContainer = Color(0xFF393939), secondary = JaskAccent, secondaryContainer = Color(0xFF303030), onSecondaryContainer = Color.White, surfaceVariant = Color(0xFF303030), onSurfaceVariant = Color(0xFFBDBDBD), primary = JaskAccent, onPrimary = JaskCanvas, onSurface = Color(0xFFF6F3EF),
        onBackground = Color(0xFFF6F3EF)), typography = MaterialTheme.typography) {
        // D-pad focus ring on every clickable (see JaskFocus.kt), with the touch ripple underneath.
        // One "Play takes focus" per screen visit (see JaskPlayPill): reset when the screen is opened again (Back).
        val playGrabbed = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides app.opentv.ui.JaskIndication,
            LocalPlayGrabbed provides playGrabbed, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JaskFilterSheet(onDismiss: () -> Unit, onApply: () -> Unit, onReset: () -> Unit,
    content: @Composable () -> Unit) {
    val actions: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onReset, modifier = Modifier.jaskFocusRing()) { Text(stringResource(R.string.catalogue_reset)) }
            TextButton(onClick = onApply, modifier = Modifier.jaskFocusRing()) { Text(stringResource(R.string.catalogue_apply)) }
        }
    }
    if (app.opentv.ui.LocalLayoutClass.current == app.opentv.ui.LayoutClass.PHONE) {
        app.opentv.ui.JaskSheet(onDismiss) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.catalogue_filters), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp))
                content()
                actions()
            }
        }
    } else {
        AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.catalogue_filters)) },
            text = content, confirmButton = actions)
    }
}

@Composable
private fun FilterOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(selected, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * What a poster shows on its art, as badges and icons rather than text rows:
 * rank (frosted circle, top left, overhanging the corner), best available quality and network logo
 * (frosted pill, top right, overhanging), rating and Running/Ended (bottom left).
 * [noSource] = not on any enabled source: the pill shows a cloud-off mark and the art is muted.
 */
data class PosterFacts(
    val rank: Int? = null, val rating: Double? = null, val status: String? = null, val quality: Int = 0,
    val networkLogo: String? = null, val network: String? = null, val noSource: Boolean = false,
    val extra: List<String> = emptyList(),
    /** "Coming 17 Jul": not released yet (see HomeTitle.comingSoon); a clock instead of the no-source mark. */
    val comingSoon: String? = null,
    /** Who [rating] comes from, named on its tag: [TmdbRating], or [ProviderRating] for titles not matched to TMDB. */
    val ratingSource: String = TmdbRating,
)

/** TMDB's vote average: the score of every title matched to TMDB. */
internal const val TmdbRating = "TMDB"
/** The provider's own score (Xtream's `rating` field), for titles not matched to TMDB. */
internal const val ProviderRating = "Provider"

/** A score's source tag, as the media page's hero draws it: TMDB in its blue, any other source neutral grey. */
@Composable
internal fun JaskRatingTag(source: String, small: Boolean = false) {
    val bg = if (source == TmdbRating) Color(0xFF01B4E4) else Color(0xFFB8B8B8)
    val style = MaterialTheme.typography.labelSmall.let { if (small) it.copy(fontSize = 9.sp, lineHeight = 10.sp) else it }
    Text(source, color = Color.Black, style = style, fontWeight = androidx.compose.ui.text.font.FontWeight.Black, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(bg).padding(horizontal = 4.dp))
}

/**
 * One plain line of facts joined with " · ", ending with the score named by its source's tag
 * ("2026 · Ended · [TMDB] 6.8"). The facts shorten first, so the score always shows.
 */
@Composable
internal fun JaskFactsLine(facts: List<String>, rating: Double?, ratingSource: String = TmdbRating, modifier: Modifier = Modifier,
    color: Color = Color(0xFFBDBDBD), style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelMedium, small: Boolean = false) {
    val score = rating?.takeIf { it > 0 }?.let { String.format(Locale.getDefault(), "%.1f", it) }
    val text = facts.filter(String::isNotBlank)
    if (score == null && text.isEmpty()) return
    Row(modifier, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        if (text.isNotEmpty()) Text(text.joinToString(" · ") + if (score != null) " · " else "", color = color, style = style,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (score != null) {
            JaskRatingTag(ratingSource, small)
            Spacer(Modifier.width(4.dp))
            Text(score, color = color, style = style, maxLines = 1)
        }
    }
}

/** "4K" / "HD" / "SD" from an inferred quality rank; null when unknown. */
internal fun qualityBadge(rank: Int): String? = when {
    rank >= 500 -> "8K"
    rank >= 400 -> "4K"
    rank >= 200 -> "HD"
    rank >= 100 -> "SD"
    else -> null
}

/** How far corner badges stick out past the poster edge, like an app-icon badge. */
internal val PosterBadgeOverhang = 6.dp

/** Muted artwork for titles that aren't on any source. */
internal val UnavailableArt = androidx.compose.ui.graphics.ColorFilter.colorMatrix(
    androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0.25f) })

@Composable
internal fun JaskPosterCard(title: String, poster: String?, chips: List<String>, onClick: () -> Unit) =
    JaskPosterCard(title, poster, PosterFacts(extra = chips), onClick)

/** The poster size a card uses: TMDB's 342-wide (a card is ~316 px wide on the TV's 1080p UI), not 500 or original. */
internal fun cardPoster(url: String): String = url.replace("/t/p/w500/", "/t/p/w342/").replace("/t/p/original/", "/t/p/w342/")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JaskPosterCard(title: String, poster: String?, facts: PosterFacts, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    // Top padding leaves room for the overhanging badges inside the card's own bounds, so lazy
    // lists never clip them; the sideways overhang falls inside the grid spacing.
    Column(Modifier.fillMaxWidth().jaskFocusLift(key = "poster:$title:$poster").onFocusChanged { focused = it.isFocused }
        .clickable(interactionSource = null, indication = null, onClick = onClick)
        .padding(start = 2.dp, end = 2.dp, bottom = 2.dp, top = PosterBadgeOverhang + 1.dp)) {
        PosterArt(poster, facts, Modifier.fillMaxWidth().aspectRatio(2f / 3f), shape, focused)
        Text(title, color = if (facts.noSource) Color(0xFFA9A49F) else Color(0xFFF6F3EF), style = MaterialTheme.typography.labelLarge,
            // Always two lines tall: every card in a row is then the same height. A taller card (a two-line title)
            // reached further down, and the d-pad's search counted it as "below", so Down jumped sideways in the row.
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
    }
}

/**
 * A poster with its badges. Used by grid cards and by Library rows so every poster in the app
 * carries the same marks. Badges overhang the top corners by [PosterBadgeOverhang]; callers leave room.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PosterArt(posterUrl: String?, facts: PosterFacts, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp),
    focused: Boolean = false, compact: Boolean = false, inset: Boolean = false) {
    // A card is ~316 px wide on the TV's 1080p UI: TMDB's 342-wide poster, not the 500 or original.
    val poster = posterUrl?.let(::cardPoster)
    // The badges are glass over the poster: they show a pre-blurred copy of it (see JaskMaterial).
    // The glass badges (rank, quality, not on sources, network logo) see through to a pre-blurred copy of the
    // poster. Only fetched when the card has one of them: it is a second, small decode of the poster.
    val hasFrost = facts.rank != null || facts.noSource || qualityBadge(facts.quality) != null || !facts.networkLogo.isNullOrBlank()
    val art = app.opentv.ui.rememberArtBackdrop(poster.takeIf { hasFrost }, cells = 24, bleed = PosterBadgeOverhang + 6.dp)
    Box(modifier.then(Modifier.jaskArtSource(art))) {
    androidx.compose.runtime.CompositionLocalProvider(app.opentv.ui.LocalJaskBackdrop provides art) {
        Box(Modifier.matchParentSize().clip(shape).background(Color(0xFF282522))
            .then(if (focused) Modifier.drawWithContent {
                drawContent(); drawRect(Color.White.copy(alpha = 0.08f))
                // The d-pad ring, as on every card (see JaskFocus.kt).
                val w = 3.dp.toPx()
                drawRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
            } else Modifier)) {
            AsyncImage(poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                colorFilter = if (facts.noSource) UnavailableArt else null, alpha = if (facts.noSource) .7f else 1f)
            // The chips along the bottom are drawn, not composed: one node instead of a FlowRow of Rows, Icons and
            // Texts, so a row of posters scrolling into view on a TV stick costs far less to build.
            if (!compact) PosterChips(facts, Modifier.matchParentSize())
        }
        // Inset (inside a clipped row) the badges sit just inside the corner instead of overhanging it.
        val o = if (inset) (-4).dp else PosterBadgeOverhang
        facts.rank?.let { rank ->
            PosterFrost(CircleShape, Modifier.align(androidx.compose.ui.Alignment.TopStart).offset(-o, -o).size(24.dp)) {
                Text("$rank", color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 12.sp),
                    modifier = Modifier.align(androidx.compose.ui.Alignment.Center))
            }
        }
        val quality = qualityBadge(facts.quality)
        val logo = facts.networkLogo?.takeIf(String::isNotBlank)
        if (facts.noSource || quality != null || logo != null) {
            PosterFrost(RoundedCornerShape(50), Modifier.align(androidx.compose.ui.Alignment.TopEnd).offset(o, -o).height(20.dp)) {
                Row(Modifier.fillMaxHeight().padding(horizontal = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (facts.comingSoon != null) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(Icons.Outlined.Schedule, null, tint = Color.White, modifier = Modifier.size(12.dp))
                        Text(facts.comingSoon, color = Color.White, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 10.sp))
                    }
                    else if (facts.noSource) Icon(Icons.Outlined.CloudOff, stringResource(R.string.jask_not_on_sources), tint = Color(0xFFFFC7BD), modifier = Modifier.size(13.dp))
                    else quality?.let {
                        Text(it, color = Color.White, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 10.sp, letterSpacing = 0.3.sp),
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            modifier = Modifier.border(1.dp, Color.White.copy(alpha = .8f), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp))
                    }
                    // Network logos drawn as white silhouettes so dark marks (HBO, ABC) read on glass.
                    logo?.let {
                        AsyncImage(it, facts.network, contentScale = ContentScale.Fit,
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
                            modifier = Modifier.height(10.dp).widthIn(max = 34.dp))
                    }
                }
            }
        }
    }
    }
}

/**
 * The poster's bottom chips (rating with its source's tag, status, network name, extras), wrapped like a FlowRow from the
 * bottom-left, drawn in one pass. Text layouts come from one shared, cached [androidx.compose.ui.text.TextMeasurer],
 * so "HD" or "8.4" is laid out once for the whole grid rather than once per card.
 */
@Composable
private fun PosterChips(facts: PosterFacts, modifier: Modifier) {
    val rating = facts.rating?.takeIf { it > 0 }?.let { String.format(Locale.getDefault(), "%.1f", it) }
    val labels = buildList {
        facts.status?.let(::add)
        if (facts.networkLogo.isNullOrBlank()) facts.network?.let(::add)
        addAll(facts.extra)
    }
    if (rating == null && labels.isEmpty()) return
    val measurer = PosterText.measurer(androidx.compose.ui.platform.LocalFontFamilyResolver.current,
        androidx.compose.ui.platform.LocalDensity.current, androidx.compose.ui.platform.LocalLayoutDirection.current)
    val style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp, color = Color(0xFFF2F2F2))
    // The score's source, as a tiny tag inside its chip ("TMDB 8.4"), drawn like the media page's tag.
    val tagStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 7.sp, lineHeight = 8.sp, color = Color.Black,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
    val tagColor = if (facts.ratingSource == TmdbRating) Color(0xFF01B4E4) else Color(0xFFB8B8B8)
    androidx.compose.foundation.layout.Spacer(modifier.drawWithCache {
        val pad = 5.dp.toPx(); val gap = 3.dp.toPx(); val vPad = 2.dp.toPx(); val radius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx())
        val tagPad = 2.dp.toPx(); val tagGap = 3.dp.toPx(); val tagRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
        class Chip(val text: androidx.compose.ui.text.TextLayoutResult, val tag: androidx.compose.ui.text.TextLayoutResult?, val hPad: Float) {
            val tagW = tag?.let { it.size.width + tagPad * 2 } ?: 0f
            val w = hPad * 2 + text.size.width + if (tag != null) tagW + tagGap else 0f
            val h = vPad * 2 + kotlin.math.max(text.size.height.toFloat(), tag?.size?.height?.toFloat() ?: 0f)
        }
        val chips = buildList {
            rating?.let { add(Chip(measurer.measure(it, style, maxLines = 1), measurer.measure(facts.ratingSource, tagStyle, maxLines = 1), 4.dp.toPx())) }
            labels.forEach { add(Chip(measurer.measure(it, style, maxLines = 1), null, 5.dp.toPx())) }
        }
        // Flow: fill rows left to right, then sit the whole block on the bottom edge.
        val maxW = size.width - pad * 2
        val rows = mutableListOf(mutableListOf<Chip>()); var x = 0f
        chips.forEach { c -> if (rows.last().isNotEmpty() && x + c.w > maxW) { rows += mutableListOf<Chip>(); x = 0f }; rows.last() += c; x += c.w + gap }
        val heights = rows.map { r -> r.maxOf { it.h } }
        val top = size.height - pad - heights.sum() - gap * (rows.size - 1)
        onDrawWithContent {
            drawContent()
            var y = top
            rows.forEachIndexed { i, row ->
                var cx = pad
                row.forEach { c ->
                    val cy = y + (heights[i] - c.h) / 2
                    drawRoundRect(Color(0xB3000000), androidx.compose.ui.geometry.Offset(cx, cy), androidx.compose.ui.geometry.Size(c.w, c.h), radius)
                    var tx = cx + c.hPad
                    c.tag?.let { tag ->
                        val th = tag.size.height.toFloat()
                        val ty = cy + (c.h - th) / 2
                        drawRoundRect(tagColor, androidx.compose.ui.geometry.Offset(tx, ty), androidx.compose.ui.geometry.Size(c.tagW, th), tagRadius)
                        drawText(tag, topLeft = androidx.compose.ui.geometry.Offset(tx + tagPad, ty))
                        tx += c.tagW + tagGap
                    }
                    drawText(c.text, topLeft = androidx.compose.ui.geometry.Offset(tx, cy + (c.h - c.text.size.height) / 2))
                    cx += c.w + gap
                }
                y += heights[i] + gap
            }
        }
    })
}

/** One text measurer for every poster chip, with a cache big enough for the labels a catalogue repeats. */
private object PosterText {
    private var key: Any? = null
    private var cached: androidx.compose.ui.text.TextMeasurer? = null
    fun measurer(resolver: androidx.compose.ui.text.font.FontFamily.Resolver, density: androidx.compose.ui.unit.Density,
        direction: androidx.compose.ui.unit.LayoutDirection): androidx.compose.ui.text.TextMeasurer {
        val k = Triple(resolver, density.density to density.fontScale, direction)
        return cached?.takeIf { key == k } ?: androidx.compose.ui.text.TextMeasurer(resolver, density, direction, cacheSize = 256).also { cached = it; key = k }
    }
}

/** Frosted badge over a poster: badge-tone glass over the poster's art backdrop (see [PosterArt]). */
@Composable
internal fun PosterFrost(shape: androidx.compose.ui.graphics.Shape, modifier: Modifier, content: @Composable BoxScope.() -> Unit) =
    app.opentv.ui.JaskGlassSurface(modifier, shape, app.opentv.ui.JaskGlassTone.BADGE, content = content)

/** Small dark pill over artwork; legible on bright posters without a full-width scrim. */
@Composable
internal fun JaskPosterChip(text: String) {
    Text(text, color = Color(0xFFF2F2F2), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
        maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Color(0xB3000000)).padding(horizontal = 5.dp, vertical = 2.dp))
}


@Preview
@Composable
private fun JaskPosterPreview() { Box(Modifier.width(180.dp).background(JaskCanvas)) {
    JaskPosterCard("Inception", null, listOf("★ 8.4", "2010", "EN")) {} } }
