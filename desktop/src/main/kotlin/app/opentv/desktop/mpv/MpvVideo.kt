/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.desktop.mpv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.opentv.player.VideoScale

/**
 * Draws the latest mpv frame into the Compose scene.
 *
 * mpv already fits the picture into the buffer it is given, so the bitmap fills the canvas. The
 * scale mode is applied by mpv (`panscan` / `video-aspect-override`) rather than by Compose, which
 * keeps the scaling in the same place as the decoder's colour conversion.
 */
@Composable
internal fun MpvVideo(
    modifier: Modifier,
    scale: VideoScale,
    engine: MpvPlayerEngine,
) {
    val frame by engine.frames.collectAsState()

    Box(
        modifier
            .background(Color.Black)
            .onSizeChanged {
                engine.setViewport(it.width, it.height)
                engine.setVideoScale(scale, it.width, it.height)
            },
    ) {
        LaunchedEffect(scale) {
            engine.setVideoScale(scale, 0, 0)
        }

        val image = frame
        if (image != null) {
            Canvas(Modifier.fillMaxSize()) {
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
    }
}
