package com.waenhancer.ui.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class WaexSpacing(
    val pageMargin: Dp = 24.dp,
    val gutter: Dp = 16.dp,
    val bentoGap: Dp = 12.dp,
    val stackLg: Dp = 24.dp,
    val stackMd: Dp = 16.dp,
    val stackSm: Dp = 8.dp
)

val LocalWaexSpacing = staticCompositionLocalOf { WaexSpacing() }
