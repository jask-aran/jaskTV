/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.opentv.desktop.mpv.MpvPlayerEngineFactory
import app.opentv.player.PlaybackRequest
import app.opentv.player.PlaybackState
import app.opentv.player.VideoScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "jaskTV",
        state = rememberWindowState(width = 1440.dp, height = 900.dp),
    ) {
        var playing by remember { mutableStateOf<Pair<String, String>?>(null) }
        Box(Modifier.fillMaxSize()) {
            JaskDesktopApp(onPlay = { url, title -> playing = url to title })
            playing?.let { (url, title) -> Player(url, title) { playing = null } }
        }
    }
}

/** Full-window libmpv playback over the app; Back returns to where you were. */
@Composable
private fun Player(url: String, title: String, onClose: () -> Unit) {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val engine = remember { runCatching { MpvPlayerEngineFactory().create(scope) } }
    val e = engine.getOrNull()
    DisposableEffect(e) {
        e?.play(PlaybackRequest(url = url, title = title, userAgent = "OpenTV/0.1 (Android)", isLive = false), debounce = false)
        onDispose { e?.release(); scope.cancel() }
    }
    val state = e?.state?.collectAsState()?.value
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        e?.surface?.Content(Modifier.fillMaxSize(), VideoScale.FIT, transparent = false)
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(Color.Black.copy(alpha = .5f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, color = Color.White)
                val status = when (state) {
                    is PlaybackState.Buffering -> "Buffering…"
                    is PlaybackState.Error -> "Error: ${state.message}"
                    null -> engine.exceptionOrNull()?.message?.lineSequence()?.firstOrNull() ?: "libmpv not available"
                    else -> null
                }
                status?.let { Text(it, color = Color.White.copy(alpha = .7f)) }
            }
        }
    }
}
