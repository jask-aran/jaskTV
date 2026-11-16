/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import app.opentv.desktop.mpv.MpvPlayerEngineFactory
import app.opentv.player.PlaybackRequest
import app.opentv.player.PlaybackState
import app.opentv.player.VideoScale

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "jaskTV",
        state = rememberWindowState(width = 1280.dp, height = 720.dp),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            DesktopShell()
        }
    }
}

/**
 * Temporary desktop shell.
 *
 * This is deliberately a URL box and a video surface, nothing else: the TV/desktop UI is owned by
 * the other agent. Everything below the shell (window, libmpv engine, surface, lifecycle) is the
 * part that is meant to stay.
 */
@Composable
private fun DesktopShell() {
    val engineScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val engineResult = remember { runCatching { MpvPlayerEngineFactory().create(engineScope) } }
    val engine = engineResult.getOrNull()
    var url by remember { mutableStateOf(System.getenv("JASKTV_URL").orEmpty()) }
    val state = engine?.state?.collectAsState()?.value

    // Development convenience: JASKTV_URL both fills the box and starts playback, so launching
    // the window is enough to see whether the engine works.
    LaunchedEffect(engine) {
        val initial = System.getenv("JASKTV_URL").orEmpty()
        if (engine != null && initial.isNotBlank()) {
            engine.play(
                PlaybackRequest(
                    url = initial.trim(),
                    title = "Desktop playback",
                    userAgent = "jaskTV/0.1 (Desktop)",
                ),
                debounce = false,
            )
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0A0A0C))) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                label = { Text("Stream URL") },
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = engine != null && url.isNotBlank(),
                onClick = {
                    engine?.play(
                        PlaybackRequest(
                            url = url.trim(),
                            title = "Desktop playback",
                            userAgent = "jaskTV/0.1 (Desktop)",
                        ),
                        debounce = false,
                    )
                },
            ) {
                Text("Play")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = when (val s = state) {
                    is PlaybackState.Buffering -> "Buffering…"
                    is PlaybackState.Playing -> "Playing"
                    is PlaybackState.Error -> "Error: ${s.message}"
                    else -> engineResult.exceptionOrNull()?.message?.lineSequence()?.firstOrNull()
                        ?: "Idle"
                },
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
            )
        }

        Box(Modifier.fillMaxSize()) {
            if (engine != null) {
                DisposableEffect(engine) {
                    onDispose {
                        engine.release()
                        engineScope.cancel()
                    }
                }
                engine.surface.Content(Modifier.fillMaxSize(), VideoScale.FIT, transparent = false)
            } else {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("libmpv is not available", color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        engineResult.exceptionOrNull()?.message.orEmpty(),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
