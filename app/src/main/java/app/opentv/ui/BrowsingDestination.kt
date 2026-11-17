/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/**
 * Non-player destinations own one safe viewport for both chrome and content, regardless of layout
 * class or rotation. Keep this INSIDE each destination: padding the NavHost would also constrain
 * fullscreen video/PiP and make outgoing screens jump during navigation to/from the player.
 */
fun NavGraphBuilder.browsingDestination(
    route: String,
    edgeToEdgeOnTouch: Boolean = false,
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route) { entry ->
        // Touch shell and media pages draw under the system bars (they pad their own chrome and
        // content); only side cutouts/three-button bars are kept clear. TV keeps the full safe area.
        // The sides come from the window directly (see [rememberSideInsets]); top and bottom from Compose.
        val sides = rememberSideInsets()
        if (edgeToEdgeOnTouch && LocalLayoutClass.current.isTouch)
            BrowsingViewport(sides) { content(entry) }
        else BrowsingViewport(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical).union(sides)) { content(entry) }
    }
}

/**
 * safeDrawing is the union (not the sum) of system bars, display cutout and IME. Applying and
 * consuming it here protects interactive bounds on all four physical edges, including a left/right
 * three-button bar. Descendant Material navigation bars/inset modifiers see only the remainder,
 * so portrait does not reserve the navigation bar twice. The Activity's background stays edge-to-edge.
 */
@Composable
internal fun BrowsingViewport(
    insets: WindowInsets = WindowInsets.safeDrawing,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(insets)) {
        content()
    }
}

/**
 * The left and right safe area (side cutout, a side navigation bar), read from the window each time the screen
 * lays out. Not Compose's own insets: those hold back updates while a system-bar animation runs, and closing the
 * player shows the bars and rotates back to portrait at the same moment, which left the landscape cutout's width
 * stuck on the left of the page (a black strip, everything pushed right) until the app restarted.
 */
@Composable
internal fun rememberSideInsets(): WindowInsets {
    val view = androidx.compose.ui.platform.LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    fun read(): Pair<Int, Int> {
        val i = androidx.core.view.ViewCompat.getRootWindowInsets(view)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
        return (i?.left ?: 0) to (i?.right ?: 0)
    }
    var sides by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(read()) }
    androidx.compose.runtime.DisposableEffect(view) {
        val root = view.rootView
        val listener = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> val now = read(); if (now != sides) sides = now }
        root.addOnLayoutChangeListener(listener)
        // Rotation lays the root out again after the new insets arrive; read once more after that settles.
        val late = Runnable { sides = read() }
        root.postDelayed(late, 600)
        onDispose { root.removeOnLayoutChangeListener(listener); root.removeCallbacks(late) }
    }
    // And after every rotation or resize, once now and once more when any bar animation has finished.
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    androidx.compose.runtime.LaunchedEffect(config.orientation, config.screenWidthDp) {
        sides = read(); kotlinx.coroutines.delay(400); sides = read(); kotlinx.coroutines.delay(800); sides = read()
    }
    return with(density) { WindowInsets(left = sides.first.toDp(), right = sides.second.toDp()) }
}
