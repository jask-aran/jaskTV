/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * A deliberately dark, low-chroma palette.
 *
 * This is a living-room app: it is looked at in a dark room, from three metres away, often
 * for hours. Bright surfaces and saturated accents that read well on a phone in daylight are
 * actively unpleasant on a 55" panel at night, so everything here is anchored near-black with
 * a single restrained accent used only for focus and selection.
 */
// Neutral: jaskTV highlights are white and grey glass, never a coloured accent (Material controls that fall back on
// the scheme — spinners, switches, text buttons — then match the rest of the app).
private val Accent = Color(0xFFEDEDED)
private val AccentDim = Color(0xFF3A3A3C)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF0A0A0A),
    primaryContainer = AccentDim,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFA8A8A8),
    background = Color(0xFF07080C),
    onBackground = Color(0xFFE8EAF0),
    surface = Color(0xFF0D0F16),
    onSurface = Color(0xFFE8EAF0),
    surfaceVariant = Color(0xFF171A24),
    onSurfaceVariant = Color(0xFFA8AEC0),
    outline = Color(0xFF2A2F3D),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF1A0505),
)

/**
 * Light mode for anyone who wants it. By default TV boxes stay dark (a white living-room
 * screen at night is nobody's friend) — that default lives at the call site, so a user who
 * explicitly picks Light in settings gets it on any device.
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF3B4FCC),
    onPrimary = Color.White,
    background = Color(0xFFFBFBFE),
    onBackground = Color(0xFF13141A),
    surface = Color.White,
    onSurface = Color(0xFF13141A),
    surfaceVariant = Color(0xFFEEF0F6),
    onSurfaceVariant = Color(0xFF4A4F60),
    outline = Color(0xFFD3D7E2),
)

/** Scaled up from the Material defaults: ten-foot viewing needs bigger text than a phone. */
private val OpenTvTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

// Phone surfaces are neutral so artwork, rather than tinted controls, carries the colour.
private val PhoneDarkScheme = DarkScheme.copy(
    primary = Accent, onPrimary = Color(0xFF0A0A0A),
    primaryContainer = AccentDim, onPrimaryContainer = Color.White,
    secondary = Color(0xFF98989D), onSecondary = Color.White,
    secondaryContainer = Color(0xFF2C2C2E), onSecondaryContainer = Color.White,
    background = Color(0xFF000000), onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF0C0C0E), onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF1C1C1E), onSurfaceVariant = Color(0xFF98989D),
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF1C1C1E),
    surfaceContainer = Color(0xFF1C1C1E), surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF3A3A3C), surfaceTint = Color.Transparent,
    outline = Color(0xFF48484A), outlineVariant = Color(0xFF2C2C2E),
)
private val PhoneLightScheme = LightScheme.copy(
    primary = Color(0xFF007AFF), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0EEFF), onPrimaryContainer = Color(0xFF003E85),
    secondary = Color(0xFF8E8E93), secondaryContainer = Color(0xFFE5E5EA),
    onSecondaryContainer = Color(0xFF1C1C1E),
    background = Color(0xFFF2F2F7), onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFF9F9FB), onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFE5E5EA), onSurfaceVariant = Color(0xFF6C6C70),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFE5E5EA),
    surfaceContainer = Color(0xFFE5E5EA), surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White, surfaceTint = Color.Transparent,
    outline = Color(0xFFC7C7CC), outlineVariant = Color(0xFFD1D1D6),
)
private val PhoneTypography = OpenTvTypography.copy(
    titleLarge = TextStyle(fontSize = 25.sp, lineHeight = 30.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun OpenTvTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    phone: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = when {
            phone && darkTheme -> PhoneDarkScheme
            phone -> PhoneLightScheme
            darkTheme -> DarkScheme
            else -> LightScheme
        },
        typography = if (phone) PhoneTypography else OpenTvTypography,
        content = content,
    )
}
