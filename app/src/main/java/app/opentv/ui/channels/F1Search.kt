package app.opentv.ui.channels

import app.opentv.ui.jaskFocusLift
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.opentv.data.model.Channel
import app.opentv.data.model.shownName
import app.opentv.livetv.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun F1SessionRow(session: F1Session, discovery: F1Discovery, onPlay: (Channel) -> Unit) {
    var expanded by remember(session.id) { mutableStateOf(false) }
    var candidates by remember(session.id) { mutableStateOf(emptyList<F1Discovery.Candidate>()) }
    var loading by remember(session.id) { mutableStateOf(false) }
    var failed by remember(session.id) { mutableStateOf(false) }
    LaunchedEffect(expanded, session.id) {
        if (expanded) {
            loading = true; failed = false
            try { candidates = discovery.candidates(session) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { failed = true }
            finally { loading = false }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.jaskFocusLift(1.02f).fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp)) {
            Text("${session.grandPrix} · ${session.session}", style = MaterialTheme.typography.titleMedium)
            val time = Instant.ofEpochMilli(session.startUtcMillis).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm z"))
            Text("$time · scheduled start", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (expanded) "Hide channels" else "Find available channels", style = MaterialTheme.typography.labelLarge)
        }
        if (expanded) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (failed) Text("Channel discovery unavailable", Modifier.padding(12.dp))
            else if (candidates.isEmpty()) Text("No channel matches found. The session is still scheduled.", Modifier.padding(12.dp))
            candidates.forEach { candidate ->
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.jaskFocusLift(1.02f).weight(1f).clickable { onPlay(candidate.channel) }.padding(vertical = 12.dp)) {
                        Text(candidate.channel.shownName)
                        Text(candidate.evidence.label, style = MaterialTheme.typography.labelMedium)
                        Text(candidate.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AddToMyTvButton(candidate.channel)
                }
            }
        }
        HorizontalDivider()
    }
}
