/*
 * jaskTV — GPL-3.0-or-later. Derived from OpenTV Mobile.
 * A saved-items library with persistent user-defined collections.
 */
package app.opentv.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
        else -> moviesToShow.sortedBy { it.name.lowercase() }
    }
    val orderedShows = when (sort) {
        "Rating" -> showsToShow.sortedWith(compareByDescending<Series> { it.rating ?: -1.0 }.thenBy { it.name })
        "Year" -> showsToShow.sortedWith(compareByDescending<Series> { it.year ?: 0 }.thenBy { it.name })
        else -> showsToShow.sortedBy { it.name.lowercase() }
    }

    var editor by remember { mutableStateOf<UserCollection?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<UserCollection?>(null) }
    var organize by remember { mutableStateOf<SavedRef?>(null) }
    var mapChannel by remember { mutableStateOf<Channel?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("All", "Channels", "Movies", "Shows", "Downloads").forEach { name ->
                FilterChip(selected = section == name, onClick = { section = name },
                    label = { Text(name) })
            }
            collections.forEach { collection ->
                FilterChip(selected = collectionId == collection.id,
                    onClick = { section = "collection:${collection.id}" },
                    label = { Text(collection.name, maxLines = 1) },
                    leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) })
            }
            TextButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Collection")
            }
        }
        if (collectionId != null) {
            val selected = collections.firstOrNull { it.id == collectionId }
            if (selected != null) Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(selected.name, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { editor = selected }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Rename collection")
                }
                IconButton(onClick = { confirmDelete = selected }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete collection")
                }
            }
        } else if (section != "Downloads") {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                TextButton(onClick = { sorting = true }) { Text("Sort: $sort") }
                DropdownMenu(expanded = sorting, onDismissRequest = { sorting = false }) {
                    listOf("Name", "Rating", "Year").forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = {
                            sort = option
                            sorting = false
                        })
                    }
                }
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        LibraryRow(movie.name, listOfNotNull(movie.year?.toString(),
                                movie.rating?.let { "★ %.1f".format(it) }).joinToString(" · "),
                            { onOpenMovie(movie) }, onOrganize = { organize = ref },
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
                        LibraryRow(show.name, listOfNotNull(show.year?.toString(),
                                show.rating?.let { "★ %.1f".format(it) }).joinToString(" · "),
                            { onOpenSeries(show) }, onOrganize = { organize = ref },
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
            title = { Text(if (original == null) "New collection" else "Rename collection") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it.take(60) },
                    singleLine = true, label = { Text("Name") })
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
                }) { Text("Save") }
            }, dismissButton = {
                TextButton(onClick = { creating = false; editor = null }) { Text("Cancel") }
            })
    }

    confirmDelete?.let { item ->
        AlertDialog(onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${item.name}?") },
            text = { Text("This removes the collection, not its channels, movies or downloads.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { dao.delete(item.id) }
                    if (collectionId == item.id) section = "All"
                    confirmDelete = null
                }) { Text("Delete") }
            }, dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            })
    }

    organize?.let { media ->
        AlertDialog(onDismissRequest = { organize = null },
            title = { Text("Add to collections") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (collections.isEmpty()) Text("Create a collection to organise this item.")
                    collections.forEach { collection ->
                        val contains = memberships.any {
                            it.collectionId == collection.id && it.kind == media.kind &&
                                it.sourceId == media.sourceId && it.itemKey == media.key
                        }
                        Row(Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                if (contains) dao.remove(collection.id, media.kind, media.sourceId, media.key)
                                else dao.add(media.asEntry(collection.id))
                            }
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = contains, onCheckedChange = null)
                            Text(collection.name)
                        }
                    }
                }
            }, confirmButton = {
                TextButton(onClick = { organize = null }) { Text("Done") }
            }, dismissButton = {
                TextButton(onClick = { organize = null; creating = true }) { Text("New collection") }
            })
    }
}

@Composable
private fun Heading(title: String, count: Int) {
    Text("$title · $count", style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
private fun LibraryRow(
    title: String, subtitle: String, onOpen: () -> Unit, onOrganize: () -> Unit,
    onRemove: (() -> Unit)?,
    onMap: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(onClick = onOpen).padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) Text(subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onMap != null) IconButton(onClick = onMap) {
            Icon(Icons.Filled.Link, contentDescription = "Map channel identity")
        }
        IconButton(onClick = onOrganize) {
            Icon(Icons.Filled.Folder, contentDescription = "Organise in collections")
        }
        if (onRemove != null) IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = "Remove from this view")
        }
    }
    HorizontalDivider()
}
