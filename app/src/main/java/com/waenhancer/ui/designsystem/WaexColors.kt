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
    // Keep these roles in sync with WaEnhancer's values-night palette.
    primary = Color(0xFF25D366),
    primaryContainer = Color(0xFF1B5E20),
    onPrimary = Color(0xFF000000),
    background = Color(0xFF121212),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFF121212),
    surfaceContainerLow = Color(0xFF1E1E1E),
    surfaceContainer = Color(0xFF252525),
    surfaceContainerHigh = Color(0xFF2C2C2C),
    surfaceContainerHighest = Color(0xFF333333),
    onSurfaceVariant = Color(0xFFB3B3B3),
    outline = Color(0xFF64706C),
    outlineVariant = Color(0xFF2C2C2C),
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFF690005),
    onError = Color(0xFFFFFFFF),
    onErrorContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFF25D366),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF1B5E20),
    onSecondaryContainer = Color(0xFFFFFFFF),
    isLight = false
)

val LocalWaexColors = staticCompositionLocalOf { lightColors }
