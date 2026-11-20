package app.opentv.ui.vod

import app.opentv.ui.jaskSlot
import app.opentv.ui.jaskSlotKeys
import androidx.compose.foundation.focusGroup
import app.opentv.ui.jaskFocusRing
import app.opentv.ui.jaskWheelScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.foundation.clickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import app.opentv.R
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.parser.displayTitle
import app.opentv.data.parser.sourceTag
import app.opentv.data.repo.MovieBrowseGroup
import app.opentv.data.parser.MovieBrowseKey
import kotlinx.coroutines.CancellationException
import app.opentv.ui.LayoutClass
import app.opentv.ui.LocalLayoutClass

@Composable
internal fun PagedMovieGrid(
    items: LazyPagingItems<MovieBrowseGroup>, filterKey: String, onOpen: (Movie) -> Unit,
    loadVariants: suspend (String) -> List<Movie>, sourceNames: Map<Long, String>,
) {
    // Reset selection when filters change; LaunchedEffect cancels an obsolete in-flight read.
    var selected by remember(filterKey) { mutableStateOf<MovieBrowseGroup?>(null) }
    PagedVodGrid(items, filterKey, { it.key }, poster = { it.primary.posterUrl }, frost = false) { group ->
        val movie = group.primary
        PosterCard(
            title = movie.displayTitle, posterUrl = movie.posterUrl,
            subtitle = movie.year?.toString(), rating = movie.rating, tagBadge = movie.sourceTag,
            qualityBadge = group.qualityLabel.takeIf { it.isNotBlank() }
                ?: if (group.variantCount > 1) stringResource(R.string.guide_qualities_count, group.variantCount) else null,
            onClick = { if (group.variantCount > 1) selected = group else onOpen(movie) },
        )
    }
    selected?.let { group ->
        androidx.compose.runtime.key(group.key, filterKey) {
            MovieVariantsDialog(group, sourceNames, loadVariants,
                onDismiss = { selected = null }, onOpen = { selected = null; onOpen(it) })
        }
    }
}

@Composable
private fun MovieVariantsDialog(
    group: MovieBrowseGroup, sourceNames: Map<Long, String>,
    loadVariants: suspend (String) -> List<Movie>, onDismiss: () -> Unit, onOpen: (Movie) -> Unit,
) {
    var retry by remember { mutableStateOf(0) }
    var result by remember(group.key) { mutableStateOf<Result<List<Movie>>?>(null) }
    LaunchedEffect(group.key, retry) {
        result = null
        result = try { Result.success(loadVariants(group.key)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(group.primary.displayTitle) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.jaskFocusRing()) { Text(stringResource(R.string.common_cancel)) } },
        text = {
            when {
                result == null -> PagingLoading()
                result!!.isFailure -> PagingError { retry++ }
                result!!.getOrThrow().isEmpty() -> Text(stringResource(R.string.vod_paging_empty))
                else -> LazyColumn {
                    items(result!!.getOrThrow(), key = { it.id }) { movie ->
                        val quality = MovieBrowseKey.from(movie.name, movie.year).qualityLabel
                            .ifBlank { stringResource(R.string.player_quality_standard) }
                        ListItem(
                            headlineContent = { Text(movie.name) },
                            supportingContent = { Text("${sourceNames[movie.sourceId] ?: stringResource(R.string.rec_source_unknown)} · $quality") },
                            modifier = Modifier.clickable { onOpen(movie) },
                        )
                    }
                }
            }
        },
    )
}

