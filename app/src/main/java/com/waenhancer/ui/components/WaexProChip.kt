package com.waenhancer.ui.components

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
                    shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 8.dp, topEnd = 16.dp, bottomEnd = 0.dp),
                    clip = false
                )
                .clip(RoundedCornerShape(topStart = 0.dp, bottomStart = 8.dp, topEnd = 16.dp, bottomEnd = 0.dp))
                .background(unlockedBgBrush)
                .border(
                    width = 1.dp,
                    color = unlockedBorderColor,
                    shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 8.dp, topEnd = 16.dp, bottomEnd = 0.dp)
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
                    Color(0xFF451A03).copy(alpha = 0.85f),
                    Color(0xFF78350F).copy(alpha = 0.65f)
                )
            )
        } else {
            Brush.horizontalGradient(
                listOf(
                    Color(0xFFFEF3C7),
                    Color(0xFFFDE68A)
                )
            )
        }

        val lockedBorderColor = if (isDark) {
            Color(0xFFF59E0B).copy(alpha = 0.45f)
        } else {
            Color(0xFFF59E0B).copy(alpha = 0.5f)
        }

        val lockedContentColor = if (isDark) {
            Color(0xFFFBBF24)
        } else {
            Color(0xFFB45309)
        }

        Row(
            modifier = modifier
                .shadow(
                    elevation = 1.5.dp,
                    shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 8.dp, topEnd = 16.dp, bottomEnd = 0.dp),
                    clip = false
                )
                .clip(RoundedCornerShape(topStart = 0.dp, bottomStart = 8.dp, topEnd = 16.dp, bottomEnd = 0.dp))
                .background(lockedBgBrush)
                .border(
                    width = 1.dp,
                    color = lockedBorderColor,
                    shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 8.dp, topEnd = 16.dp, bottomEnd = 0.dp)
                )
                .padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = WaexIcons.Lock,
                contentDescription = "Locked Pro feature",
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
