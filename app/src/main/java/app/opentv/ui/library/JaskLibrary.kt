package app.opentv.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import app.opentv.ui.LayoutClass
import app.opentv.ui.LocalLayoutClass
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.ui.LocalJaskContentPadding
import app.opentv.ui.JaskMenu
import app.opentv.ui.JaskMenuItem
import app.opentv.ui.LocalJaskFilterRequest
import app.opentv.ui.VodViewModel
import app.opentv.ui.vod.JaskPosterCard
import app.opentv.ui.vod.JaskPosterChip
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

internal enum class LibraryType(val label: String) { ALL("All"), MOVIES("Movies"), SHOWS("TV") }
private enum class ContinueSort(val label: String) { RECENT("Last watched"), NEW("New episodes first"), LEFT("Least time left") }
private enum class WatchlistSort(val label: String) { TITLE("A–Z"), NEWEST("Newest"), RATING("Rating") }

private const val CONTINUE_LIMIT = 4
private const val WATCHLIST_LIMIT = 5

/**
 * Touch Library: Continue watching (anything you're part-way through, or the next episode of a show
 * you're following, whether or not it's on the Watchlist), then the Watchlist. Each section has its
 * own type switch and sort inline with its heading and shows a few entries until expanded. Tapping
 * opens the media page, never the player. Downloads are a separate view behind the header button;
 * Watched history is one collapsed line at the end.
 */
