package com.waenhancer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.designsystem.WaexTheme

@Composable
fun WaexEmptyState(
    title: String,
    description: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val spacing = WaexTheme.spacing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(spacing.pageMargin),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(radius.lgShape)
                .background(colors.surfaceContainerLow),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(spacing.stackLg))
        Text(
            text = title,
            style = typography.headlineMd,
            color = colors.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(spacing.stackSm))
        Text(
            text = description,
            style = typography.bodyMd,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionText != null && onActionClick != null) {
            Spacer(modifier = Modifier.height(spacing.stackLg))
            Button(
                onClick = onActionClick,
                shape = radius.buttonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary
                )
            ) {
                Text(text = actionText, style = typography.labelSm)
            }
        }
    }
}
