package com.waenhancer.ui.components

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import com.waenhancer.ui.designsystem.WaexTheme

@Composable
fun StitchSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = WaexTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.graphicsLayer {
            scaleX = 0.84f
            scaleY = 0.84f
        },
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.onPrimary,
            checkedTrackColor = colors.primary,
            checkedBorderColor = colors.primary,
            uncheckedThumbColor = colors.outline,
            uncheckedTrackColor = colors.surfaceContainerHighest,
            uncheckedBorderColor = colors.outline,
            disabledCheckedThumbColor = colors.onSurface.copy(alpha = 0.38f),
            disabledCheckedTrackColor = colors.primary.copy(alpha = 0.32f),
            disabledUncheckedThumbColor = colors.onSurface.copy(alpha = 0.38f),
            disabledUncheckedTrackColor = colors.surfaceContainerHighest.copy(alpha = 0.38f)
        )
    )
}

@Composable
fun WaexSwitchPreference(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    showDivider: Boolean = true
) {
    WaexPreferenceItem(
        title = title,
        modifier = modifier,
        description = description,
        icon = icon,
        onClick = if (enabled) { { onCheckedChange(!checked) } } else null,
        showDivider = showDivider,
        trailing = {
            StitchSwitch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled
            )
        }
    )
}