@Composable
internal fun JaskLibrary(
    query: String, @Suppress("UNUSED_PARAMETER") vodViewModel: VodViewModel,
    onPlayChannel: (Channel) -> Unit, onOpenMovie: (Movie) -> Unit, onOpenSeries: (Series) -> Unit,
    onResume: (String, String, String) -> Unit,
) {
    val vm: LibraryViewModel = viewModel()
    val scope = rememberCoroutineScope()
    val activity by vm.activity.collectAsState()
    val watchlist by vm.watchlist.collectAsState()
    var downloads by rememberSaveable { mutableStateOf(false) }
    var classic by rememberSaveable { mutableStateOf(false) }
    var resumeType by rememberSaveable { mutableStateOf(LibraryType.ALL) }
    var resumeSort by rememberSaveable { mutableStateOf(ContinueSort.RECENT) }
    var resumeAll by rememberSaveable { mutableStateOf(false) }
    var listType by rememberSaveable { mutableStateOf(LibraryType.ALL) }
    var listSort by rememberSaveable { mutableStateOf(WatchlistSort.TITLE) }
    var listAll by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    // The header button toggles the Downloads view.
    val filterRequest = LocalJaskFilterRequest.current
    var seenRequest by rememberSaveable { mutableStateOf(filterRequest) }
    LaunchedEffect(filterRequest) { if (filterRequest != seenRequest) { seenRequest = filterRequest; downloads = !downloads } }
    val padding = LocalJaskContentPadding.current

    if (downloads || classic) {
        BackHandler { downloads = false; classic = false }
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { downloads = false; classic = false }) { Text("‹ " + stringResource(R.string.nav_saved_simple)) }
                Spacer(Modifier.weight(1f))
                Text(if (classic) stringResource(R.string.jask_library_classic) else stringResource(R.string.jask_library_downloads),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 12.dp))
            }
            CompositionLocalProvider(LocalJaskFilterRequest provides 0) {
                if (classic) LibraryScreen(onPlayChannel, onOpenMovie, onOpenSeries, onResume)
                else app.opentv.ui.downloads.DownloadsScreen(onPlay = onResume)
            }
        }
        return
    }

    val needle = query.trim()
    fun matches(text: String) = needle.isEmpty() || text.contains(needle, ignoreCase = true)
    fun LibraryType.allows(isMovie: Boolean) = this == LibraryType.ALL || (this == LibraryType.MOVIES) == isMovie
    val resume = activity.continueWatching.filter { resumeType.allows(it.isMovie) && matches(it.searchText) }.let { list ->
        when (resumeSort) {
            ContinueSort.RECENT -> list
            ContinueSort.NEW -> list.sortedByDescending { it.newEpisode } // stable: keeps last-watched order within each group
            ContinueSort.LEFT -> list.sortedBy { it.remainingMinutes ?: Int.MAX_VALUE }
        }
    }
    val saved = watchlist.filter { listType.allows(it.movie != null) && matches(it.title) }.let { list ->
        when (listSort) {
            WatchlistSort.TITLE -> list.sortedBy { it.title.lowercase() }
            WatchlistSort.NEWEST -> list.sortedWith(compareByDescending<LibraryViewModel.WatchItem> { it.year ?: 0 }.thenBy { it.title.lowercase() })
            WatchlistSort.RATING -> list.sortedWith(compareByDescending<LibraryViewModel.WatchItem> { it.rating ?: 0.0 }.thenBy { it.title.lowercase() })
        }
    }
    val watched = activity.watched.filter { matches(it.searchText) }
    app.opentv.ui.JaskAmbientFrom(activity.continueWatching.firstOrNull()?.let { it.wideArt ?: it.poster } ?: watchlist.firstOrNull()?.poster)

    fun open(item: LibraryViewModel.Activity) = scope.launch {
        item.movieId?.let { id -> vm.movieRow(id)?.let(onOpenMovie) }
        item.seriesRowId?.let { id -> vm.seriesRow(id)?.let(onOpenSeries) }
    }

    val resumeLabel = stringResource(R.string.vod_continue_watching)
    val watchlistLabel = stringResource(R.string.jask_watchlist)
    val searching = needle.isNotEmpty()
    val noMatches = stringResource(R.string.jask_no_matches, needle)
    // Rows are at least 340dp: one column on phones, two or more on tablets and desktops. The
    // collapsed sections show whole rows (6 cards in 3 columns rather than 4 with one left over).
    val side = if (LocalLayoutClass.current == LayoutClass.PHONE) 16.dp else 28.dp
    val columns = ((LocalConfiguration.current.screenWidthDp - 2 * side.value + 12) / (340 + 12)).toInt().coerceAtLeast(1)
    fun whole(limit: Int) = if (columns == 1) limit else ((limit + columns - 1) / columns) * columns
    val continueLimit = whole(CONTINUE_LIMIT); val watchlistLimit = whole(WATCHLIST_LIMIT)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(340.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = side, end = side,
            top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        sectionHeader("h-resume", resumeLabel, resumeType, { resumeType = it; resumeAll = false },
            ContinueSort.entries.map { it.label }, resumeSort.ordinal) { resumeSort = ContinueSort.entries[it] }
        if (resume.isEmpty()) note("n-resume", if (searching) noMatches else "Nothing in progress.")
        val shownResume = if (resumeAll || searching) resume else resume.take(continueLimit)
        items(shownResume, key = { "r-" + it.mediaKey }) { item ->
            ContinueCard(item, onOpen = { open(item) }, onWatched = { vm.markWatched(item) }, onRemove = { vm.forget(item) })
        }
        if (!searching) expander("x-resume", resume.size, continueLimit, resumeAll) { resumeAll = it }

        sectionHeader("h-watchlist", watchlistLabel, listType, { listType = it; listAll = false },
            WatchlistSort.entries.map { it.label }, listSort.ordinal) { listSort = WatchlistSort.entries[it] }
        if (saved.isEmpty()) note("n-watchlist", if (searching) noMatches else "Tap ☆ on any movie or show to save it here.")
        val shownSaved = if (listAll || searching) saved else saved.take(watchlistLimit)
        items(shownSaved, key = { "w-" + it.key }) { entry ->
            WatchRow(entry) { entry.movie?.let(onOpenMovie); entry.series?.let(onOpenSeries) }
        }
        if (!searching) expander("x-watchlist", saved.size, watchlistLimit, listAll) { listAll = it }

        if (watched.isNotEmpty()) {
            item(key = "h-watched", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(8.dp)).clickable { history = !history }
                    .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Watched history", style = MaterialTheme.typography.titleSmall, color = Color(0xFFBDBDBD), modifier = Modifier.weight(1f))
                    Text(if (history) "Hide" else "${watched.size} ›", style = MaterialTheme.typography.labelLarge, color = Color(0xFF9E9E9E))
                }
            }
            if (history || searching) item(key = "watched", span = { GridItemSpan(maxLineSpan) }) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 4) {
                    watched.take(24).forEach { item ->
                        Box(Modifier.weight(1f)) {
                            JaskPosterCard(item.title, item.poster ?: item.wideArt, listOfNotNull(item.episodeCode, "✓")) { open(item) }
                        }
                    }
                    repeat((4 - watched.take(24).size % 4) % 4) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        item(key = "classic", span = { GridItemSpan(maxLineSpan) }) {
            TextButton(onClick = { classic = true }) { Text(stringResource(R.string.jask_library_classic), color = Color(0xFF8A8A8A)) }
        }
    }
}

/** Section title with its type switch and sort on the same line. */
private fun LazyGridScope.sectionHeader(key: String, title: String, type: LibraryType, onType: (LibraryType) -> Unit,
    sorts: List<String>, sort: Int, onSort: (Int) -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFFF4F4F4),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TypeSwitch(type, onType)
            var open by remember { mutableStateOf(false) }
            Spacer(Modifier.width(8.dp))
            Box {
                app.opentv.ui.JaskGlassCircle(Icons.AutoMirrored.Filled.Sort, "Sort: ${sorts[sort]}", size = 34.dp) { open = true }
                JaskMenu(open, { open = false }, sorts.mapIndexed { i, label -> JaskMenuItem(label, checked = i == sort) { onSort(i) } })
            }
        }
    }
}

