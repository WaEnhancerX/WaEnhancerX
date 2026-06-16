package com.waenhancer.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme

enum class BannerType {
    INFO,
    WARNING,
    ERROR
}

@Composable
fun WaexInfoBanner(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    bannerType: BannerType = BannerType.INFO,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val spacing = WaexTheme.spacing

    val bannerTheme = when (bannerType) {
        BannerType.INFO -> BannerTheme(
            bgColor = colors.surfaceContainerLow,
            borderColor = colors.outlineVariant,
            contentColor = colors.primary,
            icon = WaexIcons.Info
        )
        BannerType.WARNING -> BannerTheme(
            bgColor = Color(0xFFFFF7E6), // Light Orange
            borderColor = Color(0xFFFFD591), // Orange Border
            contentColor = Color(0xFFD4380D), // Orange Text
            icon = WaexIcons.Warning
        )
        BannerType.ERROR -> BannerTheme(
            bgColor = colors.errorContainer.copy(alpha = 0.15f),
            borderColor = colors.error,
            contentColor = colors.error,
            icon = WaexIcons.Warning
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = radius.defaultShape,
        color = bannerTheme.bgColor,
        border = BorderStroke(1.dp, bannerTheme.borderColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = bannerTheme.icon,
                contentDescription = null,
                tint = bannerTheme.contentColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(spacing.gutter))
            Column(modifier = Modifier.weight(1f)) {
                if (title != null) {
                    Text(
                        text = title,
                        style = typography.bodyLg.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.onSurface
                    )
                    Spacer(modifier = Modifier.height(spacing.stackSm))
                }
                Text(
                    text = message,
                    style = typography.bodyMd,
                    color = colors.onSurface
                )
                if (actionText != null && onActionClick != null) {
                    Spacer(modifier = Modifier.height(spacing.stackMd))
                    Button(
                        onClick = onActionClick,
                        shape = radius.buttonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primary,
                            contentColor = colors.onPrimary
                        ),
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Text(text = actionText, style = typography.labelSm)
                    }
                }
            }
        }
    }
}

private data class BannerTheme(
    val bgColor: Color,
    val borderColor: Color,
    val contentColor: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)
