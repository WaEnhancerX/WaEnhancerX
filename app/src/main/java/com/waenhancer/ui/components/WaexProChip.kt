package com.waenhancer.ui.components

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme

@Composable
fun WaexProChip(
    isUnlocked: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = WaexTheme.colors
    val isDark = colors.isDark
    val tileCornerShape = RoundedCornerShape(
        topStart = 0.dp,
        topEnd = 0.dp,
        bottomEnd = 0.dp,
        bottomStart = 8.dp
    )

    if (isUnlocked) {
        // Unlocked Pro Floating Badge
        val unlockedBgBrush = if (isDark) {
            Brush.horizontalGradient(
                listOf(
                    colors.primary.copy(alpha = 0.28f),
                    colors.primary.copy(alpha = 0.16f)
                )
            )
        } else {
            Brush.horizontalGradient(
                listOf(
                    colors.primary.copy(alpha = 0.18f),
                    colors.primary.copy(alpha = 0.10f)
                )
            )
        }

        val unlockedBorderColor = if (isDark) {
            colors.primary.copy(alpha = 0.45f)
        } else {
            colors.primary.copy(alpha = 0.30f)
        }

        Box(
            modifier = modifier
                .shadow(
                    elevation = 1.dp,
                    shape = tileCornerShape,
                    clip = false
                )
                .clip(tileCornerShape)
                .background(unlockedBgBrush)
                .border(
                    width = 1.dp,
                    color = unlockedBorderColor,
                    shape = tileCornerShape
                )
                .padding(horizontal = 8.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "PRO",
                style = WaexTheme.typography.labelSm.copy(
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.8.sp
                ),
                color = colors.primary
            )
        }
    } else {
        // Floating Locked Pro Badge (Amber / Gold Accent)
        val lockedBgBrush = if (isDark) {
            Brush.horizontalGradient(
                listOf(
                    colors.warningContainer,
                    colors.surfaceContainerHigh
                )
            )
        } else {
            Brush.horizontalGradient(
                listOf(
                    colors.warningContainer,
                    colors.surfaceContainerHigh
                )
            )
        }

        val lockedBorderColor = if (isDark) {
            colors.warning.copy(alpha = 0.45f)
        } else {
            colors.warning.copy(alpha = 0.5f)
        }

        val lockedContentColor = if (isDark) {
            colors.onWarningContainer
        } else {
            colors.onWarningContainer
        }

        Row(
            modifier = modifier
                .shadow(
                    elevation = 1.5.dp,
                    shape = tileCornerShape,
                    clip = false
                )
                .clip(tileCornerShape)
                .background(lockedBgBrush)
                .border(
                    width = 1.dp,
                    color = lockedBorderColor,
                    shape = tileCornerShape
                )
                .padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = WaexIcons.Lock,
                contentDescription = UiText.get(R.string.waex_ui_locked_pro_feature, "Locked Pro feature"),
                tint = lockedContentColor,
                modifier = Modifier.size(9.dp)
            )
            Text(
                text = "PRO",
                style = WaexTheme.typography.labelSm.copy(
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.8.sp
                ),
                color = lockedContentColor
            )
        }
    }
}
