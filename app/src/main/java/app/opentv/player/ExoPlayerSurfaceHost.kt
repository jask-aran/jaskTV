/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Android video surface: the Media3 `PlayerView`, with its own controls off because the screens
 * draw the transport bar themselves.
 *
 * The desktop and iOS builds provide their own [PlayerSurfaceHost]; this class is the only place
 * a screen's video can meet Media3's view layer.
 */
@OptIn(UnstableApi::class)
internal class ExoPlayerSurfaceHost(
    private val controller: PlayerController,
) : PlayerSurfaceHost {

    @Composable
    override fun Content(modifier: Modifier, scale: VideoScale, transparent: Boolean) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    subtitleView?.setUserDefaultStyle()
                    subtitleView?.setUserDefaultTextSize()
                    if (transparent) {
                        // Preview panes draw the channel logo behind the video, so the view must
                        // not paint a black shutter over it before the first frame arrives.
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { view ->
                view.player = controller.player
                view.resizeMode = scale.toResizeMode()
            },
        )
    }

    private fun VideoScale.toResizeMode(): Int = when (this) {
        VideoScale.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoScale.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoScale.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
}
