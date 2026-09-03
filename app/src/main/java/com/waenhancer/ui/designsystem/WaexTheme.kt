package com.waenhancer.ui.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalThemeMode = staticCompositionLocalOf { "System" }
val LocalThemeModeUpdater = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
fun animateWaexColors(targetColors: WaexColors): WaexColors {
    val animationSpec = tween<Color>(durationMillis = 300)
    return WaexColors(
        primary = animateColorAsState(targetColors.primary, animationSpec, label = "primary").value,
        primaryContainer = animateColorAsState(targetColors.primaryContainer, animationSpec, label = "primaryContainer").value,
        onPrimary = animateColorAsState(targetColors.onPrimary, animationSpec, label = "onPrimary").value,
        background = animateColorAsState(targetColors.background, animationSpec, label = "background").value,
        onBackground = animateColorAsState(targetColors.onBackground, animationSpec, label = "onBackground").value,
        surface = animateColorAsState(targetColors.surface, animationSpec, label = "surface").value,
        onSurface = animateColorAsState(targetColors.onSurface, animationSpec, label = "onSurface").value,
        surfaceDim = animateColorAsState(targetColors.surfaceDim, animationSpec, label = "surfaceDim").value,
        surfaceContainerLow = animateColorAsState(targetColors.surfaceContainerLow, animationSpec, label = "surfaceContainerLow").value,
        surfaceContainer = animateColorAsState(targetColors.surfaceContainer, animationSpec, label = "surfaceContainer").value,
        surfaceContainerHigh = animateColorAsState(targetColors.surfaceContainerHigh, animationSpec, label = "surfaceContainerHigh").value,
        surfaceContainerHighest = animateColorAsState(targetColors.surfaceContainerHighest, animationSpec, label = "surfaceContainerHighest").value,
        onSurfaceVariant = animateColorAsState(targetColors.onSurfaceVariant, animationSpec, label = "onSurfaceVariant").value,
        outline = animateColorAsState(targetColors.outline, animationSpec, label = "outline").value,
        outlineVariant = animateColorAsState(targetColors.outlineVariant, animationSpec, label = "outlineVariant").value,
        error = animateColorAsState(targetColors.error, animationSpec, label = "error").value,
        errorContainer = animateColorAsState(targetColors.errorContainer, animationSpec, label = "errorContainer").value,
        onError = animateColorAsState(targetColors.onError, animationSpec, label = "onError").value,
        onErrorContainer = animateColorAsState(targetColors.onErrorContainer, animationSpec, label = "onErrorContainer").value,
        secondary = animateColorAsState(targetColors.secondary, animationSpec, label = "secondary").value,
        onSecondary = animateColorAsState(targetColors.onSecondary, animationSpec, label = "onSecondary").value,
        secondaryContainer = animateColorAsState(targetColors.secondaryContainer, animationSpec, label = "secondaryContainer").value,
        onSecondaryContainer = animateColorAsState(targetColors.onSecondaryContainer, animationSpec, label = "onSecondaryContainer").value,
        isLight = targetColors.isLight
    )
}

@Composable
fun WaexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val targetColors = if (darkTheme) darkColors else lightColors
    val colors = animateWaexColors(targetColors)
    val typography = WaexTypography()
    val spacing = WaexSpacing()
    val radius = WaexRadius()
    val elevation = WaexElevation()

    val m3ColorScheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            primaryContainer = colors.primaryContainer,
            onPrimaryContainer = Color(0xFF36FFCD),
            secondary = colors.secondary,
            onSecondary = colors.onSecondary,
            secondaryContainer = colors.secondaryContainer,
            onSecondaryContainer = colors.onSecondaryContainer,
            background = colors.background,
            onBackground = colors.onBackground,
            surface = colors.surface,
            onSurface = colors.onSurface,
            surfaceVariant = colors.surfaceDim,
            onSurfaceVariant = colors.onSurfaceVariant,
            outline = colors.outline,
            outlineVariant = colors.outlineVariant,
            error = colors.error,
            onError = colors.onError
        )
    } else {
        lightColorScheme(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            primaryContainer = colors.primaryContainer,
            onPrimaryContainer = Color(0xFF002119),
            secondary = colors.secondary,
            onSecondary = colors.onSecondary,
            secondaryContainer = colors.secondaryContainer,
            onSecondaryContainer = colors.onSecondaryContainer,
            background = colors.background,
            onBackground = colors.onBackground,
            surface = colors.surface,
            onSurface = colors.onSurface,
            surfaceVariant = colors.surfaceDim,
            onSurfaceVariant = colors.onSurfaceVariant,
            outline = colors.outline,
            outlineVariant = colors.outlineVariant,
            error = colors.error,
            onError = colors.onError
        )
    }

    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalWaexColors provides colors,
        LocalWaexTypography provides typography,
        LocalWaexSpacing provides spacing,
        LocalWaexRadius provides radius,
        LocalWaexElevation provides elevation
    ) {
        MaterialTheme(
            colorScheme = m3ColorScheme,
            typography = MaterialTypography
        ) {
            content()
        }
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
