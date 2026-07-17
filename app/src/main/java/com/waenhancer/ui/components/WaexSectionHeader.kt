package com.waenhancer.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.waenhancer.ui.designsystem.WaexTheme

@Composable
fun WaexSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val spacing = WaexTheme.spacing

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = typography.headlineMd,
                color = colors.onSurface
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(spacing.stackSm))
                Text(
                    text = subtitle,
                    style = typography.bodyMd,
                    color = colors.onSurfaceVariant
                )
            }
        }
        if (action != null) {
            action()
        }
    }
}
