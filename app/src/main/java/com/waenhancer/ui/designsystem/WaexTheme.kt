package com.waenhancer.ui.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import android.app.Activity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val LocalThemeMode = staticCompositionLocalOf { "System" }
val LocalThemeModeUpdater = staticCompositionLocalOf<(String) -> Unit> { {} }

enum class InterfaceSize(val storageValue: String, val scale: Float) {
    COMPACT("Compact", 1f),
    STANDARD("Standard", 1.08f),
    COMFORTABLE("Comfortable", 1.16f),
    LARGE("Large", 1.25f);

    companion object {
        fun fromStorage(value: String?): InterfaceSize =
            entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) } ?: COMPACT
    }
}

val LocalInterfaceSize = staticCompositionLocalOf { InterfaceSize.COMPACT }
val LocalInterfaceSizeUpdater = staticCompositionLocalOf<(InterfaceSize) -> Unit> { {} }

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

/** Map system Material You roles into WAEX's custom design-system colors. */
private fun monetWaexColors(scheme: ColorScheme, isDark: Boolean) = WaexColors(
    primary = scheme.primary,
    primaryContainer = scheme.primaryContainer,
    onPrimary = scheme.onPrimary,
    background = scheme.background,
    onBackground = scheme.onBackground,
    surface = scheme.surface,
    onSurface = scheme.onSurface,
    surfaceDim = scheme.surfaceDim,
    surfaceContainerLow = scheme.surfaceContainerLow,
    surfaceContainer = scheme.surfaceContainer,
    surfaceContainerHigh = scheme.surfaceContainerHigh,
    surfaceContainerHighest = scheme.surfaceContainerHighest,
    onSurfaceVariant = scheme.onSurfaceVariant,
    outline = scheme.outline,
    outlineVariant = scheme.outlineVariant,
    error = scheme.error,
    errorContainer = scheme.errorContainer,
    onError = scheme.onError,
    onErrorContainer = scheme.onErrorContainer,
    secondary = scheme.secondary,
    onSecondary = scheme.onSecondary,
    secondaryContainer = scheme.secondaryContainer,
    onSecondaryContainer = scheme.onSecondaryContainer,
    isLight = !isDark
)

@Composable
fun WaexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useMonet: Boolean = false,
    interfaceSize: InterfaceSize = InterfaceSize.COMPACT,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDensity = LocalDensity.current
    // Monet is opt-in. Android <= 11 and OEM palette failures preserve the existing theme.
    val systemPalette = if (useMonet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }.getOrNull()
    } else null
    val targetColors = systemPalette?.let { monetWaexColors(it, darkTheme) }
        ?: if (darkTheme) darkColors else lightColors
    val colors = animateWaexColors(targetColors)
    // LocalDensity applies the interface scale to every dp/sp value below.
    // Keep tokens in their base units so text and gaps grow once, like fixed controls.
    val typography = remember { WaexTypography() }
    val spacing = remember { WaexSpacing() }
    val radius = WaexRadius()
    val elevation = WaexElevation()

    val m3ColorScheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            primaryContainer = colors.primaryContainer,
            onPrimaryContainer = systemPalette?.onPrimaryContainer ?: colors.onSecondaryContainer,
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
            surfaceDim = colors.surfaceDim,
            surfaceBright = colors.surfaceContainerHighest,
            surfaceContainerLowest = colors.surface,
            surfaceContainerLow = colors.surfaceContainerLow,
            surfaceContainer = colors.surfaceContainer,
            surfaceContainerHigh = colors.surfaceContainerHigh,
            surfaceContainerHighest = colors.surfaceContainerHighest,
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
            onPrimaryContainer = systemPalette?.onPrimaryContainer ?: Color(0xFF002119),
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
            surfaceDim = colors.surfaceDim,
            surfaceBright = colors.surface,
            surfaceContainerLowest = colors.surfaceContainerLow,
            surfaceContainerLow = colors.surfaceContainerLow,
            surfaceContainer = colors.surfaceContainer,
            surfaceContainerHigh = colors.surfaceContainerHigh,
            surfaceContainerHighest = colors.surfaceContainerHighest,
            outline = colors.outline,
            outlineVariant = colors.outlineVariant,
            error = colors.error,
            onError = colors.onError
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
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
        LocalWaexElevation provides elevation,
        LocalDensity provides Density(
            density = systemDensity.density * interfaceSize.scale,
            fontScale = systemDensity.fontScale
        )
    ) {
        MaterialTheme(
            colorScheme = m3ColorScheme,
            typography = MaterialTypography,
            shapes = androidx.compose.material3.Shapes(
                extraSmall = radius.smShape,
                small = radius.defaultShape,
                medium = radius.mdShape,
                large = radius.cardShape,
                extraLarge = radius.bottomSheetShape
            )
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