@Composable
internal fun PagedSeriesGrid(items: LazyPagingItems<app.opentv.ui.VodViewModel.ShowCard>, filterKey: String,
    onOpen: (Series) -> Unit, loadVariants: suspend (String) -> List<Series>, emptyText: String? = null,
    pickDefault: suspend (List<Series>) -> Series? = { it.firstOrNull() },
    header: (androidx.compose.foundation.lazy.grid.LazyGridScope.(columns: Int) -> Unit)? = null, showEmpty: Boolean = true) {
    // A tap always opens the show's page; its Source control switches between provider versions.
    val scope = rememberCoroutineScope()
    PagedVodGrid(items, filterKey, { it.group.groupKey }, poster = { it.group.primary.posterUrl?.let(::cardPoster) }, emptyText = emptyText, header = header, showEmpty = showEmpty) { card ->
        val group = card.group
        val show = card.show
        JaskPosterCard(cleanShowTitle(group.primary.name), group.primary.posterUrl, PosterFacts(rating = show?.rating ?: group.primary.rating,
            status = show?.statusLabel, quality = group.bestQuality, networkLogo = show?.networkLogo, network = show?.network)) {
            if (group.variantCount > 1) scope.launch { onOpen(pickDefault(loadVariants(group.groupKey)) ?: group.primary) }
            else onOpen(group.primary)
        }
    }
}

@Composable
internal fun <T : Any> PagedVodGrid(
    items: LazyPagingItems<T>, filterKey: String, key: (T) -> Any,
    /** The card's poster URL, fetched a few rows ahead of the d-pad. [frost]: also prepare its frosted badge backdrop. */
    poster: ((T) -> String?)? = null, frost: Boolean = true, emptyText: String? = null,
    header: (androidx.compose.foundation.lazy.grid.LazyGridScope.(columns: Int) -> Unit)? = null,
    /** False: a home that is only its [header] (no "nothing here" message under it). */
    showEmpty: Boolean = true, card: @Composable (T) -> Unit,
) {
    // A detail push saves the viewport, while an actual filter change starts at the top.
    // Declare before load-state branches so invalidations never discard the saved viewport.
    val state = rememberSaveable(filterKey, saver = LazyGridState.Saver) { LazyGridState() }
    val refresh = items.loadState.refresh
    // The home arrives a moment after the grid; the grid would stay anchored on its first poster
    // and open scrolled past the hero. Jump to the top if we were at the top when it arrived.
    val hasHeader = header != null
    var hadHeader by remember(filterKey) { mutableStateOf(hasHeader) }
    LaunchedEffect(hasHeader) {
        if (hasHeader && !hadHeader) {
            val firstKey = if (items.itemCount > 0) items.peek(0)?.let(key) else null
            val visible = state.layoutInfo.visibleItemsInfo.firstOrNull()
            if (visible == null || (visible.key == firstKey && state.firstVisibleItemScrollOffset < 40)) state.scrollToItem(0)
        }
        hadHeader = hasHeader
    }
    if (items.itemCount == 0 && header == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (refresh) {
                is LoadState.Loading -> PagingLoading()
                is LoadState.Error -> PagingError(items::retry)
                is LoadState.NotLoading -> Text(emptyText ?: stringResource(R.string.vod_paging_empty), modifier = Modifier.padding(24.dp))
            }
        }
        return
    }
    val phone = LocalLayoutClass.current == LayoutClass.PHONE
    // TV: Up/Down move by slot (row and column), not by on-screen geometry. See JaskSlots.
    val slotScope = rememberCoroutineScope()
    val slots = if (LocalLayoutClass.current == LayoutClass.TV) remember(state) { app.opentv.ui.JaskSlots(state, slotScope) } else null
    val keyOf = items.itemKey(key)
    app.opentv.ui.JaskPageTopEffect(state) { state.animateScrollToItem(0) }
    if (poster != null) PosterPrefetch(state, items, poster, frost)
    androidx.compose.runtime.CompositionLocalProvider(app.opentv.ui.LocalJaskSlots provides slots) {
    Column(Modifier.fillMaxSize()) {
        // Room invalidates as background identification commits. Keep the existing viewport
        // visible; inserting a loading row on every refresh made the grid look like a reload loop.
        if (refresh is LoadState.Error) PagingError(items::retry)
        LazyVerticalGrid(
            // Three posters across a phone, ~120dp posters on tablets: browse density over hero size.
            columns = if (phone) GridCells.Fixed(3) else GridCells.Adaptive(if (LocalLayoutClass.current == LayoutClass.TV) 140.dp else 150.dp),
            state = state, modifier = Modifier.weight(1f).fillMaxWidth().jaskWheelScroll(state).then(Modifier.jaskSlotKeys(slots)),
            // A home header starts full-bleed under the floating header.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
                top = if (header != null) 0.dp else app.opentv.ui.LocalJaskContentPadding.current.calculateTopPadding() + 8.dp,
                bottom = app.opentv.ui.LocalJaskContentPadding.current.calculateBottomPadding() + 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            header?.invoke(this, if (phone) 3 else 6)
            if (header != null && items.itemCount == 0 && showEmpty) item(key = "paging-empty", span = { GridItemSpan(maxLineSpan) }) {
                when (refresh) {
                    is LoadState.Loading -> PagingLoading()
                    is LoadState.Error -> PagingError(items::retry)
                    is LoadState.NotLoading -> Text(emptyText ?: stringResource(R.string.vod_paging_empty), modifier = Modifier.padding(24.dp))
                }
            }
            items(count = items.itemCount, key = keyOf) { index ->
                Box(Modifier.jaskSlot(keyOf(index)).focusGroup()) { items[index]?.let { card(it) } }
            }
            item(key = "paging-footer", span = { GridItemSpan(maxLineSpan) }) {
                when (items.loadState.append) {
                    is LoadState.Loading -> PagingLoading()
                    is LoadState.Error -> PagingError(items::retry)
                    else -> Unit
                }
            }
        }
    }
    }
}

