/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.runtime.saveable.rememberSaveable
import app.opentv.data.parser.ChannelNameNormalizer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Close
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import app.opentv.core.ServiceLocator
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import app.opentv.ui.isPhoneLandscape
import app.opentv.ui.LayoutClass
import app.opentv.ui.LocalLayoutClass
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.parser.displayTitle
import app.opentv.data.parser.sourceTag
import app.opentv.ui.VodViewModel
import app.opentv.data.repo.VodCategoryKey
import androidx.compose.material3.TextButton
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.activity.compose.BackHandler
import coil.compose.AsyncImage

/** Search and browse the local catalogue in place, using the same paged poster grid. */
@Composable
fun MoviesScreen(
    onOpenMovie: (Movie) -> Unit,
    onResume: (String, String, String) -> Unit,
    onOpenSearch: () -> Unit,
    hasSources: Boolean,
    isSyncing: Boolean,
    viewModel: VodViewModel = viewModel(),
    kindControl: @Composable () -> Unit = {},
    searchVisible: Boolean = true,
    onCloseSearch: () -> Unit = {},
) {
    var inventory by rememberSaveable { mutableStateOf(false) }
    if (!inventory) {
        CanonicalMoviesBrowser(viewModel, onOpenMovie, kindControl, onInventory = { inventory = true })
        LaunchedEffect(hasSources) { if (hasSources) viewModel.ensureVodLoaded() }
        return
    }
    BackHandler { inventory = false }
    val categories by viewModel.movieCategories.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val source by viewModel.selectedVodSource.collectAsState()
    val category by viewModel.selectedMovieCategory.collectAsState()
    val query by viewModel.browseQuery.collectAsState()
    LaunchedEffect(hasSources) { if (hasSources) viewModel.ensureVodLoaded() }
    BackHandler(category != null) { viewModel.selectMovieCategory(null) }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = { inventory = false }) { Text(stringResource(R.string.catalogue_back)) }
        CatalogueControls(query, viewModel::setBrowseQuery, sources, source,
            viewModel::selectVodSource, categories, category,
            { viewModel.selectMovieCategory(null) }, viewModel::selectMovieCategory,
            viewModel::movieCategoryCounts, onOpenSearch, kindControl, searchVisible, onCloseSearch)
        CatalogueSyncState(viewModel)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PagedMovieGrid(viewModel.moviePages.collectAsLazyPagingItems(),
                "$source/$category/$query", onOpenMovie, viewModel::movieBrowseVariants,
                sources.associate { it.id to it.name })
        }
    }
}

@Composable
fun SeriesScreen(
    onOpenSeries: (Series) -> Unit,
    onResume: (String, String, String) -> Unit,
    onOpenSearch: () -> Unit,
    hasSources: Boolean,
    isSyncing: Boolean,
    viewModel: VodViewModel = viewModel(),
    kindControl: @Composable () -> Unit = {},
    searchVisible: Boolean = true,
    onCloseSearch: () -> Unit = {},
) {
    val categories by viewModel.seriesCategories.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val source by viewModel.selectedVodSource.collectAsState()
    val category by viewModel.selectedSeriesCategory.collectAsState()
    val query by viewModel.browseQuery.collectAsState()
    LaunchedEffect(hasSources) { if (hasSources) viewModel.ensureVodLoaded() }
    if (app.opentv.ui.LocalJaskTouchShell.current) {
        // Source and availability scope live in the account popover; there is no filter sheet here.
        val home by viewModel.showHome.collectAsState()
        val hits by viewModel.showSearch.collectAsState()
        val openTitle = rememberHomeOpener { title -> viewModel.openHomeShow(title)?.let(onOpenSeries) }
        val watchlist = androidx.compose.runtime.remember(viewModel) { HomeWatchlist(viewModel::homeOnWatchlist, viewModel::toggleHomeWatchlist) }
        if (query.isNotBlank()) {
            JaskSearchResults(hits, stringResource(R.string.jask_no_matches, query.trim()), openTitle)
            return
        }
        val h = home
        val header: (androidx.compose.foundation.lazy.grid.LazyGridScope.(Int) -> Unit)? = if (h != null) { columns ->
            jaskHome(h, columns, watchlist, openTitle)
        } else null
        PagedSeriesGrid(viewModel.seriesPages.collectAsLazyPagingItems(), "$source/$category/$query", onOpenSeries, viewModel::seriesBrowseVariants, pickDefault = viewModel::defaultSeriesVersion,
            emptyText = null, header = header)
        return
    }
    BackHandler(category != null) { viewModel.selectSeriesCategory(null) }
    Column(Modifier.fillMaxSize()) {
        CatalogueControls(query, viewModel::setBrowseQuery, sources, source,
            viewModel::selectVodSource, categories, category,
            { viewModel.selectSeriesCategory(null) }, viewModel::selectSeriesCategory,
            viewModel::seriesCategoryCounts, onOpenSearch, kindControl, searchVisible, onCloseSearch)
        CatalogueSyncState(viewModel)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PagedSeriesGrid(viewModel.seriesPages.collectAsLazyPagingItems(),
                "$source/$category/$query", onOpenSeries, viewModel::seriesBrowseVariants)
        }
    }
}

