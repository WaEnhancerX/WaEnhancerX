package com.waenhancer.ui.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalThemeMode = staticCompositionLocalOf { "System" }
val LocalThemeModeUpdater = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
fun WaexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) darkColors else lightColors
    val typography = WaexTypography()
    val spacing = WaexSpacing()
    val radius = WaexRadius()
    val elevation = WaexElevation()

    CompositionLocalProvider(
        LocalWaexColors provides colors,
        LocalWaexTypography provides typography,
        LocalWaexSpacing provides spacing,
        LocalWaexRadius provides radius,
        LocalWaexElevation provides elevation
    ) {
        content()
    }
}

object WaexTheme {
    val colors: WaexColors
        @Composable
        @ReadOnlyComposable
        get() = LocalWaexColors.current

    val typography: WaexTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalWaexTypography.current

    val spacing: WaexSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalWaexSpacing.current

    val radius: WaexRadius
        @Composable
        @ReadOnlyComposable
        get() = LocalWaexRadius.current

    val elevation: WaexElevation
        @Composable
        @ReadOnlyComposable
        get() = LocalWaexElevation.current
}
