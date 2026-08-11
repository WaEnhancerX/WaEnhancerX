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
    primaryContainer = Color(0xFFE8F5E9),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1E),
    surfaceDim = Color(0xFFF1F3F5),
    surfaceContainerLow = Color(0xFFF8F9FA),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFECEEF0),
    surfaceContainerHighest = Color(0xFFE1E3E5),
    onSurfaceVariant = Color(0xFF5F6E78),
    outline = Color(0xFFE2E4E8),
    outlineVariant = Color(0xFFEBECEF),
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
    background = Color(0xFF0F1417),
    onBackground = Color(0xFFE2E5E8),
    surface = Color(0xFF171F24),
    onSurface = Color(0xFFE2E5E8),
    surfaceDim = Color(0xFF12191D),
    surfaceContainerLow = Color(0xFF171F24),
    surfaceContainer = Color(0xFF1D262C),
    surfaceContainerHigh = Color(0xFF243037),
    surfaceContainerHighest = Color(0xFF2E3D46),
    onSurfaceVariant = Color(0xFF8B9BA5),
    outline = Color(0xFF243037),
    outlineVariant = Color(0xFF1D262C),
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
