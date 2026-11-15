/*
 * jaskTV — GPL-3.0-or-later.
 * Optional manual channel identity mapping. Does not change upstream provider stream/EPG data.
 */
package app.opentv.ui.channels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.ChannelAliasOverride
import app.opentv.data.model.shownName
import app.opentv.discovery.ChannelIdentity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChannelMappingDialog(channel: Channel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val graph = remember(context) { ServiceLocator.get(context) }
    val scope = rememberCoroutineScope()
    val dao = remember(graph) { graph.database.channelAliases() }
    val overrides by remember(dao) { dao.observeAll() }.collectAsState(initial = emptyList())
    val existing = overrides.firstOrNull {
        it.sourceId == channel.sourceId && it.streamId == channel.streamId
    }
    var query by remember(channel.id) { mutableStateOf(channel.shownName) }
    var label by remember(channel.id, existing?.label) { mutableStateOf(existing?.label.orEmpty()) }
    var results by remember(channel.id) { mutableStateOf(emptyList<ChannelIdentity>()) }
    var busy by remember(channel.id) { mutableStateOf(false) }
    LaunchedEffect(query) {
        results = emptyList()
        if (query.trim().length < 2) return@LaunchedEffect
        delay(300)
        busy = true
        try {
            results = graph.iptvOrgCatalog.suggestions(query)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            results = emptyList()
        } finally {
            busy = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Map channel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(channel.shownName, style = MaterialTheme.typography.titleSmall)
                Text("Match this playlist channel to an IPTV-org identity. Manual mappings survive provider refreshes and help find event broadcasters.",
                    style = MaterialTheme.typography.bodySmall)
                if (existing != null) {
                    Text("Mapped to ${existing.iptvOrgId}",
                        color = MaterialTheme.colorScheme.primary)
                }
                OutlinedTextField(query, onValueChange = { query = it },
                    singleLine = true, label = { Text("Find channel identity") })
                OutlinedTextField(label, onValueChange = { label = it.take(80) },
                    singleLine = true, label = { Text("Optional personal alias") })
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(results, key = { it.id }) { identity ->
                        Column(Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                dao.save(ChannelAliasOverride(channel.sourceId, channel.streamId,
                                    identity.id, label.trim()))
                                onDismiss()
                            }
                        }.padding(vertical = 9.dp)) {
                            Text(identity.name, style = MaterialTheme.typography.bodyLarge)
                            Text("${identity.id} · ${identity.country}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                    }
                }
                Text("IPTV-org identities are metadata, not verified streams or guaranteed EPG matches.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
        dismissButton = {
            if (existing != null) TextButton(onClick = {
                scope.launch {
                    dao.remove(channel.sourceId, channel.streamId)
                    onDismiss()
                }
            }) { Text("Clear mapping") }
        },
    )
}
