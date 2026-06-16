package com.waenhancer.ui.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class WaexElevation(
    val none: Dp = 0.dp,
    val card: Dp = 0.dp,
    val button: Dp = 0.dp,
    val modal: Dp = 0.dp
)

val LocalWaexElevation = staticCompositionLocalOf { WaexElevation() }
