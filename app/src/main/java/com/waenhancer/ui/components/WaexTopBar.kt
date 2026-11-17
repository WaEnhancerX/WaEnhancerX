package com.waenhancer.ui.components

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import com.waenhancer.ui.glass.LocalWaexLiquidGlass
import com.waenhancer.ui.glass.waexGlass


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaexTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBackClick: (() -> Unit)? = null,
    titleStyle: TextStyle? = null,
    titleLeadingIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val glass = LocalWaexLiquidGlass.current
    TopAppBar(
        title = {
            if (titleLeadingIcon != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    titleLeadingIcon()
                    Text(
                        text = title,
                        style = titleStyle ?: typography.headlineMd,
                        color = colors.onSurface
                    )
                }
            } else {
                Text(
                    text = title,
                    style = titleStyle ?: typography.headlineMd,
                    color = colors.onSurface
                )
            }
        },
        modifier = modifier.fillMaxWidth().then(
            if (glass) Modifier.waexGlass(RectangleShape, colors.surface.copy(alpha = 0.35f), rimAlpha = 0.25f) else Modifier
        ),
        navigationIcon = {
            if (onBackClick != null) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = WaexIcons.Back,
                        contentDescription = UiText.get(R.string.action_back, "Back")
                    )
                }
            }
        },
        actions = { actions?.invoke(this) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = if (glass) Color.Transparent else colors.background,
            scrolledContainerColor = if (glass) Color.Transparent else colors.surfaceContainer,
            navigationIconContentColor = colors.onSurface,
            actionIconContentColor = colors.onSurfaceVariant,
            titleContentColor = colors.onSurface
        )
    )
}
