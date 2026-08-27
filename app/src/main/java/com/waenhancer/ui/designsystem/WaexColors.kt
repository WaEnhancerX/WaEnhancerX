package com.waenhancer.ui.designsystem

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Stable
data class WaexColors(
    val primary: Color,
    val primaryContainer: Color,
    val onPrimary: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceDim: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val error: Color,
    val errorContainer: Color,
    val onError: Color,
    val onErrorContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val isLight: Boolean
) {
    val isDark: Boolean get() = !isLight
}

val lightColors = WaexColors(
    primary = Color(0xFF008069), // WhatsApp Teal
    primaryContainer = Color(0xFFE1F3EF),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF), // Pure stark white
    onBackground = Color(0xFF0F0F0F), // Almost black
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F0F0F),
    surfaceDim = Color(0xFFF5F5F5),
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF5F5F5),
    surfaceContainerHighest = Color(0xFFEAEAEA),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFFE0E0E0),
    outlineVariant = Color(0xFFEAEAEA),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onError = Color(0xFFFFFFFF),
    onErrorContainer = Color(0xFF93000A),
    secondary = Color(0xFF4A4A4A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0F0F0),
    onSecondaryContainer = Color(0xFF1A1A1A),
    isLight = true
)

val darkColors = WaexColors(
    primary = Color(0xFF00A884), // WhatsApp Dark Teal
    primaryContainer = Color(0xFF005140),
    onPrimary = Color(0xFF00372D),
    background = Color(0xFF000000), // True pitch black / AMOLED
    onBackground = Color(0xFFF5F5F5), // High contrast text
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF5F5F5),
    surfaceDim = Color(0xFF111111),
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1C1C1C),
    surfaceContainerHighest = Color(0xFF242424),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF333333),
    outlineVariant = Color(0xFF222222),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF690005),
    onError = Color(0xFF690005),
    onErrorContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFB0B0B0),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF222222),
    onSecondaryContainer = Color(0xFFE0E0E0),
    isLight = false
)

val LocalWaexColors = staticCompositionLocalOf { lightColors }
