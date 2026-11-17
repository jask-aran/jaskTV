/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.opentv.core.PipState
import app.opentv.core.pipAspectRatio
import app.opentv.player.PlayerEngine

data class PlayerPlaybackStatus(val isPlaying: Boolean, val playWhenReady: Boolean)

/** One PiP lease per player. Never infer playback from READY or a UI toggle. */
@Composable
fun BindPlayerPlayback(engine: PlayerEngine, nowPlaying: app.opentv.remote.NowPlaying.Item? = null, onBackground: () -> Unit = {}): PlayerPlaybackStatus {
    val status by engine.status.collectAsState()
    // What's playing, for the account's other devices and a phone used as a remote.
    LaunchedEffect(engine, nowPlaying) { nowPlaying?.let { app.opentv.remote.NowPlaying.set(it, engine) } }
    DisposableEffect(engine) { onDispose { app.opentv.remote.NowPlaying.clear(engine) } }
    val background by rememberUpdatedState(onBackground)
    val token = remember(engine) { Any() }

    DisposableEffect(engine) {
        PipState.attach(token) {
            engine.setPlaying(false)
            background()
        }
        onDispose { PipState.detach(token) }
    }

    // Also catches a delayed prepare/retry that sets playWhenReady after Home/Stop.
    LaunchedEffect(engine) {
        engine.status.collect { s ->
            if ((!PipState.owns(token) || !PipState.playbackAllowed) && s.playWhenReady) {
                engine.setPlaying(false)
                background()
            }
            PipState.update(
                token,
                s.isPlaying,
                pipAspectRatio(s.videoSize.width, s.videoSize.height, s.videoSize.pixelWidthHeightRatio),
            )
        }
    }

    return PlayerPlaybackStatus(status.isPlaying, status.playWhenReady)
}
