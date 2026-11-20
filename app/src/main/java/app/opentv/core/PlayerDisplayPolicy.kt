/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

/** Session policy, deliberately independent of screen dimensions (PiP changes those dimensions). */
enum class PlayerOrientation { LANDSCAPE, PORTRAIT, SYSTEM, LOCKED }
enum class PlayerOrientationTarget { SENSOR_LANDSCAPE, SENSOR_PORTRAIT, FULL_USER, LANDSCAPE, PORTRAIT, REVERSE_LANDSCAPE, REVERSE_PORTRAIT }

data class PlayerOrientationSession(
    val active: Boolean = false,
    val orientation: PlayerOrientation = PlayerOrientation.LANDSCAPE,
    /** Surface rotation 0..3, captured when Lock is selected, not on configuration changes. */
    val lockedRotation: Int = 0,
    val naturalLandscape: Boolean = false,
) {
    fun destination(playing: Boolean): PlayerOrientationSession = when {
        playing == active -> this // Channel/episode switches are not new playback sessions.
        else -> PlayerOrientationSession(active = playing)
    }

    fun select(value: PlayerOrientation, rotation: Int, naturalLandscape: Boolean = false) =
        copy(orientation = value, lockedRotation = rotation.coerceIn(0, 3), naturalLandscape = naturalLandscape)

    fun target(television: Boolean, inPip: Boolean, multiWindow: Boolean): PlayerOrientationTarget? {
        if (!active || television || inPip || multiWindow) return null
        return when (orientation) {
            PlayerOrientation.LANDSCAPE -> PlayerOrientationTarget.SENSOR_LANDSCAPE
            PlayerOrientation.PORTRAIT -> PlayerOrientationTarget.SENSOR_PORTRAIT
            PlayerOrientation.SYSTEM -> PlayerOrientationTarget.FULL_USER
            PlayerOrientation.LOCKED -> {
                val landscape = (lockedRotation % 2 == 0) == naturalLandscape
                when {
                    landscape && lockedRotation >= 2 -> PlayerOrientationTarget.REVERSE_LANDSCAPE
                    landscape -> PlayerOrientationTarget.LANDSCAPE
                    lockedRotation >= 2 -> PlayerOrientationTarget.REVERSE_PORTRAIT
                    else -> PlayerOrientationTarget.PORTRAIT
                }
            }
        }
    }
}

/** Android accepts PiP aspect ratios only within [1/2.39, 2.39]. Account for anamorphic pixels. */
fun pipAspectRatio(width: Int, height: Int, pixelRatio: Float = 1f): Float {
    val ratio = width.toDouble() * pixelRatio / height
    return if (width <= 0 || height <= 0 || !ratio.isFinite() || ratio <= 0) 16f / 9f
    else ratio.coerceIn(1.0 / 2.39, 2.39).toFloat()
}

fun shouldEnterPlayerPip(eligible: Boolean, isPlaying: Boolean, supported: Boolean): Boolean =
    eligible && isPlaying && supported

fun mayPlayVideo(activityVisible: Boolean, inPip: Boolean): Boolean = activityVisible || inPip

/** A display mode as Android reports it. */
data class DisplayModeInfo(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

private val StandardRates = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 47.952f, 48f, 50f, 59.94f, 60f)

/** A measured frame rate snapped to the standard rate it is within 1.5% of, else null (too uncertain to switch on). */
fun snapFrameRate(measured: Float): Float? =
    StandardRates.minByOrNull { kotlin.math.abs(it - measured) }?.takeIf { kotlin.math.abs(it - measured) / it < 0.015f }

private fun multipleOf(refresh: Float, fps: Float): Boolean {
    val k = kotlin.math.round(refresh / fps)
    return k >= 1 && kotlin.math.abs(refresh - k * fps) / refresh < 0.001f
}

/**
 * The display mode to play [fps] video in: same resolution as [current], with a refresh rate that is a whole
 * multiple of the frame rate (each frame held for the same number of refreshes, so no judder). The highest such
 * rate wins (50 Hz over 25 Hz keeps the UI smooth). Null when [current] already suits, or nothing does.
 */
fun pickDisplayMode(modes: List<DisplayModeInfo>, current: DisplayModeInfo, fps: Float): DisplayModeInfo? {
    if (fps <= 0f || multipleOf(current.refreshRate, fps)) return null
    return modes.filter { it.width == current.width && it.height == current.height && multipleOf(it.refreshRate, fps) }
        .maxByOrNull { it.refreshRate }
}
