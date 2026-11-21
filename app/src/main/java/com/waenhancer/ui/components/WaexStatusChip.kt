package com.waenhancer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.designsystem.WaexTheme

@Composable
fun WaexStatusChip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = WaexTheme.colors.primaryContainer
) {
    val radius = WaexTheme.radius
    val typography = WaexTheme.typography
    // WhatsApp "New" badge: sent-green fill with deep green (light) / pale green (dark) text.
    val isBadge = color == WaexTheme.colors.primaryContainer
    val badgeText = if (WaexTheme.colors.isLight) Color(0xFF15603E) else Color(0xFFD9FDD3)

    Box(
        modifier = modifier
            .clip(radius.fullShape)
            .background(if (isBadge) color else color.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = typography.labelSm,
            color = if (isBadge) badgeText else color
        )
    }
}
