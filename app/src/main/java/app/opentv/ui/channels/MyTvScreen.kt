package app.opentv.ui.channels

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.shownName
import app.opentv.livetv.MyTvRepository
import app.opentv.ui.ChannelsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Personal guide only. Never subscribes to ChannelsViewModel.rows or its catalogue-wide index. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyTvScreen(query: String, onPlay: (Channel) -> Unit, vm: ChannelsViewModel = viewModel()) {
    val onFindChannels: () -> Unit = app.opentv.ui.LocalJaskOpenSearch.current
    val context = LocalContext.current
    val graph = remember(context) { ServiceLocator.get(context) }
    val repository = remember(context) { MyTvRepository(context) }
    val saved by repository.saved.collectAsState(initial = emptyList())
    val guideVersion by graph.epgRepository.guideVersion.collectAsState()
    val sources by remember(graph) { graph.sourceRepository.observeAll() }.collectAsState(initial = emptyList())
    var entries by remember { mutableStateOf(emptyList<MyTvRepository.Entry>()) }
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var programme by remember { mutableStateOf<Pair<ChannelsViewModel.Row, Programme>?>(null) }
    var manage by remember { mutableStateOf(false) }
    var mapping by remember { mutableStateOf<MyTvRepository.Entry?>(null) }
    val filterRequest = app.opentv.ui.LocalJaskFilterRequest.current
    LaunchedEffect(filterRequest) { if (filterRequest > 0) manage = true }
    LaunchedEffect(saved, guideVersion, sources) { entries = saved.map { repository.resolve(it) } }
    LaunchedEffect(Unit) { vm.setCompactGuide(true); while (true) { now = System.currentTimeMillis(); delay(60_000) } }
    val rows = remember(entries) { entries.map { entry ->
        val channel = entry.primary ?: Channel(id = -entry.saved.identity.hashCode().toLong(), sourceId = 0,
            streamId = "", name = entry.saved.label, categoryId = null, logoUrl = null, epgChannelId = null, number = null, streamUrl = "")
        ChannelsViewModel.Row(channel.copy(displayName = entry.saved.label), entry.variants, null, null, emptyList(), entry.saved.identity)
    } }
    // Header search filters My TV in place and lists unsaved provider matches beneath (bounded LIKE query).
    val needle = query.trim()
    val searching = needle.length >= 2
    LaunchedEffect(needle) { vm.setSearchQuery(if (searching) needle else "") }
    val matches by vm.searchResults.collectAsState()
    val shownRows = remember(rows, needle) { if (!searching) rows else rows.filter { it.primary.shownName.contains(needle, true) || it.primary.name.contains(needle, true) } }
    val addable = remember(matches, entries, searching) {
        if (!searching) emptyList() else {
            val savedIds = entries.flatMap { it.variants }.map { it.id }.toSet()
            val savedKeys = entries.map { it.saved.identity }.toSet()
            matches.filter { m -> m.key !in savedKeys && m.variants.none { it.id in savedIds } }.take(40)
        }
    }
    val f1Query = searching && app.opentv.livetv.F1Schedule.recognizes(needle)
    fun play(channel: Channel) {
        if (entries.none { it.primary?.id == channel.id }) {
            app.opentv.player.PlaybackQueue.items = listOf(app.opentv.player.PlaybackQueue.Item(channel.id, channel.displayName, channel.logoUrl, channel.number))
            onPlay(channel); return
        }
        app.opentv.player.PlaybackQueue.items = rows.map { row ->
            app.opentv.player.PlaybackQueue.Item(row.primary.id, row.primary.displayName, row.primary.logoUrl, row.primary.number)
        }
        onPlay(channel)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("My TV", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if (saved.isNotEmpty()) TextButton(onClick = { manage = true }) { Text("Manage") }
            TextButton(onClick = onFindChannels) { Text("Add channels") }
        }
        if (searching && shownRows.isEmpty() && addable.isEmpty() && !f1Query) {
            Text(androidx.compose.ui.res.stringResource(app.opentv.R.string.jask_no_matches, needle), Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (searching) {
            PhoneTouchGuide(shownRows, now, vm, onPlay = { play(it.primary) }, onProgramme = { row, item -> programme = row to item },
                addable = addable, leadingCount = if (f1Query) 1 else 0) {
                if (f1Query) item(key = "f1") { F1Sessions(needle) { play(it) } }
            }
        } else if (saved.isEmpty()) {
            MyTvEmptyState(onFindChannels)
        } else if (rows.isEmpty()) {
            Column(Modifier.padding(24.dp)) {
                Text("Your saved channels are unavailable", style = MaterialTheme.typography.titleMedium)
                Text("Connect a source to watch them.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            PhoneTouchGuide(rows, now, vm, onPlay = { play(it.primary) }, onProgramme = { row, item -> programme = row to item })
        }
    }
    if (manage) ModalBottomSheet(onDismissRequest = { manage = false }) {
        Text("My TV channels", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).navigationBarsPadding()) {
            items(entries, key = { it.saved.identity }) { entry ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(entry.saved.label, style = MaterialTheme.typography.titleMedium)
                    Text(entry.saved.identity, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { scope.launch { repository.move(saved, saved.indexOf(entry.saved), -1) } }) { Text("Move up") }
                        TextButton(onClick = { scope.launch { repository.move(saved, saved.indexOf(entry.saved), 1) } }) { Text("Move down") }
                        TextButton(onClick = { scope.launch { repository.remove(entry.saved) } }) { Text("Remove") }
                    }
                    entry.variants.forEach { channel ->
                        TextButton(onClick = { scope.launch { repository.prefer(entry.saved, channel) } }) {
                            val source = sources.firstOrNull { it.id == channel.sourceId }?.name ?: "Source"
                            Text("${if (entry.primary?.id == channel.id) "✓ " else ""}$source · ${channel.qualityLabel.ifBlank { "Quality unknown" }}${if (channel.qualityLabel.isNotBlank()) " (inferred)" else ""}")
                        }
                    }
                    entry.primary?.let { channel ->
                        TextButton(onClick = { mapping = entry }) { Text("Map channel identity") }
                        Text("Guide: ${channel.epgOverrideId?.let { "manual · $it" } ?: channel.epgChannelId?.let { "provider · $it" } ?: channel.matchedEpgId?.let { "name match · $it" } ?: "unmapped"}", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    mapping?.let { entry -> entry.primary?.let { channel ->
        ChannelMappingDialog(channel) {
            mapping = null
            scope.launch { repository.applyManualMapping(entry.saved, channel) }
        }
    } }
    programme?.let { (row, item) ->
        ModalBottomSheet(onDismissRequest = { programme = null }) {
            Column(Modifier.fillMaxWidth().padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(item.title, style = MaterialTheme.typography.headlineSmall)
                val zone = java.time.ZoneId.systemDefault()
                Text("${PhoneGuideTimeline.timeLabel(item.startUtcMillis, zone)}–${PhoneGuideTimeline.timeLabel(item.endUtcMillis, zone)}")
                item.description?.takeIf(String::isNotBlank)?.let { Text(it) }
                TextButton(onClick = { programme = null; play(row.primary) }) { Text("Watch channel") }
            }
        }
    }
}

@Composable
internal fun MyTvEmptyState(onFindChannels: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text("Your TV, together", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Add channels to build your programme guide.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = onFindChannels) { Text("Find channels") }
    }
}

/** F1 sessions for a recognised query, shown above the channel rows. Schedule from Jolpica; candidates are advisory. */
@Composable
private fun F1Sessions(query: String, onPlay: (Channel) -> Unit) {
    val context = LocalContext.current
    val schedule = remember(context) { app.opentv.livetv.F1Schedule(context) }
    val discovery = remember(context) { app.opentv.livetv.F1Discovery(context) }
    var loading by remember { mutableStateOf(true) }
    var stale by remember { mutableStateOf(false) }
    var sessions by remember { mutableStateOf(emptyList<app.opentv.livetv.F1Session>()) }
    LaunchedEffect(Unit) {
        try { val result = schedule.upcoming(); sessions = result.sessions; stale = result.stale }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { sessions = emptyList(); stale = true }
        finally { loading = false }
    }
    val shown = sessions.filter {
        when {
            query.contains("qual", true) -> it.session.contains("qualifying", true)
            query.contains("practice", true) -> it.session.contains("Practice")
            query.contains("sprint", true) -> it.session.contains("Sprint")
            else -> true
        }
    }.take(8)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Formula 1", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        if (!loading && shown.isEmpty()) Text(if (stale) "Schedule unavailable offline" else "No upcoming sessions in the next 45 days",
            style = MaterialTheme.typography.bodySmall)
        if (stale && shown.isNotEmpty()) Text("Cached schedule · update unavailable", style = MaterialTheme.typography.bodySmall)
        shown.forEach { session -> androidx.compose.runtime.key(session.id) { F1SessionRow(session, discovery, onPlay) } }
    }
}
