/*
 * jaskTV — GPL-3.0-or-later. Derived from OpenTV Mobile.
 * A saved-items library with persistent user-defined collections.
 */
package app.opentv.ui.library

import app.opentv.ui.jaskFocusRing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import app.opentv.ui.channels.ChannelMappingDialog
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import app.opentv.ui.JaskMenu
import app.opentv.ui.JaskMenuItem
import app.opentv.ui.vod.JaskFactsLine
import app.opentv.ui.vod.ProviderRating
import app.opentv.ui.vod.cleanShowTitle
import androidx.compose.ui.unit.dp
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.CollectionEntry
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.UserCollection
import app.opentv.data.model.shownName
import app.opentv.data.repo.DownloadRepository
import app.opentv.ui.downloads.DownloadsScreen
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private data class SavedRef(val kind: String, val sourceId: Long, val key: String) {
    fun asEntry(collectionId: Long) = CollectionEntry(collectionId, kind, sourceId, key)
}

@Composable
fun LibraryScreen(
    onPlayChannel: (Channel) -> Unit,
    onOpenMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onResume: (String, String, String) -> Unit,
) {
    val context = LocalContext.current
    val graph = remember(context) { ServiceLocator.get(context) }
    val dao = remember(graph) { graph.database.collections() }
    val scope = rememberCoroutineScope()
    val profileId by graph.settings.activeProfileId.collectAsState()
    val favourites by remember(graph) { graph.catalogRepository.observeFavouriteChannels() }
        .collectAsState(initial = emptyList())
    val movies by remember(graph) { graph.database.movies().observeFavourites() }
        .collectAsState(initial = emptyList())
    val shows by remember(graph) { graph.database.series().observeFavourites() }
        .collectAsState(initial = emptyList())
    val downloads by remember(graph) { graph.downloadRepository.observe() }
        .collectAsState(initial = emptyList())
    val collections by remember(dao, profileId) { dao.observeCollections(profileId) }.collectAsState(initial = emptyList())
    val memberships by remember(dao) { dao.observeAllEntries() }.collectAsState(initial = emptyList())
    val collectedChannels by remember(dao, profileId) { dao.observeAllCollectedChannels(profileId) }.collectAsState(initial = emptyList())
    val collectedMovies by remember(dao, profileId) { dao.observeAllCollectedMovies(profileId) }.collectAsState(initial = emptyList())
    val collectedShows by remember(dao, profileId) { dao.observeAllCollectedShows(profileId) }.collectAsState(initial = emptyList())

    suspend fun removeFromCollections(ref: SavedRef) {
        memberships.filter {
            it.kind == ref.kind && it.sourceId == ref.sourceId && it.itemKey == ref.key
        }.forEach { dao.remove(it.collectionId, it.kind, it.sourceId, it.itemKey) }
    }

    var section by rememberSaveable { mutableStateOf("All") }
    var sort by rememberSaveable { mutableStateOf("Name") }
    var sorting by remember { mutableStateOf(false) }

    val requestedCollectionId = section.removePrefix("collection:").toLongOrNull()
    // Never display a different profile's collection when the viewer switches accounts.
    val collectionId = requestedCollectionId?.takeIf { id ->
        collections.any { it.id == id }
    }
    val collectionChannels by remember(dao, collectionId) {
        collectionId?.let { dao.observeChannels(it) } ?: flowOf(emptyList<Channel>())
    }.collectAsState(initial = emptyList())
    val collectionMovies by remember(dao, collectionId) {
        collectionId?.let { dao.observeMovies(it) } ?: flowOf(emptyList<Movie>())
    }.collectAsState(initial = emptyList())
    val collectionShows by remember(dao, collectionId) {
        collectionId?.let { dao.observeSeries(it) } ?: flowOf(emptyList<Series>())
    }.collectAsState(initial = emptyList())

    val channelsToShow = if (collectionId == null)
        (favourites + collectedChannels).distinctBy { it.sourceId to it.streamId }
        else collectionChannels
    val moviesToShow = if (collectionId == null)
        (movies + collectedMovies).distinctBy { it.sourceId to it.streamId }
        else collectionMovies
    val showsToShow = if (collectionId == null)
        (shows + collectedShows).distinctBy { it.sourceId to it.seriesId }
        else collectionShows
    val entriesHere = if (collectionId == null) emptyList() else memberships.filter { it.collectionId == collectionId }
    val downloadsHere = downloads.filter {
        it.state == DownloadRepository.State.DONE && it.fileUri != null &&
            (collectionId == null || entriesHere.any { entry ->
                entry.kind == "download" && entry.itemKey == it.download.mediaKey
            })
    }
    val savedChannels = channelsToShow.distinctBy {
        "${it.sourceId}:${it.groupKey.takeIf(String::isNotBlank) ?: it.streamId}"
    }
    val orderedMovies = when (sort) {
        "Rating" -> moviesToShow.sortedWith(compareByDescending<Movie> { it.rating ?: -1.0 }.thenBy { it.name })
        "Year" -> moviesToShow.sortedWith(compareByDescending<Movie> { it.year ?: 0 }.thenBy { it.name })
        else -> moviesToShow.sortedBy { cleanShowTitle(it.name).lowercase() }
    }
    val orderedShows = when (sort) {
        "Rating" -> showsToShow.sortedWith(compareByDescending<Series> { it.rating ?: -1.0 }.thenBy { it.name })
        "Year" -> showsToShow.sortedWith(compareByDescending<Series> { it.year ?: 0 }.thenBy { it.name })
        else -> showsToShow.sortedBy { cleanShowTitle(it.name).lowercase() }
    }

    var editor by remember { mutableStateOf<UserCollection?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<UserCollection?>(null) }
    var organize by remember { mutableStateOf<SavedRef?>(null) }
    var mapChannel by remember { mutableStateOf<Channel?>(null) }

    Column(Modifier.fillMaxSize()) {
        if (!app.opentv.ui.LocalJaskTouchShell.current) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("All", "Channels", "Movies", "Shows", "Downloads").forEach { name ->
                FilterChip(selected = section == name, onClick = { section = name },
                    label = { Text(name) }, colors = neutralChipColors())
            }
            collections.forEach { collection ->
                FilterChip(selected = collectionId == collection.id,
                    onClick = { section = "collection:${collection.id}" },
                    label = { Text(collection.name, maxLines = 1) }, colors = neutralChipColors(),
                    leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) })
            }
            TextButton(onClick = { creating = true }, modifier = Modifier.jaskFocusRing(), colors = neutralTextButton()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Collection")
            }
        }
        }
        var libraryFilters by remember { mutableStateOf(false) }
        val filterRequest = app.opentv.ui.LocalJaskFilterRequest.current
        LaunchedEffect(filterRequest) { if (filterRequest > 0) libraryFilters = true }
        if (libraryFilters) AlertDialog(onDismissRequest = { libraryFilters = false },
            containerColor = DialogGlass, titleContentColor = Color.White, textContentColor = SubtleInk,
            title = { Text("Library") }, confirmButton = { TextButton(onClick = { libraryFilters = false }, modifier = Modifier.jaskFocusRing(), colors = neutralTextButton()) { Text("Done") } },
            text = { Column {
                listOf("All", "Channels", "Movies", "Shows", "Downloads").forEach { name ->
                    TextButton(onClick = { section = name; libraryFilters = false }, modifier = Modifier.jaskFocusRing(), colors = neutralTextButton()) { Text(name) }
                }
                collections.forEach { collection -> TextButton(onClick = { section = "collection:${collection.id}"; libraryFilters = false }, modifier = Modifier.jaskFocusRing(), colors = neutralTextButton()) { Text(collection.name) } }
                TextButton(onClick = { sort = when (sort) { "Name" -> "Rating"; "Rating" -> "Year"; else -> "Name" } }, modifier = Modifier.jaskFocusRing(), colors = neutralTextButton()) { Text("Sort: $sort") }
                TextButton(onClick = { libraryFilters = false; creating = true }, modifier = Modifier.jaskFocusRing(), colors = neutralTextButton()) { Text("New collection") }
            } })
        if (collectionId != null) {
            val selected = collections.firstOrNull { it.id == collectionId }
            if (selected != null) Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(selected.name, modifier = Modifier.weight(1f), color = TitleInk,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                app.opentv.ui.JaskGlassCircle(Icons.Filled.Edit, "Rename collection", size = 40.dp) { editor = selected }
                Spacer(Modifier.width(8.dp))
                app.opentv.ui.JaskGlassCircle(Icons.Filled.Delete, "Delete collection", size = 40.dp) { confirmDelete = selected }
            }
        } else if (section != "Downloads" && !app.opentv.ui.LocalJaskTouchShell.current) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                TextButton(onClick = { sorting = true }, modifier = Modifier.jaskFocusRing(), colors = neutralTextButton()) { Text("Sort: $sort") }
                JaskMenu(sorting, { sorting = false }, listOf("Name", "Rating", "Year").map { option ->
                    JaskMenuItem(option, checked = option == sort) { sort = option }
                })
            }
        }

        if (section == "Downloads") {
            DownloadsScreen(onPlay = onResume)
        } else {
            val showChannels = collectionId != null || section == "All" || section == "Channels"
            val showMovies = collectionId != null || section == "All" || section == "Movies"
            val showShows = collectionId != null || section == "All" || section == "Shows"
            val showDownloads = collectionId != null || section == "All"
            val isEmpty = (!showChannels || savedChannels.isEmpty()) &&
                (!showMovies || orderedMovies.isEmpty()) &&
                (!showShows || orderedShows.isEmpty()) &&
                (!showDownloads || downloadsHere.isEmpty())
            if (isEmpty) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("Nothing saved here. Star a title or channel, then add it to a collection.",
                        color = SubtleInk)
                }
            } else LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (showChannels && savedChannels.isNotEmpty()) {
                    item(key = "head-channels") { Heading("Channels", savedChannels.size) }
                    items(savedChannels, key = { "channel-${it.id}" }) { channel ->
                        val ref = SavedRef("channel", channel.sourceId, channel.streamId)
                        LibraryRow(channel.shownName, channel.qualityLabel, { onPlayChannel(channel) },
                            onOrganize = { organize = ref },
                            onMap = { mapChannel = channel },
                            onRemove = if (collectionId != null) ({ scope.launch {
                                dao.remove(collectionId, ref.kind, ref.sourceId, ref.key)
                            } }) else ({ scope.launch {
                                val variants = channelsToShow.filter { it.sourceId == channel.sourceId &&
                                    (it.streamId == channel.streamId ||
                                        (channel.groupKey.isNotBlank() && it.groupKey == channel.groupKey)) }
                                variants.forEach { variant ->
                                    graph.catalogRepository.setChannelFavourite(variant.id, false)
                                    removeFromCollections(SavedRef("channel", variant.sourceId, variant.streamId))
                                }
                            } }))
                    }
                }
                if (showMovies && orderedMovies.isNotEmpty()) {
                    item(key = "head-movies") { Heading("Movies", orderedMovies.size) }
                    items(orderedMovies, key = { "movie-${it.id}" }) { movie ->
                        val ref = SavedRef("movie", movie.sourceId, movie.streamId)
                        // Saved rows are the provider's own entries: its name cleaned, its year and its score.
                        LibraryRow(cleanShowTitle(movie.name), listOfNotNull(movie.year?.toString()).joinToString(" · "),
                            { onOpenMovie(movie) }, rating = movie.rating, onOrganize = { organize = ref },
                            onRemove = { scope.launch {
                                if (collectionId != null) dao.remove(collectionId, ref.kind, ref.sourceId, ref.key)
                                else {
                                    graph.catalogRepository.setMovieFavourite(movie.id, false)
                                    removeFromCollections(ref)
                                }
                            } })
                    }
                }
                if (showShows && orderedShows.isNotEmpty()) {
                    item(key = "head-shows") { Heading("Shows", orderedShows.size) }
                    items(orderedShows, key = { "show-${it.id}" }) { show ->
                        val ref = SavedRef("show", show.sourceId, show.seriesId)
                        LibraryRow(cleanShowTitle(show.name), listOfNotNull(show.year?.toString()).joinToString(" · "),
                            { onOpenSeries(show) }, rating = show.rating, onOrganize = { organize = ref },
                            onRemove = { scope.launch {
                                if (collectionId != null) dao.remove(collectionId, ref.kind, ref.sourceId, ref.key)
                                else {
                                    graph.database.series().setFavourite(show.id, false)
                                    removeFromCollections(ref)
                                }
                            } })
                    }
                }
                if (showDownloads && downloadsHere.isNotEmpty()) {
                    item(key = "head-downloads") { Heading("Downloaded", downloadsHere.size) }
                    items(downloadsHere, key = { "download-${it.download.mediaKey}" }) { download ->
                        val ref = SavedRef("download", 0L, download.download.mediaKey)
                        LibraryRow(download.download.title, "Available offline",
                            onOpen = { download.fileUri?.let { uri ->
                                onResume(download.download.mediaKey, uri, download.download.title)
                            } }, onOrganize = { organize = ref },
                            onRemove = if (collectionId != null) ({ scope.launch {
                                dao.remove(collectionId, ref.kind, ref.sourceId, ref.key)
                            } }) else null)
                    }
                }
            }
        }
    }

    mapChannel?.let { channel ->
        ChannelMappingDialog(channel) { mapChannel = null }
    }

    if (creating || editor != null) {
        val original = editor
        var name by remember(creating, original?.id) { mutableStateOf(original?.name.orEmpty()) }
        val normalized = name.trim()
        val valid = normalized.isNotBlank() && normalized.length <= 60 &&
            collections.none { it.id != original?.id && it.name.equals(normalized, ignoreCase = true) }
        AlertDialog(onDismissRequest = { creating = false; editor = null },
            containerColor = DialogGlass, titleContentColor = Color.White, textContentColor = SubtleInk,
            title = { Text(if (original == null) "New collection" else "Rename collection") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it.take(60) },
                    singleLine = true, label = { Text("Name") }, colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White,
                        focusedBorderColor = Color.White, unfocusedBorderColor = Color(0xFF6E6E6E),
                        focusedLabelColor = Color.White, unfocusedLabelColor = SubtleInk))
            }, confirmButton = {
                TextButton(enabled = valid, onClick = {
                    scope.launch {
                        if (original == null) {
                            val id = dao.create(UserCollection(name = normalized,
                                createdMillis = System.currentTimeMillis(), profileId = profileId))
                            section = "collection:$id"
                        } else dao.rename(original.id, normalized)
                    }
                    creating = false
                    editor = null
                }, modifier = Modifier.jaskFocusRing(), colors = neutralTextButton()) { Text("Save") }
            }, dismissButton = {
                TextButton(onClick = { creating = false; editor = null }, modifier = Modifier.jaskFocusRing(), colors = neutralTextButton()) { Text("Cancel") }
            })
    }

    confirmDelete?.let { item ->
        AlertDialog(onDismissRequest = { confirmDelete = null },
            containerColor = DialogGlass, titleContentColor = Color.White, textContentColor = SubtleInk,
            title = { Text("Delete ${item.name}?") },
            text = { Text("This removes the collection, not its channels, movies or downloads.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { dao.delete(item.id) }
                    if (collectionId == item.id) section = "All"
                    confirmDelete = null
                }, modifier = Modifier.jaskFocusRing(), colors = neutralTextButton()) { Text("Delete") }
            }, dismissButton = {
                TextButton(onClick = { confirmDelete = null }, modifier = Modifier.jaskFocusRing(), colors = neutralTextButton()) { Text("Cancel") }
            })
    }

    // The same frosted sheet as every other choice in the app.
    organize?.let { media ->
        app.opentv.ui.JaskSheet({ organize = null }) {
            Text("Add to collections", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            if (collections.isEmpty()) Text("Create a collection to organise this item.", color = SubtleInk,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            collections.forEach { collection ->
                val contains = memberships.any {
                    it.collectionId == collection.id && it.kind == media.kind && it.sourceId == media.sourceId && it.itemKey == media.key
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = .06f)).clickable {
                        scope.launch {
                            if (contains) dao.remove(collection.id, media.kind, media.sourceId, media.key)
                            else dao.add(media.asEntry(collection.id))
                        }
                    }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (contains) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                        null, tint = if (contains) Color.White else SubtleInk, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(collection.name, color = Color.White, style = MaterialTheme.typography.titleSmall)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                app.opentv.ui.settings.JaskPill("New collection", onClick = { organize = null; creating = true })
                app.opentv.ui.settings.JaskPill("Done", onClick = { organize = null })
            }
        }
    }
}

