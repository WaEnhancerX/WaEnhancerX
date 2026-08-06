package com.waenhancer.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.statusBarsPadding
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme


@Composable
fun WaexTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBackClick: (() -> Unit)? = null,
    titleStyle: androidx.compose.ui.text.TextStyle? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val spacing = WaexTheme.spacing

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.surface
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = spacing.stackSm),
                verticalAlignment = Alignment.CenterVertically
            ) {


                if (onBackClick != null) {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    ) {
                        Icon(
                            imageVector = WaexIcons.Back,
                            contentDescription = "Back",
                            tint = colors.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.width(spacing.stackSm))
                } else {
                    Spacer(modifier = Modifier.width(spacing.stackMd))
                }
                Text(
                    text = title,
                    style = titleStyle ?: typography.headlineMd,
                    color = colors.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .align(Alignment.CenterVertically)
                )
                if (actions != null) {
                    Row(
                        modifier = Modifier.align(Alignment.CenterVertically),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions
                    )
                }
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = colors.outlineVariant
            )
        }
    }
}
