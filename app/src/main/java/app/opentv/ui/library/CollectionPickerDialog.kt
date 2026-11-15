/*
 * jaskTV — GPL-3.0-or-later.
 * Direct save-to-collection from movie and series details.
 */
package app.opentv.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.opentv.core.ServiceLocator
import app.opentv.data.model.CollectionEntry
import app.opentv.data.model.UserCollection
import kotlinx.coroutines.launch

@Composable
fun CollectionPickerDialog(
    kind: String,
    sourceId: Long,
    itemKey: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val graph = remember(context) { ServiceLocator.get(context) }
    val dao = remember(graph) { graph.database.collections() }
    val profileId by graph.settings.activeProfileId.collectAsState()
    val scope = rememberCoroutineScope()
    val collections by remember(dao, profileId) { dao.observeCollections(profileId) }.collectAsState(initial = emptyList())
    val entries by remember(dao) { dao.observeAllEntries() }.collectAsState(initial = emptyList())
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    if (creating) {
        val candidate = name.trim()
        val valid = candidate.isNotEmpty() && candidate.length <= 60 &&
            collections.none { it.name.equals(candidate, ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New collection") },
            text = { OutlinedTextField(name, onValueChange = { name = it.take(60) },
                singleLine = true, label = { Text("Collection name") }) },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    scope.launch {
                        val id = dao.create(UserCollection(name = candidate,
                            createdMillis = System.currentTimeMillis(), profileId = profileId))
                        dao.add(CollectionEntry(id, kind, sourceId, itemKey))
                        onDismiss()
                    }
                }) { Text("Create and save") }
            },
            dismissButton = {
                TextButton(onClick = { creating = false }) { Text("Back") }
            },
        )
    } else AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save to collection") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (collections.isEmpty()) {
                    Text("No collections yet. Create one to save this title.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                collections.forEach { item ->
                    val saved = entries.any { it.collectionId == item.id && it.kind == kind &&
                        it.sourceId == sourceId && it.itemKey == itemKey }
                    Row(Modifier.fillMaxWidth().clickable {
                        scope.launch {
                            if (saved) dao.remove(item.id, kind, sourceId, itemKey)
                            else dao.add(CollectionEntry(item.id, kind, sourceId, itemKey))
                        }
                    }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = saved, onCheckedChange = null)
                        Text(item.name, modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = { creating = true }) { Text("New collection") }
        },
    )
}
