package com.waenhancer.ui.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class WaexSpacing(
    val scale: Float = 1f,
    val pageMargin: Dp = (24f * scale).dp,
    val gutter: Dp = (12f * scale).dp,
    val bentoGap: Dp = (12f * scale).dp,
    val stackLg: Dp = (24f * scale).dp,
    val stackMd: Dp = (16f * scale).dp,
    val stackSm: Dp = (8f * scale).dp
)

val LocalWaexSpacing = staticCompositionLocalOf { WaexSpacing() }