@Composable
private fun Heading(title: String, count: Int) {
    Text("$title · $count", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TitleInk,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
private fun LibraryRow(
    title: String, subtitle: String, onOpen: () -> Unit, onOrganize: () -> Unit,
    onRemove: (() -> Unit)?,
    onMap: (() -> Unit)? = null,
    /** The provider's score (these are its own rows, not matched to TMDB), drawn with its source's tag. */
    rating: Double? = null,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(onClick = onOpen).padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = TitleInk)
            JaskFactsLine(listOf(subtitle).filter(String::isNotBlank), rating, ProviderRating,
                color = SubtleInk, style = MaterialTheme.typography.bodySmall)
        }
        // Glass circles, as on the media page and the Library's sort button.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onMap != null) app.opentv.ui.JaskGlassCircle(Icons.Filled.Link, "Map channel identity", size = 40.dp, onClick = onMap)
            app.opentv.ui.JaskGlassCircle(Icons.Filled.Folder, "Organise in collections", size = 40.dp, onClick = onOrganize)
            if (onRemove != null) app.opentv.ui.JaskGlassCircle(Icons.Filled.Close, "Remove from this view", size = 40.dp, onClick = onRemove)
        }
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
}

private val TitleInk = Color(0xFFF4F4F4)
private val SubtleInk = Color(0xFFBDBDBD)
/** Dialogs on this page: dark neutral glass instead of the theme's tinted surface. */
private val DialogGlass = Color(0xFF242322)

/** Text buttons in white instead of the theme's periwinkle primary. */
@Composable
private fun neutralTextButton() = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF2F2F2), disabledContentColor = Color(0xFF6E6E6E))

/** Filter chips selected in grey/white glass, not the theme's tinted container. */
@Composable
private fun neutralChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = Color.Transparent, labelColor = SubtleInk,
    selectedContainerColor = Color.White.copy(alpha = 0.24f), selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White)
