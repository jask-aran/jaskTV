/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

/**
 * Platform-neutral playback vocabulary.
 *
 * Nothing in this file touches Media3, Android or any other platform API. The Android player
 * ([PlayerController]) and a future desktop/iOS player both speak these types, so screens can
 * stay the same when the platform player is swapped.
 */

/** What the UI asks a player to play. */
data class PlaybackRequest(
    val url: String,
    val title: String,
    val userAgent: String,
    /** Live streams are never resumed; VOD is. */
    val startPositionMillis: Long = 0L,
    val isLive: Boolean = true,
)

/**
 * What the player is doing, in terms the UI understands.
 *
 * [Error.message] is written for humans and already explains the common provider failures; the
 * platform player is responsible for translating its native error into that sentence.
 */
sealed interface PlaybackState {
    data object Idle : PlaybackState
    data class Buffering(val title: String) : PlaybackState
    data class Playing(val title: String) : PlaybackState
    /** Playback stopped and retries are exhausted. */
    data class Error(
        val title: String,
        val message: String,
        val canRetry: Boolean,
        /** The server's HTTP status, when it answered with one (404: the file is gone). */
        val httpCode: Int? = null,
    ) : PlaybackState
}

data class VideoSizeInfo(
    val width: Int,
    val height: Int,
    val pixelWidthHeightRatio: Float = 1f,
)

/**
 * Volatile playback facts the UI polls or observes.
 *
 * Deliberately not part of [PlaybackState]: position and buffering change constantly, while the
 * state above changes only when the session does. A player that cannot report a value returns 0.
 */
data class PlaybackStatus(
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val videoSize: VideoSizeInfo = VideoSizeInfo(0, 0),
)

enum class PlayerTrackType { VIDEO, AUDIO, TEXT }

/**
 * One selectable audio, text or video track.
 *
 * [groupIndex] counts groups of the same [type], not all groups, so the UI can filter by type and
 * still address the right track. [index] is the track's position inside its group.
 */
data class PlayerTrack(
    val type: PlayerTrackType,
    val groupIndex: Int,
    val index: Int,
    val label: String?,
    val language: String?,
    val isSelected: Boolean,
    val isSupported: Boolean,
)

/** How the picture is fitted to the surface. */
enum class VideoScale {
    /** Letterbox: whole frame visible. */
    FIT,

    /** Crop the frame to fill the surface (Media3 calls this "zoom"). */
    CROP,

    /** Stretch the frame, ignoring its aspect ratio. */
    STRETCH;

    companion object {
        /**
         * Maps the int this app has always stored in settings (the Media3
         * `AspectRatioFrameLayout.RESIZE_MODE_*` values) onto the neutral enum, without making the
         * settings layer depend on Media3.
         */
        fun fromStored(value: Int): VideoScale = when (value) {
            3 -> STRETCH
            4 -> CROP
            else -> FIT
        }
    }
}
