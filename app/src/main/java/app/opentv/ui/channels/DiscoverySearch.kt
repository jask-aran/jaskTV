/*
 * jaskTV — GPL-3.0-or-later. Shared search UI for phones and Android TV.
 */
package app.opentv.ui.channels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.opentv.core.ServiceLocator
import app.opentv.discovery.BroadcastDiscovery
import app.opentv.data.model.Channel
import app.opentv.data.model.shownName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Debounced shared programme/broadcaster discovery; no request while simply browsing tabs. */
@Composable
internal fun rememberBroadcastDiscovery(
    query: String, enabled: Boolean,
): Pair<Boolean, List<BroadcastDiscovery.Hit>> {
    val context = LocalContext.current
    val source = remember(context) { BroadcastDiscovery(context) }
    var hits by remember { mutableStateOf(emptyList<BroadcastDiscovery.Hit>()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(query, enabled) {
        hits = emptyList()
        loading = false
        if (enabled && query.trim().length >= 2) {
            delay(650L)
            loading = true
            try {
                hits = source.lookup(query)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // A missing EPG or external service should never prevent ordinary channel search.
                hits = emptyList()
            } finally {
                loading = false
            }
        }
    }
    return loading to hits
}

/** Focusable on TV remote, touch-operable on phone. Tapping a result starts playback. */
@Composable
internal fun DiscoveryResultRow(
    hit: BroadcastDiscovery.Hit,
    onPlayChannel: (Channel) -> Unit,
) {
    val context = LocalContext.current
    val catalog = remember(context) { ServiceLocator.get(context).catalogRepository }
    val scope = rememberCoroutineScope()
    var showMapping by remember(hit.channel.id) { mutableStateOf(false) }
    var favourite by remember(hit.channel.id, hit.channel.favourite) {
        mutableStateOf(hit.channel.favourite)
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable { onPlayChannel(hit.channel) }
            .padding(vertical = 8.dp)) {
            Text(hit.channel.shownName, style = MaterialTheme.typography.bodyLarge)
            Text(hit.explanation, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
        AddToMyTvButton(hit.channel)
        IconButton(onClick = { showMapping = true }) {
            Icon(Icons.Filled.Link, contentDescription = "Map channel identity")
        }
        IconButton(onClick = {
            val desired = !favourite
            favourite = desired
            scope.launch {
                try {
                    catalog.setChannelFavourite(hit.channel.id, desired)
                } catch (_: Exception) {
                    favourite = !desired
                }
            }
        }) {
            Icon(if (favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = if (favourite) "Remove favourite" else "Add favourite")
        }
    }
    if (showMapping) ChannelMappingDialog(hit.channel) { showMapping = false }
}
