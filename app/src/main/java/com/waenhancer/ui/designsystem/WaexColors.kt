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

    val success: Color get() = if (isLight) Color(0xFF256B3B) else Color(0xFF8DD99A)
    val onSuccess: Color get() = if (isLight) Color.White else Color(0xFF073917)
    val successContainer: Color get() = if (isLight) Color(0xFFB8F2C5) else Color(0xFF174D29)
    val onSuccessContainer: Color get() = if (isLight) Color(0xFF002109) else Color(0xFFA8F5B5)

    val warning: Color get() = if (isLight) Color(0xFF765A00) else Color(0xFFF2C66D)
    val onWarning: Color get() = if (isLight) Color.White else Color(0xFF3E2E00)
    val warningContainer: Color get() = if (isLight) Color(0xFFFFE08A) else Color(0xFF594500)
    val onWarningContainer: Color get() = if (isLight) Color(0xFF241A00) else Color(0xFFFFE29A)
}

val lightColors = WaexColors(
    primary = Color(0xFF008069), // WhatsApp Teal
    primaryContainer = Color(0xFFE1F3EF),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF7FAF8),
    onBackground = Color(0xFF0F0F0F), // Almost black
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F0F0F),
    surfaceDim = Color(0xFFF5F5F5),
    surfaceContainerLow = Color(0xFFF1F6F3),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE7F0EC),
    surfaceContainerHighest = Color(0xFFDCE8E3),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF718079),
    outlineVariant = Color(0xFFC4D1CB),
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
    primary = Color(0xFF55D6B7),
    primaryContainer = Color(0xFF00513F),
    onPrimary = Color(0xFF00382B),
    background = Color(0xFF0A1310),
    onBackground = Color(0xFFDCE5DF),
    surface = Color(0xFF0F1815),
    onSurface = Color(0xFFDCE5DF),
    surfaceDim = Color(0xFF0A1310),
    surfaceContainerLow = Color(0xFF131E19),
    surfaceContainer = Color(0xFF18241F),
    surfaceContainerHigh = Color(0xFF202C27),
    surfaceContainerHighest = Color(0xFF2A3731),
    onSurfaceVariant = Color(0xFFBAC9C1),
    outline = Color(0xFF85958D),
    outlineVariant = Color(0xFF3C4A43),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onError = Color(0xFF690005),
    onErrorContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFBBCAC2),
    onSecondary = Color(0xFF26332D),
    secondaryContainer = Color(0xFF3C4A43),
    onSecondaryContainer = Color(0xFFD7E7DE),
    isLight = false
)

val LocalWaexColors = staticCompositionLocalOf { lightColors }
