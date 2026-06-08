package com.waenhancer.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    secondary = Secondary,
    background = ColorSchemeBackgroundDark(),
    surface = ColorSchemeSurfaceDark()
)

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    secondary = Secondary,
    background = Background,
    surface = Surface
)

@Composable
fun WaEnhancerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    CompositionLocalProvider(LocalSpacing provides Spacing()) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

// Helper to provide basic dark colors
private fun ColorSchemeBackgroundDark() = androidx.compose.ui.graphics.Color(0xFF121212)
private fun ColorSchemeSurfaceDark() = androidx.compose.ui.graphics.Color(0xFF121212)