@Composable
private fun CatalogueControls(
    query: String, onQuery: (String) -> Unit, sources: List<Source>, source: Long?,
    onSource: (Long?) -> Unit, categories: List<app.opentv.data.model.Category>,
    category: String?, onAll: () -> Unit, onCategory: (String) -> Unit,
    counts: suspend () -> Map<String, Int>, onOpenSearch: () -> Unit,
    kindControl: @Composable () -> Unit,
    searchVisible: Boolean, onCloseSearch: () -> Unit,
) {
    val categoryControl: @Composable () -> Unit = {
        CategoryChips(
            entries = categories.map {
                val name = ChannelNameNormalizer.foldSuperscripts(it.name)
                val provider = sources.firstOrNull { s -> s.id == it.sourceId }?.name
                VodCategoryKey(it.sourceId, it.id).token to
                    if (source == null && sources.size > 1) "$name · $provider" else name
            },
            selected = category, homeSelected = category == null,
            onSelectHome = onAll, onSelectCategory = onCategory, loadCounts = counts,
            sources = sources, selectedSource = source, onSelectSource = onSource,
        )
    }
    if (LocalLayoutClass.current == LayoutClass.PHONE) {
        if (searchVisible) app.opentv.ui.CatalogueSearchField(query, onQuery,
            stringResource(R.string.vod_search_hint), onClose = onCloseSearch, autoFocus = true)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            kindControl()
            Spacer(Modifier.weight(1f))
            categoryControl()
        }
    } else {
        SearchAffordance(onOpenSearch)
        BrowseControls {
            kindControl()
            if (sources.size > 1) ProviderChips(sources, source,
                onSelectAll = { onSource(null) }, onSelectSource = { onSource(it) })
            categoryControl()
        }
    }
}

@Composable
private fun CatalogueSyncState(viewModel: VodViewModel) {
    val loading by viewModel.vodLoading.collectAsState()
    val failed by viewModel.vodFailed.collectAsState()
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (failed) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.vod_paging_error), modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = viewModel::refreshVod) { Text(stringResource(R.string.vod_paging_retry)) }
    }
}

/** One horizontally scrollable control row, never a vertical scroll parent of a paged grid. */
@Composable
private fun BrowseControls(content: @Composable () -> Unit) {
    if (isPhoneLandscape()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically) { content() }
    } else Column { content() }
}



@Composable
internal fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
    )
}

/**
 * The reusable poster card: art, title and an optional year, with a rating chip, a quality badge and
 * a resume progress bar drawn over the art where the data is there. The focused card scales up and
 * gains a primary border — the app's established focus cue — and, being focusable, the lazy row
 * brings it into view on its own.
 */
