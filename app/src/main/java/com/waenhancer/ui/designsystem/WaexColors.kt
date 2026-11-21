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
    // WhatsApp design spec: brand teal for actions, warm neutral surfaces.
    primary = Color(0xFF1DAA61), // filled controls, FAB, switch track
    primaryContainer = Color(0xFFD9FDD3), // sent-bubble / selected-chip green
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF12181C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0A1014),
    surfaceDim = Color(0xFFF7F5F3),
    surfaceContainerLow = Color(0xFFF7F5F3),
    surfaceContainer = Color(0xFFF7F5F3),
    surfaceContainerHigh = Color(0xFFF6F5F4), // search bar fill
    surfaceContainerHighest = Color(0xFFE9E5DF),
    onSurfaceVariant = Color(0xFF5B6368),
    outline = Color(0xFF8D9599),
    outlineVariant = Color(0xFFE7E8E8),
    error = Color(0xFFEA0038),
    errorContainer = Color(0xFFFFDAD9),
    onError = Color(0xFFFFFFFF),
    onErrorContainer = Color(0xFF8A0023),
    secondary = Color(0xFF5B6368),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF1F2F4),
    onSecondaryContainer = Color(0xFF12181C),
    isLight = true
)

val darkColors = WaexColors(
    primary = Color(0xFF21C063),
    primaryContainer = Color(0xFF103529),
    onPrimary = Color(0xFF0A1014),
    background = Color(0xFF0A1014),
    onBackground = Color(0xFFEAEDEE),
    surface = Color(0xFF0A1014),
    onSurface = Color(0xFFF7F8FA),
    surfaceDim = Color(0xFF0A1014),
    surfaceContainerLow = Color(0xFF12181C),
    surfaceContainer = Color(0xFF12181C),
    surfaceContainerHigh = Color(0xFF23282C),
    surfaceContainerHighest = Color(0xFF2B3338),
    onSurfaceVariant = Color(0xFF8D9599),
    outline = Color(0xFF6C757A),
    outlineVariant = Color(0xFF23282C),
    error = Color(0xFFFB5061),
    errorContainer = Color(0xFF5C0A1A),
    onError = Color(0xFF0A1014),
    onErrorContainer = Color(0xFFFFDAD9),
    secondary = Color(0xFF8D9599),
    onSecondary = Color(0xFF0A1014),
    secondaryContainer = Color(0xFF20272B),
    onSecondaryContainer = Color(0xFFEAEDEE),
    isLight = false
)

val LocalWaexColors = staticCompositionLocalOf { lightColors }
