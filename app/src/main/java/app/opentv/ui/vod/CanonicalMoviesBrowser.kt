package app.opentv.ui.vod

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
                TextButton(onClick = { filters = true }) {
                    Text(stringResource(if (options.languages.isEmpty()) R.string.catalogue_all_languages else R.string.catalogue_filters))
                }
                TextButton(onClick = onInventory) { Text(stringResource(R.string.catalogue_inventory)) }
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
        PagedVodGrid(vm.canonicalMoviePages.collectAsLazyPagingItems(), "$source/$query/$options", { it.title.tmdbId }, if (query.isNotBlank()) stringResource(R.string.jask_no_matches, query.trim()) else stringResource(if (vm.metadataConfigured) R.string.vod_paging_empty else R.string.catalogue_empty),
            header = header) { card ->
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
                    item { TextButton(onClick = { filters = false; onInventory() }) { Text(stringResource(R.string.catalogue_inventory)) } }
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

@Composable
internal fun JaskTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(background = JaskCanvas, surface = Color(0xFF22201F),
        primaryContainer = Color(0xFF393939), secondary = JaskAccent, secondaryContainer = Color(0xFF303030), onSecondaryContainer = Color.White, surfaceVariant = Color(0xFF303030), onSurfaceVariant = Color(0xFFBDBDBD), primary = JaskAccent, onPrimary = JaskCanvas, onSurface = Color(0xFFF6F3EF),
        onBackground = Color(0xFFF6F3EF)), typography = MaterialTheme.typography) {
        // D-pad focus ring on every clickable (see JaskFocus.kt), with the touch ripple underneath.
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides app.opentv.ui.JaskIndication, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JaskFilterSheet(onDismiss: () -> Unit, onApply: () -> Unit, onReset: () -> Unit,
    content: @Composable () -> Unit) {
    val actions: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onReset) { Text(stringResource(R.string.catalogue_reset)) }
            TextButton(onClick = onApply) { Text(stringResource(R.string.catalogue_apply)) }
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
)

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JaskPosterCard(title: String, poster: String?, facts: PosterFacts, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    // Top padding leaves room for the overhanging badges inside the card's own bounds, so lazy
    // lists never clip them; the sideways overhang falls inside the grid spacing.
    Column(Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick)
        .padding(start = 2.dp, end = 2.dp, bottom = 2.dp, top = PosterBadgeOverhang + 1.dp)) {
        PosterArt(poster, facts, Modifier.fillMaxWidth().aspectRatio(2f / 3f), shape, focused)
        Text(title, color = if (facts.noSource) Color(0xFFA9A49F) else Color(0xFFF6F3EF), style = MaterialTheme.typography.labelLarge,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
    }
}

/**
 * A poster with its badges. Used by grid cards and by Library rows so every poster in the app
 * carries the same marks. Badges overhang the top corners by [PosterBadgeOverhang]; callers leave room.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PosterArt(poster: String?, facts: PosterFacts, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp),
    focused: Boolean = false, compact: Boolean = false, inset: Boolean = false) {
    // The badges are glass over the poster: they show a pre-blurred copy of it (see JaskMaterial).
    val art = app.opentv.ui.rememberArtBackdrop(poster, cells = 24, bleed = PosterBadgeOverhang + 6.dp)
    Box(modifier.then(Modifier.jaskArtSource(art))) {
    androidx.compose.runtime.CompositionLocalProvider(app.opentv.ui.LocalJaskBackdrop provides art) {
        Box(Modifier.matchParentSize().clip(shape).background(Color(0xFF282522))
            .then(if (focused) Modifier.border(3.dp, JaskAccent, shape) else Modifier)) {
            AsyncImage(poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                colorFilter = if (facts.noSource) UnavailableArt else null, alpha = if (facts.noSource) .7f else 1f)
            if (!compact) FlowRow(Modifier.align(androidx.compose.ui.Alignment.BottomStart).padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                facts.rating?.takeIf { it > 0 }?.let { r ->
                    Row(Modifier.clip(RoundedCornerShape(5.dp)).background(Color(0xB3000000)).padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Icon(Icons.Filled.Star, null, tint = Color(0xFFF5C518), modifier = Modifier.size(10.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(String.format(Locale.getDefault(), "%.1f", r), color = Color(0xFFF2F2F2),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp))
                    }
                }
                facts.status?.let { JaskPosterChip(it) }
                // Until TMDB supplies a logo, the network name stays as a chip.
                if (facts.networkLogo.isNullOrBlank()) facts.network?.let { JaskPosterChip(it) }
                facts.extra.forEach { JaskPosterChip(it) }
            }
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
                    if (facts.noSource) Icon(Icons.Outlined.CloudOff, stringResource(R.string.jask_not_on_sources), tint = Color(0xFFFFC7BD), modifier = Modifier.size(13.dp))
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

@Composable
internal fun JaskMetadataRow(text: String) {
    Text(text, color = Color(0xFFB6B1AB), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Preview
@Composable
private fun JaskPosterPreview() { Box(Modifier.width(180.dp).background(JaskCanvas)) {
    JaskPosterCard("Inception", null, listOf("★ 8.4", "2010", "EN")) {} } }
