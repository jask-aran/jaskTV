/*
 * jaskTV personal library, based on OpenTV Mobile. GPL-3.0-or-later.
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
import app.opentv.ui.downloads.DownloadsScreen
import kotlinx.coroutines.launch

/** Explicit favourites and offline files, not algorithmic recommendations. */
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
    val scope = rememberCoroutineScope()
    var section by rememberSaveable { mutableStateOf("All") }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("All", "Channels", "Movies", "Shows", "Downloads").forEach { name ->
                FilterChip(selected = section == name, onClick = { section = name }, label = { Text(name) })
            }
        }

        if (section == "Downloads") {
            DownloadsScreen(onPlay = onResume)
        } else {
            val distinctChannels = channels.distinctBy { ch ->
                ch.groupKey.takeIf { it.isNotBlank() } ?: "id:${ch.id}"
            }
            val hasChannels = section in listOf("All", "Channels") && distinctChannels.isNotEmpty()
            val hasMovies = section in listOf("All", "Movies") && movies.isNotEmpty()
            val hasShows = section in listOf("All", "Shows") && series.isNotEmpty()
            if (!hasChannels && !hasMovies && !hasShows) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("Nothing saved yet. Favourite a channel, movie or show to add it here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (hasChannels) {
                    item(key = "heading-channels") { Heading("Channels", distinctChannels.size) }
                    items(distinctChannels, key = { "channel-${it.id}" }) { ch ->
                        SavedRow(ch.shownName, ch.qualityLabel, onOpen = { onPlayChannel(ch) }) {
                            scope.launch { graph.catalogRepository.setChannelFavourite(ch.id, false) }
                        }
                    }
                }
                if (hasMovies) {
                    item(key = "heading-movies") { Heading("Movies", movies.size) }
                    items(movies, key = { "movie-${it.id}" }) { movie ->
                        SavedRow(movie.name, movie.year?.toString().orEmpty(), onOpen = { onOpenMovie(movie) }) {
                            scope.launch { graph.catalogRepository.setMovieFavourite(movie.id, false) }
                        }
                    }
                }
                if (hasShows) {
                    item(key = "heading-shows") { Heading("Shows", series.size) }
                    items(series, key = { "series-${it.id}" }) { show ->
                        SavedRow(show.name, show.year?.toString().orEmpty(), onOpen = { onOpenSeries(show) }) {
                            scope.launch { graph.database.series().setFavourite(show.id, false) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Heading(name: String, count: Int) {
    Text("$name · $count", style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
private fun SavedRow(title: String, subtitle: String, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(onClick = onOpen).padding(vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) Text(subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Star, contentDescription = "Remove from library") }
    }
    HorizontalDivider()
}
