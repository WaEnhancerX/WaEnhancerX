package com.waenhancer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
    val radius = WaexTheme.radius

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
                vertical = spacing.stackLg
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(colors.secondaryContainer, radius.mdShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colors.onSecondaryContainer,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Spacer(modifier = Modifier.width(spacing.gutter))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = typography.titleMd,
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
            Box(
                modifier = Modifier
                    .padding(horizontal = spacing.gutter)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.outlineVariant.copy(alpha = 0.55f))
            )
        }
    }
}
