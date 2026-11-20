/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import app.opentv.MainActivity
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.core.PlayerOrientation
import app.opentv.core.findActivity
import app.opentv.isRunningOnTelevision
import app.opentv.ui.JaskGlassCircle
import app.opentv.ui.JaskMenu
import app.opentv.ui.JaskMenuItem

/**
 * Live and every VOD source share the same preference and controls; no player is rebuilt. [size] matches the
 * round buttons beside these ([PlayerCircleSize] in the bottom row, 40dp next to the phone's top-bar tools).
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerDisplayControls(
    settings: AppSettings,
    onMenuVisibilityChanged: (Boolean) -> Unit = {},
    size: Dp = PlayerCircleSize,
    onInteraction: () -> Unit,
) {
    val resizeMode by settings.playerResizeMode.collectAsState()
    var sizeMenu by remember { mutableStateOf(false) }
    var orientationMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = context.findActivity() as? MainActivity
    DisposableEffect(sizeMenu, orientationMenu) {
        onMenuVisibilityChanged(sizeMenu || orientationMenu)
        onDispose { onMenuVisibilityChanged(false) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box {
            JaskGlassCircle(Icons.Filled.AspectRatio, stringResource(R.string.player_display_size), size = size) {
                sizeMenu = true; onInteraction()
            }
            JaskMenu(sizeMenu, { sizeMenu = false; onInteraction() }, listOf(
                AspectRatioFrameLayout.RESIZE_MODE_FIT to R.string.player_display_fit,
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM to R.string.player_display_fill,
                AspectRatioFrameLayout.RESIZE_MODE_FILL to R.string.player_display_stretch,
            ).map { (mode, label) ->
                JaskMenuItem(stringResource(label), checked = mode == resizeMode) { settings.setPlayerResizeMode(mode) }
            })
        }
        // Rotation on a living-room TV is neither useful nor reliably supported.
        if (activity != null && !isRunningOnTelevision(context)) {
            Box {
                JaskGlassCircle(Icons.Filled.ScreenRotation, stringResource(R.string.player_display_orientation), size = size) {
                    orientationMenu = true; onInteraction()
                }
                JaskMenu(orientationMenu, { orientationMenu = false; onInteraction() }, listOf(
                    PlayerOrientation.LANDSCAPE to R.string.player_display_landscape,
                    PlayerOrientation.PORTRAIT to R.string.player_display_portrait,
                    PlayerOrientation.SYSTEM to R.string.player_display_system,
                    PlayerOrientation.LOCKED to R.string.player_display_lock,
                ).map { (orientation, label) ->
                    JaskMenuItem(stringResource(label), checked = activity.playerOrientationSession.orientation == orientation) {
                        activity.selectPlayerOrientation(orientation)
                    }
                })
            }
        }
    }
}
