package app.opentv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.opentv.core.StatusBus

/**
 * The sync indicator: a spinner in the header while anything runs in the background (the jaskTV account,
 * a playlist provider's channels or movies, the guide, TMDB matching). Selecting it lists each job, where its
 * data comes from, how far along it is and how long it has taken. Hidden when the app is idle.
 */
@Composable
internal fun JaskSyncButton() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val jobs by StatusBus.jobs.collectAsState()
    val paused by app.opentv.core.SyncPause.paused.collectAsState()
    LaunchedEffect(Unit) { app.opentv.core.SyncPause.isPaused(context) }
    var open by remember { mutableStateOf(false) }
    // Shown while anything runs, and while syncing is paused (so it can be resumed from here).
    // Stays while its menu is open, so resuming (nothing running for a moment) doesn't pull it out from under focus.
    if (jobs.isEmpty() && !paused && !open) return
    // Ticks once a second while the list is open, so elapsed times count up.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(open) { while (open) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    Box {
        JaskGlassSurface(Modifier.size(48.dp), RoundedCornerShape(50), showFocus = true) {
            // A plain clickable circle: the app-wide ring (JaskIndication), as on the header's other circles.
            Box(Modifier.fillMaxSize().clip(CircleShape).clickable { open = true }
                .semantics { contentDescription = if (jobs.isEmpty()) "Syncing paused" else "Syncing: ${jobs.joinToString { it.title }}" },
                contentAlignment = Alignment.Center) {
                if (jobs.isEmpty() && paused) androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                    // Pause mark: two bars.
                    repeat(2) { Box(Modifier.size(5.dp, 16.dp).background(Color(0xFFF0F0F0), RoundedCornerShape(2.dp))) }
                }
                else SyncSpinner(jobs)
            }
        }
        val items = buildList {
            add(JaskMenuItem(if (jobs.isNotEmpty()) "Syncing" else if (paused) "Syncing paused" else "Up to date", header = true))
            jobs.forEach { job ->
                val parts = listOfNotNull(job.origin.label, job.detail, job.progress?.let { "${(it * 100).toInt()}%" }, elapsed(now - job.startedAt))
                add(JaskMenuItem(job.title, subtitle = parts.joinToString(" · "), keepOpen = true))
            }
            add(JaskMenuItem("Pause syncing", subtitle = "TV guide keeps updating", toggle = paused, keepOpen = true,
                onClick = { app.opentv.core.SyncPause.set(context, !paused) }))
        }
        JaskMenu(open, { open = false }, items)
    }
}

/** While the header is hidden (focus down on the page), a small spinner stays in the corner. Not focusable. */
@Composable
internal fun JaskSyncCorner(modifier: Modifier = Modifier) {
    val jobs by StatusBus.jobs.collectAsState()
    if (jobs.isEmpty()) return
    JaskGlassSurface(modifier.size(30.dp), RoundedCornerShape(50)) {
        Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.Center) { SyncSpinner(jobs, small = true) }
    }
}

@Composable
private fun SyncSpinner(jobs: List<StatusBus.Job>, small: Boolean = false) {
    val size = if (small) 16.dp else 20.dp
    val stroke = if (small) 2.dp else 2.5.dp
    // A real fraction when there's a single job that reports one; otherwise an indeterminate spinner.
    // At either end a determinate ring reads as an empty or closed circle, not as work going on: spin instead.
    val progress = jobs.singleOrNull()?.progress?.takeIf { it in 0.03f..0.97f }
    if (progress != null) CircularProgressIndicator({ progress }, Modifier.size(size), color = Color(0xFFF0F0F0),
        strokeWidth = stroke, trackColor = Color.White.copy(alpha = .2f))
    else CircularProgressIndicator(Modifier.size(size), color = Color(0xFFF0F0F0), strokeWidth = stroke, trackColor = Color.White.copy(alpha = .2f))
}

private fun elapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
}
