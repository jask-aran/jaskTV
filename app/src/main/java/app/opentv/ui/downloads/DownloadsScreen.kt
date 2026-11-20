/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.downloads

import app.opentv.ui.jaskFocusRing
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Download
import app.opentv.data.repo.DownloadRepository
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * The Downloads tab: what's saved on this device for offline viewing, films and series apart.
 * Series are grouped by show, then ordered by season and episode. Tap a finished one to play it
 * from the file; the bin deletes it (and cancels it if it's still downloading).
 */
@Composable
fun DownloadsScreen(onPlay: (mediaKey: String, url: String, title: String) -> Unit) {
    val context = LocalContext.current
    val repo = remember { ServiceLocator.get(context).downloadRepository }
    val items by remember { repo.observe() }.collectAsState(initial = emptyList())
    var confirmDelete by remember { mutableStateOf<Download?>(null) }
    val scope = rememberCoroutineScope()

    val films = items.filter { it.download.kind == KIND_MOVIE }
    val episodes = items.filter { it.download.kind == KIND_EPISODE }
        .sortedWith(compareBy({ it.download.seriesTitle }, { it.download.season }, { it.download.episode }))
    val used = items.sumOf { if (it.state == DownloadRepository.State.DONE) it.totalBytes else it.bytesSoFar }

    // One list: what's still downloading first (always in view), then films, then shows by show.
    val active = items.filter { it.state != DownloadRepository.State.DONE }
        .sortedWith(compareBy({ it.state == DownloadRepository.State.FAILED }, { it.state == DownloadRepository.State.QUEUED }, { it.place }))
    val filmsLabel = stringResource(R.string.nav_movies)
    val doneFilms = films.filter { it.state == DownloadRepository.State.DONE }
    val doneEpisodes = episodes.filter { it.state == DownloadRepository.State.DONE }
    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(
                R.string.dl_used,
                Formatter.formatShortFileSize(context, used),
                Formatter.formatShortFileSize(context, repo.freeBytes()),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        AllFilesAccessRow(repo)
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.dl_empty), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                fun heading(key: String, text: String) = item(key = key) {
                    Text(text, style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                }
                if (active.isNotEmpty()) {
                    heading("h:active", "In progress · ${active.size}")
                    items(active, key = { "a" + it.download.id }) { item ->
                        DownloadRow(item, onPlay = { play(item, onPlay) }, onDelete = { confirmDelete = item.download })
                    }
                }
                if (doneFilms.isNotEmpty()) {
                    heading("h:films", "$filmsLabel · ${doneFilms.size}")
                    items(doneFilms, key = { it.download.id }) { item ->
                        DownloadRow(item, onPlay = { play(item, onPlay) }, onDelete = { confirmDelete = item.download })
                    }
                }
                // One heading per show: copies from different places name it differently ("Silo", "Silo (2023)").
                doneEpisodes.groupBy { it.download.seriesTitle.orEmpty().replace(Regex("""\s*\((19|20)\d\d\)\s*$"""), "").trim() }.forEach { (show, eps) ->
                    heading("h:$show", show)
                    items(eps, key = { it.download.id }) { item ->
                        DownloadRow(item, onPlay = { play(item, onPlay) }, onDelete = { confirmDelete = item.download })
                    }
                }
            }
        }
    }

    confirmDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            text = { Text(stringResource(R.string.dl_delete_confirm, d.title)) },
            confirmButton = {
                TextButton(onClick = { scope.launch { repo.remove(d) }; confirmDelete = null }, modifier = Modifier.jaskFocusRing()) {
                    Text(stringResource(R.string.dl_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }, modifier = Modifier.jaskFocusRing()) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

private fun play(item: DownloadRepository.Item, onPlay: (String, String, String) -> Unit) {
    val file = item.fileUri ?: return
    if (item.state == DownloadRepository.State.DONE) onPlay(item.download.mediaKey, file, item.download.title)
}

@Composable
private fun DownloadRow(item: DownloadRepository.Item, onPlay: () -> Unit, onDelete: () -> Unit) {
    val d = item.download
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.07f))
            .clickable(enabled = item.state == DownloadRepository.State.DONE, onClick = onPlay)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = d.posterUrl,
            contentDescription = null,
            modifier = Modifier.size(width = 40.dp, height = 58.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // Episodes: "The Mentalist · S1 E1 · Pilot" (the provider's "(2008) (US) - S01E01 - Pilot" cleaned up).
            val epNo = d.episode
            val name = if (d.season != null && epNo != null) listOfNotNull(d.seriesTitle?.takeIf { item.state != DownloadRepository.State.DONE },
                "S${d.season} E${d.episode}", app.opentv.ui.vod.cleanEpisodeTitle(d.title, epNo)
                    .replace(Regex("""^.*S\d{1,2}\s*E\d{1,3}\s*[-–:]\s*""", RegexOption.IGNORE_CASE), "").takeIf(String::isNotBlank)).joinToString(" · ")
                else d.title
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = androidx.compose.ui.graphics.Color.White)
            val total = if (item.totalBytes > 0) item.totalBytes else 0L
            val size = total.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) }
            // What the file is and where it came from: "1080p · HEVC · 4.2 GB · strong8k · EN".
            val about = listOfNotNull(item.tech, size, item.origin, d.tag?.takeIf(String::isNotBlank)).joinToString(" · ")
            if (about.isNotBlank()) Text(about, style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(statusText(item), style = MaterialTheme.typography.bodySmall,
                color = if (item.state == DownloadRepository.State.FAILED) MaterialTheme.colorScheme.error
                else if (item.state == DownloadRepository.State.DONE) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.5f)
                else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f))
            if (item.state == DownloadRepository.State.RUNNING || item.state == DownloadRepository.State.PAUSED ||
                (item.state == DownloadRepository.State.QUEUED && item.bytesSoFar > 0)) {
                Spacer(Modifier.height(6.dp))
                if (item.live?.phase == app.opentv.data.repo.DownloadEngine.Phase.CONNECTING && item.progress == 0f)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f))
                else LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth(),
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f), drawStopIndicator = {})
            }
            if (item.state == DownloadRepository.State.FAILED && !item.outOfSpace || item.outOfSpace) {
                val scope = rememberCoroutineScope()
                TextButton(onClick = { scope.launch { ServiceLocator.get(context).downloadRepository.retry(d) } },
                    modifier = Modifier.jaskFocusRing()) { Text("Try again", color = androidx.compose.ui.graphics.Color.White) }
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.jaskFocusRing()) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.dl_delete), tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun statusText(item: DownloadRepository.Item): String {
    val live = item.live
    val pct = (item.progress * 100).toInt()
    val got = if (item.totalBytes > 0) "$pct%" else null
    return when {
        item.state == DownloadRepository.State.DONE ->
            "Downloaded" + (item.finishedAt.takeIf { it > 0 }?.let { " " + whenText(it) } ?: "")
        live != null -> when (live.phase) {
            app.opentv.data.repo.DownloadEngine.Phase.CONNECTING -> listOfNotNull(got, "Connecting…").joinToString(" · ")
            app.opentv.data.repo.DownloadEngine.Phase.RUNNING -> listOfNotNull(got, rate(live.speed),
                if (live.speed > 1_000 && item.totalBytes > 0) left(((item.totalBytes - item.bytesSoFar) / live.speed).toLong()) else null).joinToString(" · ")
            app.opentv.data.repo.DownloadEngine.Phase.RETRY_WAIT -> {
                val s = ((live.retryAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                listOfNotNull(got, "${live.why ?: "Interrupted"} · trying again in $s s").joinToString(" · ")
            }
            app.opentv.data.repo.DownloadEngine.Phase.HELD, app.opentv.data.repo.DownloadEngine.Phase.NO_NETWORK ->
                listOfNotNull(got, live.why ?: "Waiting").joinToString(" · ")
        }
        item.state == DownloadRepository.State.QUEUED -> listOfNotNull(
            when (item.place) { 0, 1 -> "Next"; else -> "${ordinal(item.place)} in line" },
            got?.let { "$it saved, carries on from there" }).joinToString(" · ")
        // From Android's own downloader (before jaskTV had its own): its states.
        item.state == DownloadRepository.State.RUNNING -> listOfNotNull(stringResource(R.string.dl_downloading, pct), speedText(item)).joinToString(" · ")
        item.state == DownloadRepository.State.PAUSED -> stringResource(
            if (item.reason == android.app.DownloadManager.PAUSED_WAITING_TO_RETRY) R.string.dl_retrying else R.string.dl_paused)
        item.outOfSpace -> stringResource(R.string.dl_failed_space)
        else -> item.error ?: stringResource(R.string.dl_failed)
    }
}

private fun rate(speed: Double) = when { speed >= 1e6 -> "%.1f MB/s".format(speed / 1e6); speed >= 1e3 -> "%.0f KB/s".format(speed / 1e3); else -> null }
private fun left(seconds: Long) = when { seconds < 60 -> "under a minute left"; seconds < 5400 -> "${seconds / 60} min left"; else -> "${seconds / 3600} h ${seconds % 3600 / 60} min left" }
private fun ordinal(n: Int) = "$n" + when { n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }
/** "today 5:52 pm", "yesterday", "on 16 Nov". */
private fun whenText(at: Long): String {
    val days = (java.time.LocalDate.now().toEpochDay() - java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay())
    val time = java.time.format.DateTimeFormatter.ofPattern("h:mm a").format(java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())).lowercase()
    return when (days) { 0L -> "today $time"; 1L -> "yesterday"; else -> "on " + java.time.format.DateTimeFormatter.ofPattern("d MMM").format(java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())) }
}

