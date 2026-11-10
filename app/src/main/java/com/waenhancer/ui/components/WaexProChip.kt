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
    val tileCornerShape = RoundedCornerShape(
        topStart = 0.dp,
        topEnd = 0.dp,
        bottomEnd = 0.dp,
        bottomStart = 8.dp
    )

    if (isUnlocked) {
        Box(
            modifier = modifier
                .clip(tileCornerShape)
                .background(colors.primary.copy(alpha = 0.12f))
                .border(
                    width = 1.dp,
                    color = colors.primary.copy(alpha = 0.28f),
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
        val lockedContentColor = colors.onSurfaceVariant

        Row(
            modifier = modifier
                .clip(tileCornerShape)
                .background(colors.surfaceContainerHighest)
                .border(
                    width = 1.dp,
                    color = colors.outlineVariant,
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
