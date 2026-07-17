package com.waenhancer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.designsystem.WaexTheme

@Composable
fun WaexPreferenceItem(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    trailing: (@Composable () -> Unit)? = null
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val spacing = WaexTheme.spacing

    val itemModifier = if (onClick != null) {
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    } else {
        modifier.fillMaxWidth()
    }

    Column(modifier = itemModifier) {
        Row(
            modifier = Modifier.padding(
                horizontal = spacing.gutter,
                vertical = spacing.stackMd
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = typography.bodyLg,
                    color = colors.onSurface
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = typography.bodyMd,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            if (trailing != null) {
                Spacer(modifier = Modifier.width(spacing.stackSm))
                Box(contentAlignment = Alignment.Center) {
                    trailing()
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = spacing.gutter),
                thickness = 1.dp,
                color = colors.outlineVariant
            )
        }
    }
}
