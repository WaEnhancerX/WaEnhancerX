package com.waenhancer.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.designsystem.WaexTheme

@Composable
fun StitchSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = WaexTheme.colors
    val trackWidth = 36.dp
    val trackHeight = 20.dp
    val thumbSize = 16.dp

    // Smooth animation for thumb movement
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 16.dp else 0.dp,
        animationSpec = tween(durationMillis = 200),
        label = "switch_thumb"
    )

    val trackColor = when {
        !enabled -> if (checked) colors.primary.copy(alpha = 0.5f) else colors.outlineVariant.copy(alpha = 0.5f)
        checked -> colors.primary // Pitch Black (#000000)
        else -> colors.outlineVariant // Muted grey outline-variant
    }

    Box(
        modifier = modifier
            .size(width = trackWidth, height = trackHeight)
            .clip(CircleShape)
            .background(trackColor)
            .then(
                if (onCheckedChange != null && enabled) {
                    Modifier.clickable { onCheckedChange(!checked) }
                } else {
                    Modifier
                }
            )
            .padding(2.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbOffset)
                .size(thumbSize)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
fun WaexSwitchPreference(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null, // kept for signature compatibility
    enabled: Boolean = true,
    showDivider: Boolean = true
) {
    WaexPreferenceItem(
        title = title,
        modifier = modifier,
        description = description,
        onClick = if (enabled) { { onCheckedChange(!checked) } } else null,
        showDivider = showDivider,
        trailing = {
            StitchSwitch(
                checked = checked,
                onCheckedChange = if (enabled) onCheckedChange else null,
                enabled = enabled
            )
        }
    )
}
