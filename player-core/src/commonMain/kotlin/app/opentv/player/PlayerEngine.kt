/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * The player boundary.
 *
 * Everything the screens need from "something that plays video" is here. The Android build plugs
 * in [PlayerController] (Media3/ExoPlayer); a Mac build can plug in a VLC or libmpv engine, an iOS
 * build AVPlayer, without changing a screen. The implementations own their own decoding, surface
 * and track discovery; callers only see this contract.
 *
 * Rules for an implementation:
 * - [play] may be called while another stream is loading. It replaces the current one; the engine
 *   must never stack decoders.
 * - [release] is final. The UI creates one engine per player screen.
 * - [positionMillis], [durationMillis] and [bufferedPositionMillis] return 0 when unknown; they are
 *   polled from the composition, so they must be cheap and never throw after [release].
 * - [status] must change when playback starts, pauses or the video size becomes known, because
 *   picture-in-picture and the control bar are driven from it rather than from raw player events.
 */
interface PlayerEngine {
    val state: StateFlow<PlaybackState>
    val tracks: StateFlow<List<PlayerTrack>>
    val status: StateFlow<PlaybackStatus>

    /** The platform's video surface. On Android this wraps a `PlayerView`. */
    val surface: PlayerSurfaceHost

    val isSeekable: Boolean
    val positionMillis: Long
    val durationMillis: Long
    val bufferedPositionMillis: Long
    val volume: Float

    /**
     * @param debounce when true (the default for channel surfing) the switch waits briefly so
     * rapid presses collapse into a single tune. Pass false for a deliberate selection.
     */
    fun play(request: PlaybackRequest, debounce: Boolean = true)

    fun retry()
    fun setPlaying(playing: Boolean)
    fun stop()
    fun release()

    fun seekTo(positionMillis: Long)
    fun seekBy(deltaMillis: Long)
    fun seekBackward()
    fun seekForward()
    fun setVolume(volume: Float)

    fun setSubtitlesEnabled(enabled: Boolean)
    fun selectTrack(track: PlayerTrack)
    fun disableText()
}

/** Draws the engine's video. One implementation per platform toolkit. */
interface PlayerSurfaceHost {
    /**
     * @param transparent preview panes want the channel logo to show through until the first
     * frame arrives. It is required rather than defaulted: the Compose compiler crashes on
     * default arguments in `@Composable` interface methods (Google issue 317490247).
     */
    @Composable
    fun Content(
        modifier: Modifier,
        scale: VideoScale,
        transparent: Boolean,
    )
}

/**
 * Per-screen engine options.
 *
 * These used to be constructor flags on [PlayerController]. They describe *this player screen*,
 * not the platform, so they belong on the factory call.
 */
data class PlayerEngineConfig(
    val subtitlesEnabled: Boolean = true,
    /** Guide preview: shallow buffer, harder debounce, muted by the caller. */
    val preview: Boolean = false,
    /** Keep a short back-buffer so live pause/rewind works. */
    val dvr: Boolean = false,
    /** Playing a recording that is still being written. */
    val liveRecording: Boolean = false,
    /** Route `smb://` media through the NAS reader. */
    val smb: Boolean = false,
    /** Route `optvrec://` media through the tail-following reader. */
    val growingRecording: Boolean = false,
)

/**
 * The platform swap point.
 *
 * Screens obtain engines from [app.opentv.core.ServiceLocator.Graph] rather than constructing
 * [PlayerController]; when a desktop target exists, its graph supplies a VLC-backed factory and
 * none of the screens change.
 */
interface PlayerEngineFactory {
    fun create(
        scope: CoroutineScope,
        config: PlayerEngineConfig = PlayerEngineConfig(),
    ): PlayerEngine
}
