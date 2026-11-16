/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.opentv.core.AppSettings
import app.opentv.core.get
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration

/**
 * Which shell the app draws. Decided once at the root and read anywhere via [LocalLayoutClass],
 * so screens branch on one value instead of each re-deriving "am I on a TV?".
 *
 *  - [TV]: d-pad first, ten-foot sizes, side rails. The original OpenTV layout.
 *  - [PHONE]: touch first, portrait browsing, bottom navigation.
 *  - [TABLET]: touch, but wide enough for the side-by-side TV layout to fit.
 */
enum class LayoutClass {
    TV, PHONE, TABLET;

    val isTouch: Boolean get() = this != TV

    /**
     * Every class now uses the jaskTV screens; TV gets the wide (tablet) layout driven by the
     * d-pad. The original OpenTV TV screens remain behind this switch until they are removed.
     */
    val usesJaskShell: Boolean get() = true

    /** Tablet, TV and desktop: header navigation, shelves, side-by-side layouts. */
    val isWide: Boolean get() = this != PHONE
}

val LocalLayoutClass = staticCompositionLocalOf { LayoutClass.TV }

/** Keep landscape phones in the touch UI, but spend width rather than scarce height on chrome. */
@Composable
fun isPhoneLandscape(): Boolean = LocalLayoutClass.current == LayoutClass.PHONE &&
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/** Phones are anything under the standard 600dp smallest-width tablet breakpoint. */
@Composable
fun rememberLayoutClass(isTelevision: Boolean): LayoutClass {
    if (isTelevision) return LayoutClass.TV
    val sw = LocalConfiguration.current.smallestScreenWidthDp
    return if (sw < 600) LayoutClass.PHONE else LayoutClass.TABLET
}

/**
 * Interface size for wide layouts: everything below is drawn larger by raising the density (and
 * shrinking the reported screen size in dp to match). The layout class is decided before this, so
 * a scaled tablet stays a tablet. "auto": 115% on tablets, 130% on external displays (DeX,
 * glasses), 100% on TVs; phones are never scaled.
 */
@Composable
fun JaskUiScale(layout: LayoutClass, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val setting by remember(context) { AppSettings.get(context).uiScale }.collectAsState()
    val configuration = LocalConfiguration.current
    val scale = when {
        layout == LayoutClass.PHONE -> 1f
        setting != "auto" -> setting.toFloatOrNull()?.coerceIn(0.7f, 2f) ?: 1f
        layout == LayoutClass.TV -> 1f
        remember(context, configuration) { onExternalDisplay(context) } -> 1.3f
        else -> 1.15f
    }
    if (scale == 1f) { content(); return }
    val density = LocalDensity.current
    val scaled = remember(configuration, scale) {
        Configuration(configuration).apply {
            screenWidthDp = (screenWidthDp / scale).toInt()
            screenHeightDp = (screenHeightDp / scale).toInt()
            smallestScreenWidthDp = (smallestScreenWidthDp / scale).toInt()
            densityDpi = (densityDpi * scale).toInt()
        }
    }
    CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale),
        LocalConfiguration provides scaled, content = content)
}
