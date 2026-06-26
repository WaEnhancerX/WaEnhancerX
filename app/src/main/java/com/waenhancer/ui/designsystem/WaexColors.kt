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
)

val lightColors = WaexColors(
    primary = Color(0xFF008069),
    primaryContainer = Color(0xFFD2F3E7),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111B21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111B21),
    surfaceDim = Color(0xFFF0F2F5),
    surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainer = Color(0xFFF0F2F5),
    surfaceContainerHigh = Color(0xFFE9EBEE),
    surfaceContainerHighest = Color(0xFFDFE2E6),
    onSurfaceVariant = Color(0xFF667781),
    outline = Color(0xFFE9EBEE),
    outlineVariant = Color(0xFFDFE2E6),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onError = Color(0xFFFFFFFF),
    onErrorContainer = Color(0xFF93000A),
    secondary = Color(0xFF128C7E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD2F3E7),
    onSecondaryContainer = Color(0xFF002118),
    isLight = true
)

val darkColors = WaexColors(
    primary = Color(0xFF25D366),
    primaryContainer = Color(0xFF005238),
    onPrimary = Color(0xFF001F12),
    background = Color(0xFF0B141A),
    onBackground = Color(0xFFE9EDEF),
    surface = Color(0xFF111B21),
    onSurface = Color(0xFFE9EDEF),
    surfaceDim = Color(0xFF0B141A),
    surfaceContainerLow = Color(0xFF111B21),
    surfaceContainer = Color(0xFF1F2C34),
    surfaceContainerHigh = Color(0xFF2A3942),
    surfaceContainerHighest = Color(0xFF3B4A54),
    onSurfaceVariant = Color(0xFF8696A0),
    outline = Color(0xFF2A3942),
    outlineVariant = Color(0xFF1F2C34),
    error = Color(0xFFCF6679),
    errorContainer = Color(0xFF8C1D18),
    onError = Color(0xFF601410),
    onErrorContainer = Color(0xFFF9DEDC),
    secondary = Color(0xFF00A884),
    onSecondary = Color(0xFF111B21),
    secondaryContainer = Color(0xFF005C4B),
    onSecondaryContainer = Color(0xFFE9EDEF),
    isLight = false
)

val LocalWaexColors = staticCompositionLocalOf { lightColors }
