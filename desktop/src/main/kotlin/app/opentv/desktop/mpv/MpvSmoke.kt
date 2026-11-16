/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.desktop.mpv

import app.opentv.player.PlaybackRequest
import app.opentv.player.PlaybackState
import app.opentv.player.PlayerTrackType
import kotlin.system.exitProcess

/**
 * Exercises the libmpv engine without Compose: load, render, pause, seek, volume and track
 * enumeration. The UI cannot be driven from a script, and "the engine reports Playing" is a much
 * weaker claim than "position advanced, stopped when paused and landed where we seeked".
 *
 * Run with `gradlew :desktop:smoke -PsmokeUrl=...`, or pass the media path as the first argument.
 */
fun main(args: Array<String>) {
    val url = args.firstOrNull()
        ?: System.getenv("JASKTV_SMOKE_URL")
        ?: System.getProperty("smoke.url")
    if (url.isNullOrBlank()) {
        println("usage: :desktop:smoke -PsmokeUrl=<file-or-http-url>")
        exitProcess(2)
    }

    val engine = MpvPlayerEngine()
    val failures = mutableListOf<String>()

    fun check(name: String, ok: Boolean, detail: String) {
        println((if (ok) "PASS " else "FAIL ") + name + " — " + detail)
        if (!ok) failures += name
    }

    /** Waits for [predicate], returning whether it became true before [timeoutMillis]. */
    fun waitFor(timeoutMillis: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return predicate()
    }

    try {
        // A viewport makes the render thread produce frames; without it the engine has nothing
        // to draw into and some streams will not advance.
        engine.setViewport(640, 360)
        engine.play(
            PlaybackRequest(
                url = url,
                title = "smoke",
                userAgent = "OpenTV/0.1 (Android)",
                isLive = false,
            ),
            debounce = false,
        )

        val started = waitFor(30_000) {
            engine.state.value is PlaybackState.Playing && engine.positionMillis > 250
        }
        val state = engine.state.value
        if (!started) {
            check(
                "load",
                false,
                "state=$state position=${engine.positionMillis} " +
                    "error=${(state as? PlaybackState.Error)?.message}",
            )
            return
        }
        check("load", true, "state=$state position=${engine.positionMillis}ms")

        val duration = engine.durationMillis
        check("duration", duration > 0, "${duration}ms")
        check("video size", engine.status.value.videoSize.width > 0, "${engine.status.value.videoSize}")

        // Pause must actually stop the clock, not just flip a flag. PlaybackState has no paused
        // variant by design; the UI reads status.isPlaying for that.
        val beforePause = engine.positionMillis
        engine.setPlaying(false)
        val paused = waitFor(3_000) { !engine.status.value.isPlaying }
        Thread.sleep(1_200)
        val afterPause = engine.positionMillis
        val drift = afterPause - beforePause
        check("pause", paused && drift < 400, "drift=${drift}ms paused=$paused position=$afterPause")

        engine.setPlaying(true)
        check(
            "resume",
            waitFor(5_000) { engine.status.value.isPlaying },
            "state=${engine.state.value} position=${engine.positionMillis}ms",
        )

        // Seek to the middle and allow mpv's keyframe/hard-seek behaviour some slack.
        if (duration > 4_000) {
            val target = duration / 2
            engine.seekTo(target)
            val landed = waitFor(10_000) {
                kotlin.math.abs(engine.positionMillis - target) < 2_500
            }
            check("seek", landed, "target=${target}ms actual=${engine.positionMillis}ms")

            val beforeStep = engine.positionMillis
            engine.seekBy(2_000)
            val stepped = waitFor(5_000) { engine.positionMillis > beforeStep + 1_000 }
            check("seekBy +2s", stepped, "before=${beforeStep}ms after=${engine.positionMillis}ms")
        }

        engine.setVolume(0.25f)
        check(
            "volume",
            waitFor(3_000) { kotlin.math.abs(engine.volume - 0.25f) < 0.02f },
            "${engine.volume}",
        )

        val tracks = engine.tracks.value
        println(
            "tracks: " + tracks.joinToString { track ->
                "${track.type}/${track.label ?: track.language ?: "?"}" +
                    if (track.isSelected) " (selected)" else ""
            },
        )
        check("tracks", tracks.isNotEmpty(), "${tracks.size} track(s)")
        check(
            "video track",
            tracks.any { it.type == PlayerTrackType.VIDEO },
            "types=" + tracks.map { it.type }.distinct(),
        )
    } finally {
        engine.release()
    }

    if (failures.isEmpty()) {
        println("SMOKE OK")
    } else {
        println("SMOKE FAILED: " + failures.joinToString())
        exitProcess(1)
    }
}
