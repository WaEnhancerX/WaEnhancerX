package com.waenhancer.ui.screens.privacy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme

@Composable
fun PerContactPrivacyModal(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var selectedScope by remember { mutableStateOf("always") } // "always" | "scheduled" | "temporary"

    val rulesState = remember {
        mutableStateMapOf(
            "ghost" to true,
            "hide_seen" to false,
            "hide_typing" to true,
            "hide_recording" to false,
            "anti_revoke" to true,
            "freeze_lastseen" to false
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.pageMargin)
            .verticalScroll(rememberScrollState())
    ) {
        // Drag handle
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(colors.outlineVariant)
                .align(Alignment.CenterHorizontally)
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text(
                    text = "Per Contact Privacy",
                    style = typography.headlineMd,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
                Text(
                    text = "Set privacy rules per contact",
                    style = typography.bodyMd,
                    color = colors.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceDim)
                    .border(1.dp, colors.outlineVariant, CircleShape)
            ) {
                Icon(
                    imageVector = WaexIcons.Clear,
                    contentDescription = "Close",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        // Contact Info Card
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = WaexIcons.Security, // User icon analogue
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Alex Johnson",
                    style = typography.bodyLg,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
                Text(
                    text = "+1 555-0192@s.whatsapp.net",
                    style = typography.bodyMd,
                    color = colors.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        // Privacy Rules Section
        Text(
            text = "PRIVACY RULES",
            style = typography.labelSm,
            fontWeight = FontWeight.Bold,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        val rulesList = listOf(
            "ghost" to "Ghost Mode",
            "hide_seen" to "Hide Seen",
            "hide_typing" to "Hide Typing",
            "hide_recording" to "Hide Recording",
            "anti_revoke" to "Anti Revoke",
            "freeze_lastseen" to "Freeze Last Seen"
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            rulesList.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowItems.forEach { (key, label) ->
                        val isChecked = rulesState[key] ?: false
                        val cardBg = if (isChecked) colors.primaryContainer else colors.surfaceDim
                        val cardBorderColor = if (isChecked) colors.primary.copy(alpha = 0.3f) else colors.outlineVariant
                        val textColor = if (isChecked) colors.primary else colors.onSurfaceVariant

                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(radius.mdShape)
                                .background(cardBg)
                                .border(1.dp, cardBorderColor, radius.mdShape)
                                .clickable { rulesState[key] = !isChecked }
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                style = typography.bodyMd,
                                fontWeight = FontWeight.Bold,
                                color = textColor,
                                fontSize = 13.sp
                            )
                            // Custom circular indicator
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(if (isChecked) colors.primary else Color.Transparent)
                                    .border(2.dp, if (isChecked) colors.primary else Color(0xFFCBCED4), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isChecked) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(Color.White)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        // Rule Scope Section
        Text(
            text = "RULE SCOPE",
            style = typography.labelSm,
            fontWeight = FontWeight.Bold,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Surface(
            shape = radius.lgShape,
            color = colors.surfaceDim,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                val scopes = listOf("always" to "Always", "scheduled" to "Scheduled", "temporary" to "Temporary")
                scopes.forEachIndexed { idx, (scopeId, label) ->
                    val isSelected = selectedScope == scopeId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedScope = scopeId }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = label,
                            style = typography.bodyLg,
                            color = colors.onSurface
                        )
                        // Custom scope radio circle
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .border(2.dp, if (isSelected) colors.primary else Color(0xFFCBCED4), CircleShape)
                                .padding(3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                        .background(colors.primary)
                                )
                            }
                        }
                    }
                    if (idx < scopes.lastIndex) {
                        HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        // Save Rules Button
        Button(
            onClick = onDismiss,
            shape = radius.lgShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(bottom = 4.dp)
        ) {
            Text(text = "Save Rules", style = typography.bodyLg, fontWeight = FontWeight.Bold)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PerContactPrivacyModalPreview() {
    WaexTheme {
        PerContactPrivacyModal(onDismiss = {})
    }
}
