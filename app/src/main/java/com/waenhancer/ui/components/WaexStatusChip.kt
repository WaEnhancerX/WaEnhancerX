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

    Box(
        modifier = modifier
            .clip(radius.fullShape)
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = typography.labelSm,
            color = color
        )
    }
}
