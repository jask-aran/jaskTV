/*
 * jaskTV — GPL-3.0-or-later. Derived from OpenTV Mobile.
 * Saved items and downloads, without a recommendation feed.
 */
package app.opentv.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.shownName
import app.opentv.data.repo.DownloadRepository
import app.opentv.ui.downloads.DownloadsScreen
import kotlinx.coroutines.launch

/** A manual collection of saved channels, movies, shows and local videos. */
@Composable
fun LibraryScreen(
    onPlayChannel: (Channel) -> Unit,
    onOpenMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onResume: (String, String, String) -> Unit,
) {
    val context = LocalContext.current
    val graph = remember(context) { ServiceLocator.get(context) }
    val channels by remember(graph) { graph.catalogRepository.observeFavouriteChannels() }
        .collectAsState(initial = emptyList())
    val movies by remember(graph) { graph.database.movies().observeFavourites() }
        .collectAsState(initial = emptyList())
    val series by remember(graph) { graph.database.series().observeFavourites() }
        .collectAsState(initial = emptyList())
    val downloads by remember(graph) { graph.downloadRepository.observe() }
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var section by rememberSaveable { mutableStateOf("All") }
    // Keep channel variants collapsed, consistent with the main channel browser.
    val savedChannels = remember(channels) {
        channels.distinctBy { it.groupKey.takeIf(String::isNotBlank) ?: "id:${it.id}" }
    }
    val availableDownloads = remember(downloads) {
        downloads.filter { it.state == DownloadRepository.State.DONE && it.fileUri != null }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("All", "Channels", "Movies", "Shows", "Downloads").forEach { name ->
                FilterChip(selected = section == name, onClick = { section = name },
                    label = { Text(name) })
            }
        }

        if (section == "Downloads") {
            // Original transfer queue supports retry, cancellation, storage status and deletion.
            DownloadsScreen(onPlay = onResume)
        } else {
            val empty = (section !in listOf("All", "Channels") || savedChannels.isEmpty()) &&
                (section !in listOf("All", "Movies") || movies.isEmpty()) &&
                (section !in listOf("All", "Shows") || series.isEmpty()) &&
                (section != "All" || availableDownloads.isEmpty())
            if (empty) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("Nothing saved here yet. Star a channel, movie or show to add it.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (section in listOf("All", "Channels") && savedChannels.isNotEmpty()) {
                    item(key = "head-channels") { Heading("Channels", savedChannels.size) }
                    items(savedChannels, key = { "c-${it.id}" }) { channel ->
                        SavedRow(channel.shownName, channel.qualityLabel,
                            onOpen = { onPlayChannel(channel) },
                            onRemove = { scope.launch {
                                // A logical channel can have several quality variants.
                                channels.filter { candidate ->
                                    candidate.id == channel.id ||
                                        (channel.groupKey.isNotBlank() && candidate.groupKey == channel.groupKey)
                                }.forEach { graph.catalogRepository.setChannelFavourite(it.id, false) }
                            } })
                    }
                }
                if (section in listOf("All", "Movies") && movies.isNotEmpty()) {
                    item(key = "head-movies") { Heading("Movies", movies.size) }
                    items(movies, key = { "m-${it.id}" }) { movie ->
                        SavedRow(movie.name, movie.year?.toString().orEmpty(),
                            onOpen = { onOpenMovie(movie) },
                            onRemove = { scope.launch {
                                graph.catalogRepository.setMovieFavourite(movie.id, false)
                            } })
                    }
                }
                if (section in listOf("All", "Shows") && series.isNotEmpty()) {
                    item(key = "head-shows") { Heading("Shows", series.size) }
                    items(series, key = { "s-${it.id}" }) { show ->
                        SavedRow(show.name, show.year?.toString().orEmpty(),
                            onOpen = { onOpenSeries(show) },
                            onRemove = { scope.launch {
                                graph.database.series().setFavourite(show.id, false)
                            } })
                    }
                }
                if (section == "All" && availableDownloads.isNotEmpty()) {
                    item(key = "head-downloads") { Heading("Downloaded", availableDownloads.size) }
                    items(availableDownloads, key = { "dl-${it.download.id}" }) { item ->
                        SavedRow(item.download.title, "Available offline",
                            onOpen = {
                                item.fileUri?.let { uri ->
                                    onResume(item.download.mediaKey, uri, item.download.title)
                                }
                            })
                    }
                }
            }
        }
    }
}

@Composable
private fun Heading(title: String, count: Int) {
    Text("$title · $count", style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
private fun SavedRow(title: String, subtitle: String, onOpen: () -> Unit, onRemove: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(onClick = onOpen).padding(vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
        if (onRemove != null) IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Star, contentDescription = "Remove from library")
        }
    }
    HorizontalDivider()
}
