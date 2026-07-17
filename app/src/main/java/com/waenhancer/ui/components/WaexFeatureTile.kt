package com.waenhancer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.designsystem.WaexTheme

enum class TileType {
    COMPACT_1X1,
    HORIZONTAL_2X1,
    TALL_2X2
}

@Composable
fun WaexFeatureTile(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    statusText: String? = null,
    statusColor: Color? = null,
    tileType: TileType = TileType.HORIZONTAL_2X1
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val spacing = WaexTheme.spacing

    WaexCard(
        modifier = modifier,
        onClick = onClick
    ) {
        when (tileType) {
            TileType.COMPACT_1X1 -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(radius.mdShape)
                            .background(colors.primaryContainer.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = colors.primaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(spacing.stackMd))
                    Text(
                        text = title,
                        style = typography.bodyLg.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.onSurface,
                        maxLines = 1
                    )
                    if (statusText != null) {
                        Spacer(modifier = Modifier.height(spacing.stackSm))
                        WaexStatusChip(
                            text = statusText,
                            color = statusColor ?: colors.primaryContainer
                        )
                    }
                }
            }
            TileType.HORIZONTAL_2X1 -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(radius.mdShape)
                            .background(colors.primaryContainer.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = colors.primaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(spacing.gutter))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = typography.bodyLg.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.onSurface
                        )
                        if (description != null) {
                            Text(
                                text = description,
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                    if (statusText != null) {
                        Spacer(modifier = Modifier.width(spacing.stackSm))
                        WaexStatusChip(
                            text = statusText,
                            color = statusColor ?: colors.primaryContainer
                        )
                    }
                }
            }
            TileType.TALL_2X2 -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(radius.mdShape)
                                .background(colors.primaryContainer.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = colors.primaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (statusText != null) {
                            WaexStatusChip(
                                text = statusText,
                                color = statusColor ?: colors.primaryContainer
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(spacing.stackLg))
                    Text(
                        text = title,
                        style = typography.headlineMd,
                        color = colors.onSurface
                    )
                    if (description != null) {
                        Spacer(modifier = Modifier.height(spacing.stackSm))
                        Text(
                            text = description,
                            style = typography.bodyMd,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
