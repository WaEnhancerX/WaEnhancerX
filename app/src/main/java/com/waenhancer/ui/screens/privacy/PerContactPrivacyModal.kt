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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.components.WaexSwitchPreference
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.ContactPrivacy

@Composable
fun PerContactPrivacyModal(
    onDismiss: () -> Unit,
    contact: ContactPrivacy? = null,
    onSave: ((ContactPrivacy) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var selectedScope by remember { mutableStateOf(contact?.scope ?: "always") } // "always" | "scheduled" | "temporary"
    var startHour by remember { mutableStateOf(contact?.startHour ?: 9) }
    var endHour by remember { mutableStateOf(contact?.endHour ?: 18) }

    fun formatTime(hour: Int): String {
        val amPm = if (hour >= 12) "PM" else "AM"
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return String.format("%02d:00 %s", displayHour, amPm)
    }

    val rulesState = remember {
        mutableStateMapOf(
            "ghost" to (contact?.ghost ?: true),
            "hide_seen" to (contact?.hideSeen ?: false),
            "hide_typing" to (contact?.hideTyping ?: true),
            "hide_recording" to (contact?.hideRecording ?: false),
            "anti_revoke" to (contact?.antiRevoke ?: true),
            "freeze_lastseen" to (contact?.freezeLastSeen ?: false)
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
    ) {
        // ── STATIC HEADER SECTION ─────────────────────────────────────────────
        
        // Statically positioned grabber thumb
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(colors.outlineVariant)
                .align(Alignment.CenterHorizontally)
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Title and close button row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.pageMargin),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Custom Privacy Rules",
                    style = typography.bodyLg,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    fontSize = 18.sp
                )
                Text(
                    text = "Configure rules for this specific contact",
                    style = typography.bodyMd,
                    color = colors.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceDim)
            ) {
                Icon(
                    imageVector = WaexIcons.Clear,
                    contentDescription = "Close",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))

        // Profile details row (avatar + name + phone)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.pageMargin),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val avatarGradient = Brush.linearGradient(
                colors = listOf(colors.primary, colors.secondary)
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(avatarGradient),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contact?.name?.firstOrNull()?.uppercase() ?: "?",
                    style = typography.bodyLg,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 18.sp
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = contact?.name ?: "Unknown Contact",
                    style = typography.bodyLg.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onSurface
                )
                val phoneDisplay = contact?.jid?.substringBefore("@") ?: ""
                Text(
                    text = phoneDisplay,
                    style = typography.bodyMd,
                    color = colors.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        
        HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.6f))

        // ── SCROLLABLE SETTINGS CONTENT ──────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.pageMargin, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section: Privacy Rules
            Text(
                text = "PRIVACY RULES",
                style = typography.labelSm,
                fontWeight = FontWeight.Bold,
                color = colors.onSurfaceVariant
            )

            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceDim,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    val ruleItems = listOf(
                        Triple("ghost", "Ghost Mode", "Appear completely offline"),
                        Triple("hide_seen", "Hide Seen Tick", "Hide read confirmation"),
                        Triple("hide_typing", "Hide Typing Status", "Hide typing indicator"),
                        Triple("hide_recording", "Hide Recording Status", "Hide audio recording state"),
                        Triple("anti_revoke", "Anti-Revoke Messages", "Keep deleted messages visible"),
                        Triple("freeze_lastseen", "Freeze Last Seen", "Lock your online timestamp")
                    )

                    ruleItems.forEachIndexed { index, (key, label, desc) ->
                        val isChecked = rulesState[key] ?: false
                        WaexSwitchPreference(
                            title = label,
                            description = desc,
                            checked = isChecked,
                            onCheckedChange = { rulesState[key] = it },
                            showDivider = index < ruleItems.lastIndex
                        )
                    }
                }
            }

            // Section: Rule Scope
            Text(
                text = "RULE SCOPE",
                style = typography.labelSm,
                fontWeight = FontWeight.Bold,
                color = colors.onSurfaceVariant
            )

            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceDim,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    val scopes = listOf(
                        "always" to "Always Active",
                        "scheduled" to "Scheduled Rules",
                        "temporary" to "Temporary Lockout"
                    )
                    scopes.forEachIndexed { idx, (scopeId, label) ->
                        val isSelected = selectedScope == scopeId
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedScope = scopeId }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = label,
                                        style = typography.bodyLg.copy(fontWeight = FontWeight.Medium),
                                        color = colors.onSurface
                                    )
                                    val desc = when (scopeId) {
                                        "always" -> "Rules apply at all times"
                                        "scheduled" -> "Apply during set hours"
                                        else -> "Reset automatically after 24 hours"
                                    }
                                    Text(
                                        text = desc,
                                        style = typography.bodyMd,
                                        color = colors.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                                
                                // Scope radio circle indicator
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .border(2.dp, if (isSelected) colors.primary else colors.outlineVariant, CircleShape)
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
                            
                            // Active hours selector for Scheduled Rules
                            if (scopeId == "scheduled") {
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = isSelected
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(colors.surfaceContainerLow)
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.4f))
                                        Text(
                                            text = "Active Hours Range",
                                            style = typography.labelSm,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.primary
                                        )
                                        
                                        // Start Hour Select
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(text = "Start Hour", style = typography.bodyMd, color = colors.onSurface)
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                androidx.compose.material3.TextButton(
                                                    onClick = {
                                                        startHour = (startHour - 1 + 24) % 24
                                                    },
                                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                                                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)
                                                ) {
                                                    Text("-", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                                }
                                                Text(
                                                    text = formatTime(startHour),
                                                    style = typography.bodyLg,
                                                    fontWeight = FontWeight.Bold,
                                                    color = colors.primary,
                                                    modifier = Modifier.padding(horizontal = 8.dp)
                                                )
                                                androidx.compose.material3.TextButton(
                                                    onClick = {
                                                        startHour = (startHour + 1) % 24
                                                    },
                                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                                                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)
                                                ) {
                                                    Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }

                                        // End Hour Select
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(text = "End Hour", style = typography.bodyMd, color = colors.onSurface)
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                androidx.compose.material3.TextButton(
                                                    onClick = {
                                                        endHour = (endHour - 1 + 24) % 24
                                                    },
                                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                                                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)
                                                ) {
                                                    Text("-", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                                }
                                                Text(
                                                    text = formatTime(endHour),
                                                    style = typography.bodyLg,
                                                    fontWeight = FontWeight.Bold,
                                                    color = colors.primary,
                                                    modifier = Modifier.padding(horizontal = 8.dp)
                                                )
                                                androidx.compose.material3.TextButton(
                                                    onClick = {
                                                        endHour = (endHour + 1) % 24
                                                    },
                                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                                                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)
                                                ) {
                                                    Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (idx < scopes.lastIndex) {
                            HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }

        // ── STATIC ACTION FOOTER ──────────────────────────────────────────────
        HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.6f))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.pageMargin, vertical = 12.dp)
        ) {
            Button(
                onClick = {
                    if (contact != null && onSave != null) {
                        onSave(
                            contact.copy(
                                ghost = rulesState["ghost"] ?: contact.ghost,
                                hideSeen = rulesState["hide_seen"] ?: contact.hideSeen,
                                hideTyping = rulesState["hide_typing"] ?: contact.hideTyping,
                                hideRecording = rulesState["hide_recording"] ?: contact.hideRecording,
                                antiRevoke = rulesState["anti_revoke"] ?: contact.antiRevoke,
                                freezeLastSeen = rulesState["freeze_lastseen"] ?: contact.freezeLastSeen,
                                scope = selectedScope,
                                startHour = startHour,
                                endHour = endHour
                            )
                        )
                    }
                    onDismiss()
                },
                shape = radius.lgShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(text = "Save Rules", style = typography.bodyLg, fontWeight = FontWeight.Bold)
            }
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
