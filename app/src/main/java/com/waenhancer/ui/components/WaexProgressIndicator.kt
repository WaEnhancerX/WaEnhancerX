package com.waenhancer.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.designsystem.WaexTheme

/** Native Material 3 Expressive progress for measurable work. */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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

    LinearWavyProgressIndicator(
        progress = { animatedProgress },
        modifier = modifier,
        color = color,
        trackColor = trackColor
    )
}

/** Native Material 3 Expressive indeterminate circular progress. */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun WaexCircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = WaexTheme.colors.primary,
    trackColor: Color = WaexTheme.colors.surfaceContainerHighest,
    strokeWidth: Dp = 4.dp
) {
    val density = LocalDensity.current
    val indicatorStroke = Stroke(
        width = with(density) { strokeWidth.toPx() },
        cap = StrokeCap.Round
    )
    CircularWavyProgressIndicator(
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        stroke = indicatorStroke,
        trackStroke = indicatorStroke
    )
}