/**
 * Fetches posters for the next few rows before they scroll in, so a fast d-pad scroll lands on drawn cards instead of
 * blank ones. Requested at the card's size, so the image comes straight from memory when the card draws it.
 */
@Composable
private fun <T : Any> PosterPrefetch(state: LazyGridState, items: LazyPagingItems<T>, poster: (T) -> String?, frost: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(state, items) {
        val loader = coil.Coil.imageLoader(context)
        val asked = LinkedHashSet<String>()
        androidx.compose.runtime.snapshotFlow {
            val info = state.layoutInfo
            // Grid index → page index: header items come first, the footer last.
            val headers = info.totalItemsCount - items.itemCount - 1
            // Before the grid's first row is on screen (the home's shelves above it), the first rows are fetched.
            val last = ((info.visibleItemsInfo.lastOrNull()?.index ?: -1) - headers).coerceAtLeast(-1)
            val width = info.visibleItemsInfo.lastOrNull { it.index - headers in 0 until items.itemCount }?.size?.width ?: 0
            Triple(last, width, items.itemCount)
        }.collect { (last, width, count) ->
            if (count == 0) return@collect
            val end = minOf(count, last + 1 + PREFETCH)
            for (i in last + 1 until end) {
                val url = items.peek(i)?.let(poster)?.takeIf { it.isNotBlank() } ?: continue
                if (!asked.add(url)) continue
                // At the card's size once a card has been measured; before that, the poster as served (TMDB's w342,
                // a little larger than a card, so it still satisfies the card from memory).
                loader.enqueue(coil.request.ImageRequest.Builder(context).data(url).apply {
                    if (width > 0) size(width, width * 3 / 2).scale(coil.size.Scale.FILL)
                }.build())
                if (frost) launch { runCatching { app.opentv.ui.prefetchFrost(context, url, 24) } }
            }
            while (asked.size > 600) asked.remove(asked.first())
        }
    }
}

/** Cards fetched ahead: about four rows of six. */
private const val PREFETCH = 24

@Composable
private fun PagingLoading() {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White, strokeWidth = 3.dp)
        Text(stringResource(R.string.vod_paging_loading), color = androidx.compose.ui.graphics.Color(0xFFBDBDBD),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun PagingError(retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.vod_paging_error))
        TextButton(onClick = retry, modifier = Modifier.jaskFocusRing()) { Text(stringResource(R.string.vod_paging_retry)) }
    }
}
