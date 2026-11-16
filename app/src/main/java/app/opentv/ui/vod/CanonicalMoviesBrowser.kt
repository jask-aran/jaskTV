package app.opentv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
    Column(Modifier.fillMaxSize().background(JaskCanvas)) {
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
        val header: (androidx.compose.foundation.lazy.grid.LazyGridScope.(Int) -> Unit)? = if (h != null && shell && query.isBlank()) { columns ->
            jaskHome(h, columns) { title -> scope.launch { vm.openHomeMovie(title)?.let(onOpen) } }
        } else null
        PagedVodGrid(vm.canonicalMoviePages.collectAsLazyPagingItems(), "$source/$query/$options", { it.title.tmdbId }, if (query.isNotBlank()) stringResource(R.string.jask_no_matches, query.trim()) else stringResource(if (vm.metadataConfigured) R.string.vod_paging_empty else R.string.catalogue_empty),
            header = header) { card ->
            JaskPosterCard(card.title.title, card.title.posterUrl,
                // TMDB rating, release year and original language (ISO code), drawn on the poster.
                listOfNotNull(card.title.rating?.takeIf { it > 0 }?.let { "★ " + String.format(Locale.getDefault(), "%.1f", it) },
                    listOfNotNull(card.title.year?.toString(), card.title.originalLanguage?.uppercase(Locale.ROOT))
                        .joinToString(" · ").ifBlank { null })) {
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
        onBackground = Color(0xFFF6F3EF)), typography = MaterialTheme.typography, content = content)
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

@Composable
internal fun JaskPosterCard(title: String, poster: String?, chips: List<String>, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick).padding(2.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(shape).background(Color(0xFF282522))
            .then(if (focused) Modifier.border(3.dp, JaskAccent, shape) else Modifier)) {
            AsyncImage(poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            @OptIn(ExperimentalLayoutApi::class)
            if (chips.isNotEmpty()) FlowRow(Modifier.align(androidx.compose.ui.Alignment.BottomStart).padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) { chips.forEach { JaskPosterChip(it) } }
        }
        Text(title, color = Color(0xFFF6F3EF), style = MaterialTheme.typography.labelLarge,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
    }
}

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
