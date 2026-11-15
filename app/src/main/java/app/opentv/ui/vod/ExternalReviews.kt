/*
 * jaskTV — GPL-3.0-or-later.
 * Extra source-labelled review scores (opt-in OMDb), shown only on title detail pages.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.parser.displayTitle
import app.opentv.data.remote.ReviewScores
import kotlinx.coroutines.CancellationException

@Composable
internal fun ExternalReviews(movie: Movie? = null, series: Series? = null) {
    val context = LocalContext.current
    val graph = remember(context) { ServiceLocator.get(context) }
    val apiKey by graph.settings.omdbApiKey.collectAsState()
    val id = if (movie != null) "movie:${movie.id}" else "series:${series?.id}"
    var scores by remember(id, apiKey) { mutableStateOf<ReviewScores?>(null) }
    LaunchedEffect(id, apiKey) {
        scores = null
        if (apiKey.isBlank()) return@LaunchedEffect
        try {
            val imdb = if (movie != null) graph.catalogRepository.imdbIdFor(movie)
                else series?.let { graph.catalogRepository.imdbIdFor(it) }
            val title = movie?.displayTitle ?: series?.displayTitle ?: return@LaunchedEffect
            val year = movie?.year ?: series?.year
            scores = graph.omdbClient.lookup(title, year, movie != null, imdb)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No score should never prevent playback or title details.
        }
    }
    val meta = scores ?: return
    if (meta.imdb == null && meta.rottenTomatoes == null && meta.metacritic == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text("Review scores", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            meta.imdb?.let { rating ->
                ScorePill("IMDb (via OMDb) · %.1f/10".format(rating))
            }
            meta.rottenTomatoes?.let { rating ->
                ScorePill("Rotten Tomatoes · $rating%")
            }
            meta.metacritic?.let { rating ->
                ScorePill("Metacritic · $rating/100")
            }
        }
        Text("Source: OMDb API. Scores are separate from the provider/TMDB rating.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ScorePill(label: String) {
    Surface(shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelMedium)
    }
}
