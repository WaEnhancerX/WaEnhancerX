package com.waenhancer.ui.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class WaexRadius(
    val sm: Dp = 6.dp,
    val default: Dp = 10.dp,
    val button: Dp = 10.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val card: Dp = 14.dp,
    val bentoCard: Dp = 16.dp,
    val bottomSheet: Dp = 24.dp,
    val full: Dp = 9999.dp
) {

    val smShape: RoundedCornerShape get() = RoundedCornerShape(sm)
    val defaultShape: RoundedCornerShape get() = RoundedCornerShape(default)
    val buttonShape: RoundedCornerShape get() = RoundedCornerShape(button)
    val mdShape: RoundedCornerShape get() = RoundedCornerShape(md)
    val lgShape: RoundedCornerShape get() = RoundedCornerShape(lg)
    val cardShape: RoundedCornerShape get() = RoundedCornerShape(card)
    val bentoCardShape: RoundedCornerShape get() = RoundedCornerShape(bentoCard)
    val bottomSheetShape: RoundedCornerShape get() = RoundedCornerShape(topStart = bottomSheet, topEnd = bottomSheet)
    val fullShape: RoundedCornerShape get() = RoundedCornerShape(full)
}

val LocalWaexRadius = staticCompositionLocalOf { WaexRadius() }