/**
 * "1.4 MB/s · 20 min left": the download's speed over the last few seconds (smoothed), and the time left at that
 * speed. A torrent's speed swings with its peers, so this is a live estimate, not a promise. Null until measured.
 */
@Composable
private fun speedText(item: DownloadRepository.Item): String? {
    val sample = remember(item.download.id) { androidx.compose.runtime.mutableStateOf(item.bytesSoFar to System.currentTimeMillis()) }
    var speed by remember(item.download.id) { androidx.compose.runtime.mutableDoubleStateOf(0.0) }
    androidx.compose.runtime.LaunchedEffect(item.bytesSoFar) {
        val (bytes, at) = sample.value
        val now = System.currentTimeMillis()
        if (now - at >= 3_000) {
            val measured = (item.bytesSoFar - bytes).coerceAtLeast(0) / ((now - at) / 1000.0)
            speed = if (speed == 0.0) measured else speed * 0.6 + measured * 0.4
            sample.value = item.bytesSoFar to now
        }
    }
    if (speed < 1_000) return null
    val rate = if (speed >= 1e6) "%.1f MB/s".format(speed / 1e6) else "%.0f KB/s".format(speed / 1e3)
    val left = item.totalBytes - item.bytesSoFar
    if (item.totalBytes <= 0 || left <= 0) return rate
    val minutes = (left / speed / 60).toLong()
    val time = when { minutes < 1 -> "under a minute"; minutes < 90 -> "$minutes min"; else -> "${minutes / 60} h ${minutes % 60} min" }
    return "$rate · $time left"
}

