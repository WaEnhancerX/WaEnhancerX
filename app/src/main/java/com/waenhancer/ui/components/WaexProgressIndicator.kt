package com.waenhancer.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.designsystem.WaexTheme

/** Expressive Material progress for measurable, page-level work. */
@Composable
fun WaexLinearProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = WaexTheme.colors.primary,
    trackColor: Color = WaexTheme.colors.surfaceContainerHighest
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress().coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "waex_progress"
    )
    LinearProgressIndicator(
        progress = { animatedProgress },
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        strokeCap = StrokeCap.Round
    )
}

@Composable
fun WaexCircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = WaexTheme.colors.primary,
    trackColor: Color = WaexTheme.colors.surfaceContainerHighest,
    strokeWidth: Dp = 4.dp
) {
    CircularProgressIndicator(
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        strokeWidth = strokeWidth,
        strokeCap = StrokeCap.Round
    )
}