@Composable
internal fun PosterCard(
    title: String,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    rating: Double? = null,
    qualityBadge: String? = null,
    progress: Float? = null,
    /** Origin tag from the raw title (`EN`, `DE 4K`, `NF`) — which country/service copy this is. */
    tagBadge: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "posterScale")
    Column(
        modifier
            // Smaller on phones so a shelf shows three-and-a-bit, not two-and-a-half.
            .width(if (isPhone()) PHONE_POSTER_WIDTH else POSTER_WIDTH)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(if (isPhone()) 0.dp else 4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    else Modifier,
                ),
        ) {
            AsyncImage(
                model = posterUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            rating?.takeIf { it > 0.0 && !isPhone() }?.let {
                Badge(
                    text = "★ ${formatRating(it)}",
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                )
            }
            qualityBadge?.takeIf { !isPhone() }?.let {
                Badge(
                    text = it,
                    highlight = true,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
            }
            tagBadge?.takeIf { !isPhone() }?.let {
                Badge(text = it, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
            }
            progress?.let {
                LinearProgressIndicator(
                    progress = { it },
                    modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (isPhone() && subtitle != null) title.removeSuffix(" ($subtitle)") else title,
            style = if (isPhone()) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = if (isPhone() || focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (isPhone()) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        val metadata = if (isPhone()) listOfNotNull(
            tagBadge?.takeIf { it.isNotBlank() }, subtitle, qualityBadge,
        ).distinct().joinToString(" · ") else subtitle.orEmpty()
        if (metadata.isNotBlank()) Text(metadata,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)

    }
}

/** A small rounded chip drawn over poster art — a rating or a quality label. */
@Composable
private fun Badge(text: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val bg = if (highlight) MaterialTheme.colorScheme.primary
    else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.66f)
    val fg = if (highlight) MaterialTheme.colorScheme.onPrimary else androidx.compose.ui.graphics.Color.White
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ---- Continue watching -------------------------------------------------------------------------



// ---- Category chips ----------------------------------------------------------------------------

/**
 * A horizontal chip strip along the top of the home: a Home chip returns to the curated rows, and
 * each following chip opens that category's full grid. Keeps whole-category browsing reachable on a
 * d-pad without a permanent side rail taking the width.
 */
@Composable
private fun CategoryChips(
    entries: List<Pair<String, String>>,
    selected: String?,
    homeSelected: Boolean,
    onSelectHome: () -> Unit,
    onSelectCategory: (String) -> Unit,
    loadCounts: (suspend () -> Map<String, Int>)? = null,
    sources: List<Source> = emptyList(), selectedSource: Long? = null,
    onSelectSource: (Long?) -> Unit = {},
) {
    if (LocalLayoutClass.current == LayoutClass.PHONE) {
        PhoneCategoryPicker(entries, selected, homeSelected, onSelectHome, onSelectCategory, loadCounts,
            sources, selectedSource, onSelectSource)
        return
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "label") {
            Text(
                stringResource(R.string.vod_categories),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        item(key = "all") { Chip(stringResource(R.string.vod_all), homeSelected, onSelectHome) }
        items(entries, key = { it.first }) { (id, name) ->
            Chip(name, selected == id) { onSelectCategory(id) }
        }
    }
}

/**
 * Phones: a "Categories" button opening a searchable bottom sheet (film libraries run to hundreds of
 * categories — scrolling a chip strip sideways to find one doesn't work by touch), plus "All".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneCategoryPicker(
    entries: List<Pair<String, String>>,
    selected: String?,
    homeSelected: Boolean,
    onSelectHome: () -> Unit,
    onSelectCategory: (String) -> Unit,
    loadCounts: (suspend () -> Map<String, Int>)?,
    sources: List<Source>, selectedSource: Long?, onSelectSource: (Long?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(open, selectedSource) { if (open && loadCounts != null) counts = runCatching { loadCounts() }.getOrDefault(emptyMap()) }
    val selectedName = entries.firstOrNull { it.first == selected }?.second
    val compact = isPhoneLandscape()
    IconButton(onClick = { open = true }, modifier = Modifier.size(34.dp)) {
        Icon(Icons.Filled.Tune, stringResource(R.string.provider_filters), Modifier.size(20.dp),
            tint = if (selected != null || selectedSource != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            var filter by remember { mutableStateOf("") }
            val shown = remember(entries, filter) {
                if (filter.isBlank()) entries else entries.filter { it.second.contains(filter.trim(), ignoreCase = true) }
            }
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                Text(stringResource(R.string.provider_filters), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                if (sources.size > 1) ProviderChips(sources, selectedSource,
                    onSelectAll = { onSelectSource(null) }, onSelectSource = { onSelectSource(it) })

                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text(stringResource(R.string.phone_live_filter_categories)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (filter.isBlank()) {
                        item(key = "all") {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.vod_all)) },
                                colors = ListItemDefaults.colors(
                                    containerColor = if (homeSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                                modifier = Modifier.fillMaxWidth().clickable { open = false; onSelectHome() },
                            )
                        }
                    }
                    items(shown, key = { it.first }) { (id, name) ->
                        ListItem(
                            headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = counts[id]?.let { n ->
                                { Text("$n", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = if (selected == id) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                            modifier = Modifier.fillMaxWidth().clickable { open = false; onSelectCategory(id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A provider filter above the category chips, shown only when more than one source is configured —
 * pick a provider to browse just its Movies/Shows categories, or "All sources" to fold them.
 */
@Composable
private fun ProviderChips(
    sources: List<Source>,
    selected: Long?,
    onSelectAll: () -> Unit,
    onSelectSource: (Long) -> Unit,
) {
    if (isPhoneLandscape()) {
        var expanded by remember { mutableStateOf(false) }
        Box {
            FilterChip(selected = selected != null, onClick = { expanded = true },
                label = { Text(sources.firstOrNull { it.id == selected }?.name
                    ?: stringResource(R.string.channels_manager_all_sources), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) })
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.channels_manager_all_sources)) },
                    onClick = { expanded = false; onSelectAll() })
                sources.forEach { source ->
                    DropdownMenuItem(text = { Text(source.name) },
                        onClick = { expanded = false; onSelectSource(source.id) })
                }
            }
        }
        return
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "plabel") {
            Text(
                stringResource(R.string.channels_manager_source_header),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        item(key = "pall") {
            Chip(stringResource(R.string.channels_manager_all_sources), selected == null, onSelectAll)
        }
        items(sources, key = { it.id }) { source ->
            Chip(source.name, selected == source.id) { onSelectSource(source.id) }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

// ---- Search / loading / empty ------------------------------------------------------------------

/**
 * The search entry at the top of the Movies and Shows home. The rail's global search already covers
 * movies and series; this makes it reachable without leaving the tab. Focusable for d-pad on TV and
 * tappable on touch, it just opens the existing search screen.
 */
@Composable
private fun SearchAffordance(onOpenSearch: () -> Unit) {
    if (isPhoneLandscape()) {
        IconButton(onClick = onOpenSearch) {
            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.vod_search_hint))
        }
        return
    }
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (focused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onOpenSearch)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (focused) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(Icons.Filled.Search, contentDescription = null, tint = tint)
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.vod_search_hint),
            style = MaterialTheme.typography.titleMedium,
            color = tint,
        )
    }
}



/** Poster shelf card width; the grid uses an adaptive min size close to this. */
private val POSTER_WIDTH = 140.dp
private val PHONE_POSTER_WIDTH = 112.dp

@Composable
private fun isPhone(): Boolean = LocalLayoutClass.current == LayoutClass.PHONE

/** Rating to one decimal place, locale-independent (the "★" is drawn beside it). */
internal fun formatRating(rating: Double): String = String.format(java.util.Locale.US, "%.1f", rating)