/** The toast after tapping Download: started, or why it wasn't (with sizes when known). */
fun startMessage(context: android.content.Context, title: String, result: DownloadRepository.Start): String =
    when (result) {
        DownloadRepository.Start.Started -> context.getString(R.string.dl_started, title)
        DownloadRepository.Start.Queued -> context.getString(R.string.dl_queued_toast, title)
        is DownloadRepository.Start.NoSpace ->
            if (result.neededBytes > 0) context.getString(
                R.string.dl_no_space_sized,
                Formatter.formatShortFileSize(context, result.neededBytes),
                Formatter.formatShortFileSize(context, result.freeBytes),
            ) else context.getString(R.string.dl_no_space)
    }

/**
 * The download control for one film or episode: "Download" when it isn't saved, live progress
 * while it downloads, a done tick when it's on the device. [compact] draws just an icon (episode
 * rows); otherwise a labelled button (the film page).
 */
@Composable
fun DownloadControl(
    mediaKey: String,
    buildDownload: () -> Download,
    compact: Boolean = false,
    small: Boolean = false,
    /** Opens the Download sheet (every route, the user picks) instead of saving [buildDownload] straight away. */
    onStart: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val repo = graph.downloadRepository
    val item by remember(mediaKey) { repo.observe(mediaKey) }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val start: () -> Unit = onStart ?: {
        // "Download finished" is a notification (Android 13+ needs the permission).
        app.opentv.core.NotificationPermission.askIfNeeded(context)
        scope.launch {
            val d = buildDownload()
            val result = repo.enqueue(d)
            android.widget.Toast.makeText(context, startMessage(context, d.title, result), android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val state = item?.state
    if (compact) {
        // small: quieter, tighter icon for list rows (episode lists), so it doesn't dominate the row.
        val box = if (small) 36.dp else 48.dp
        val icon = if (small) 18.dp else 24.dp
        when (state) {
            DownloadRepository.State.DONE -> Box(Modifier.size(box), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.DownloadDone, stringResource(R.string.dl_downloaded), Modifier.size(icon),
                    tint = if (small) LocalContentColor.current.copy(alpha = 0.7f) else MaterialTheme.colorScheme.primary)
            }
            DownloadRepository.State.RUNNING, DownloadRepository.State.QUEUED, DownloadRepository.State.PAUSED ->
                Box(Modifier.size(box), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { item?.progress ?: 0f }, modifier = Modifier.size(icon), strokeWidth = if (small) 2.dp else 3.dp)
                }
            else -> IconButton(onClick = start, modifier = Modifier.jaskFocusRing().then(Modifier.size(box))) {
                Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.dl_download), modifier = Modifier.size(icon),
                    tint = if (small) LocalContentColor.current.copy(alpha = 0.55f) else LocalContentColor.current)
            }
        }
        return
    }
    OutlinedButton(onClick = { if (state == null || state == DownloadRepository.State.FAILED) start() }, modifier = Modifier.jaskFocusRing()) {
        Icon(
            if (state == DownloadRepository.State.DONE) Icons.Filled.DownloadDone else Icons.Filled.Download,
            contentDescription = null,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when (state) {
                null -> stringResource(R.string.dl_download)
                else -> statusText(item!!)
            },
        )
    }
}

const val KIND_MOVIE = "MOVIE"
const val KIND_EPISODE = "EPISODE"


/**
 * Asks for All files access when this install doesn't have it: downloads are ordinary files in Download/jaskTV shared
 * by every jaskTV install, and without it Android shows each install only the files it saved itself. Rechecked when
 * the screen comes back from Settings; the folder is looked at again once it's on.
 */
@Composable
private fun AllFilesAccessRow(repo: DownloadRepository) {
    val context = LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var granted by remember { mutableStateOf(repo.canSeeAll()) }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val now = repo.canSeeAll()
                if (now && !granted) repo.rescan()
                granted = now
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (granted || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
    val intent = remember {
        val own = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
        val list = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        listOf(own, list).firstOrNull { it.resolveActivity(context.packageManager) != null }
    } ?: return // a TV without the Settings page: grant it over adb (appops set <package> MANAGE_EXTERNAL_STORAGE allow)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("See every download in Download/jaskTV", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleSmall)
            Text("Including ones another jaskTV install saved. Android asks for All files access for that.",
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { runCatching { context.startActivity(intent) } }, modifier = Modifier.jaskFocusRing()) {
            Text("Allow", color = androidx.compose.ui.graphics.Color.White)
        }
    }
}