/** Compact All / Movies / TV switch on glass, selection styled like the tab rail. */
@Composable
internal fun TypeSwitch(type: LibraryType, onType: (LibraryType) -> Unit) {
    app.opentv.ui.JaskGlassSurface(Modifier.height(34.dp), RoundedCornerShape(50), showFocus = true) {
        Row(Modifier.fillMaxHeight().padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
            LibraryType.entries.forEach { t ->
                val selected = t == type
                Box(Modifier.fillMaxHeight().clip(RoundedCornerShape(50)).background(if (selected) Color(0x38FFFFFF) else Color.Transparent)
                    .clickable { onType(t) }.padding(horizontal = 11.dp), contentAlignment = Alignment.Center) {
                    Text(t.label, color = if (selected) Color.White else Color(0xFFB8B8B8), style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

private fun LazyGridScope.note(key: String, text: String) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) {
        Text(text, color = Color(0xFF9E9E9E), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
    }
}

private fun LazyGridScope.expander(key: String, total: Int, limit: Int, expanded: Boolean, onChange: (Boolean) -> Unit) {
    if (total <= limit) return
    item(key = key, span = { GridItemSpan(maxLineSpan) }) {
        Text(if (expanded) "Show less" else "Show all $total", color = Color(0xFFD8D8D8), style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onChange(!expanded) }.padding(vertical = 6.dp, horizontal = 2.dp))
    }
}

/**
 * One title you're currently watching: cover art, then the still of the episode it thinks you're
 * up to (a movie's backdrop), then the title, where you are, the same facts as the grids, and
 * progress. Tapping opens the media page. Long-press: mark watched or remove.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ContinueCard(item: LibraryViewModel.Activity, onOpen: () -> Unit, onWatched: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val removeLabel = stringResource(R.string.jask_remove_from_continue)
    Row(Modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E1C1B))
        .combinedClickable(onClick = onOpen, onLongClick = { menu = true })) {
        app.opentv.ui.vod.PosterArt(item.poster ?: item.wideArt, item.facts.copy(rating = null, status = null, network = null),
            Modifier.fillMaxHeight().aspectRatio(2f / 3f), androidx.compose.ui.graphics.RectangleShape, compact = true, inset = true)
        Box(Modifier.fillMaxHeight().aspectRatio(1.3f).background(Color(0xFF282522))) {
            AsyncImage(item.wideArt ?: item.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            when {
                item.newEpisode -> Badge("NEW EPISODE", Color(0xFFF2F2F2), Color.Black)
                item.upNext -> Badge("UP NEXT", Color.Black.copy(alpha = 0.6f), Color.White)
            }
            JaskMenu(menu, { menu = false }, listOf(
                JaskMenuItem("Mark as watched") { onWatched() },
                JaskMenuItem(removeLabel) { onRemove() }))
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)) {
            Text(item.title, color = Color(0xFFF6F3EF), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.episodeCode != null) {
                Text(buildString { append(item.episodeCode); item.episodeName?.let { append("  "); append(it) } },
                    color = Color(0xFFD6D6D6), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            if (item.facts.noSource) app.opentv.ui.vod.UnavailableMark()
            else if (item.chips.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Shows: one "Network · Running" chip; movies: rating and year · language.
                (if (item.episodeCode != null) listOf(item.chips.joinToString(" · ")) else item.chips.take(2)).forEach { JaskPosterChip(it) }
            }
            Spacer(Modifier.weight(1f))
            val status = when {
                item.remainingMinutes != null -> item.remainingMinutes.let { if (it >= 60) "${it / 60}h ${it % 60}m left" else "${it}m left" }
                item.newEpisode -> "New episode"
                item.upNext -> "Up next"
                else -> null
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(listOfNotNull(status, item.seasonProgress).joinToString(" · "), color = Color(0xFFA8A8A8),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                if (item.progress >= 0.01f) Text("${(item.progress * 100).toInt()}%", color = Color(0xFFA8A8A8),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp))
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.16f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(item.progress).background(Color(0xFFF2F2F2)))
            }
        }
    }
}

@Composable
private fun BoxScope.Badge(text: String, background: Color, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.6.sp), fontWeight = FontWeight.Bold,
        modifier = Modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(background)
            .padding(horizontal = 5.dp, vertical = 2.dp))
}

/** Watchlist entry: poster, title, the grid facts and the description, so you can choose from it directly. */
@Composable
private fun WatchRow(entry: LibraryViewModel.WatchItem, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(126.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E1C1B)).clickable(onClick = onOpen)) {
        app.opentv.ui.vod.PosterArt(entry.poster, entry.facts.copy(rating = null, status = null, network = null),
            Modifier.fillMaxHeight().aspectRatio(2f / 3f), androidx.compose.ui.graphics.RectangleShape, compact = true, inset = true)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(entry.title, color = Color(0xFFF6F3EF), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                (listOf(if (entry.movie != null) "Movie" else "TV") + entry.chips.take(2)).forEach { JaskPosterChip(it) }
            }
            if (entry.facts.noSource) { Spacer(Modifier.height(5.dp)); app.opentv.ui.vod.UnavailableMark() }
            entry.overview?.takeIf(String::isNotBlank)?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Color(0xFFB0B0B0), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
