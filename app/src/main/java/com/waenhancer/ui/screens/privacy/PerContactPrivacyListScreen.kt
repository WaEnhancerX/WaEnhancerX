package com.waenhancer.ui.screens.privacy

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.ContactPrivacy
import com.waenhancer.ui.navigation.LocalWaexNavController

@Composable
fun PerContactPrivacyListScreen(
    contacts: List<ContactPrivacy>,
    onClearContact: (ContactPrivacy) -> Unit,
    onClearAll: () -> Unit,
    onUpdateContact: (ContactPrivacy) -> Unit
) {
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var selectedContact by remember { mutableStateOf<ContactPrivacy?>(null) }
    var showEditModal by remember { mutableStateOf(false) }
    var showClearAllModal by remember { mutableStateOf(false) }

    // Close modals on back press instead of navigating back
    BackHandler(enabled = showEditModal || showClearAllModal) {
        showEditModal = false
        showClearAllModal = false
        selectedContact = null
    }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = "Per Contact Rules",
                titleStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    lineHeight = 22.sp
                ),
                onBackClick = { navController.popBack() },
                actions = {
                    if (contacts.isNotEmpty()) {
                        TextButton(
                            onClick = { showClearAllModal = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE53935))
                        ) {
                            Text(
                                text = "Clear All",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            )
        },
        containerColor = colors.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {

            // ── Main content ──────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (contacts.isEmpty()) {
                    // Empty state
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = spacing.pageMargin)
                            .padding(top = 80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(colors.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = WaexIcons.Security,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Text(
                                text = "No custom rules yet",
                                style = typography.headlineMd,
                                fontWeight = FontWeight.Bold,
                                color = colors.onBackground
                            )
                            Text(
                                text = "Contacts with custom privacy rules will appear here. Rules can be set from inside a WhatsApp conversation.",
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    // Grouped card – all contacts inside one surface with dividers (with compact horizontal margins)
                    Surface(
                        shape = radius.bentoCardShape,
                        color = colors.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                    ) {
                        Column {
                            contacts.forEachIndexed { index, contact ->
                                ContactPrivacyRow(
                                    contact = contact,
                                    onEdit = {
                                        selectedContact = contact
                                        showEditModal = true
                                    },
                                    onClear = { onClearContact(contact) }
                                )
                                if (index < contacts.lastIndex) {
                                    HorizontalDivider(
                                        thickness = 1.dp,
                                        color = colors.outlineVariant.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(80.dp))
            }

            // ── Scrim ─────────────────────────────────────────────────────────────
            AnimatedVisibility(
                visible = (showEditModal && selectedContact != null) || showClearAllModal,
                enter = fadeIn(animationSpec = tween(250)),
                exit = fadeOut(animationSpec = tween(200)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable {
                            showEditModal = false
                            showClearAllModal = false
                            selectedContact = null
                        }
                )
            }

            // ── Bottom sheet ──────────────────────────────────────────────────────
            AnimatedVisibility(
                visible = (showEditModal && selectedContact != null) || showClearAllModal,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(durationMillis = 350)
                ) + fadeIn(animationSpec = tween(300)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(durationMillis = 300)
                ) + fadeOut(animationSpec = tween(250)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (showClearAllModal) {
                                Modifier.wrapContentHeight()
                            } else {
                                Modifier.fillMaxHeight(0.9f)
                            }
                        )
                        .clickable(enabled = false) { }
                        .clip(radius.bottomSheetShape),
                    color = colors.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant)
                ) {
                    if (showClearAllModal) {
                        ClearAllConfirmationModal(
                            onDismiss = { showClearAllModal = false },
                            onConfirm = {
                                onClearAll()
                                showClearAllModal = false
                            }
                        )
                    } else {
                        selectedContact?.let { contact ->
                            PerContactPrivacyModal(
                                contact = contact,
                                onDismiss = {
                                    showEditModal = false
                                    selectedContact = null
                                },
                                onSave = { updated ->
                                    onUpdateContact(updated)
                                    showEditModal = false
                                    selectedContact = null
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Single contact row – matches the preference list aesthetic with compact padding
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ContactPrivacyRow(
    contact: ContactPrivacy,
    onEdit: () -> Unit,
    onClear: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography

    val phoneDisplay = contact.jid.substringBefore("@")

    // Define color codes for each active rule to show beautiful chips
    val activeRulesList = remember(contact) {
        val list = mutableListOf<Pair<String, Color>>()
        if (contact.hideSeen) list.add("Seen" to Color(0xFF1E88E5)) // Blue
        if (contact.hideTyping) list.add("Typing" to Color(0xFF43A047)) // Green
        if (contact.hideRecording) list.add("Rec" to Color(0xFFFB8C00)) // Orange
        if (contact.antiRevoke) list.add("Anti-Revoke" to Color(0xFFE53935)) // Red
        if (contact.freezeLastSeen) list.add("Freeze" to Color(0xFF00ACC1)) // Teal
        list
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar – beautiful gradient circle with bold initial
        val avatarGradient = Brush.linearGradient(
            colors = listOf(
                colors.primary,
                colors.secondary
            )
        )
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(avatarGradient),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = contact.name.firstOrNull()?.uppercase() ?: "?",
                style = typography.bodyLg,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 18.sp
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Name + phone + micro-chips
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = contact.name,
                style = typography.bodyLg,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = phoneDisplay,
                    style = typography.bodyMd,
                    color = colors.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Text(
                    text = "•",
                    style = typography.bodyMd,
                    color = colors.onSurfaceVariant.copy(alpha = 0.5f),
                    fontSize = 12.sp
                )
                val scopeDisplay = if (contact.scope == "scheduled") {
                    val startAmPm = if (contact.startHour >= 12) "PM" else "AM"
                    val startDisplay = when {
                        contact.startHour == 0 -> 12
                        contact.startHour > 12 -> contact.startHour - 12
                        else -> contact.startHour
                    }
                    val endAmPm = if (contact.endHour >= 12) "PM" else "AM"
                    val endDisplay = when {
                        contact.endHour == 0 -> 12
                        contact.endHour > 12 -> contact.endHour - 12
                        else -> contact.endHour
                    }
                    String.format("Scheduled (%02d:00 %s - %02d:00 %s)", startDisplay, startAmPm, endDisplay, endAmPm)
                } else {
                    contact.scope.replaceFirstChar { it.uppercase() }
                }
                Text(
                    text = scopeDisplay,
                    style = typography.bodyMd,
                    color = colors.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            if (activeRulesList.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                // Horizontal scrolling row for rule indicator chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    activeRulesList.forEach { (label, tintColor) ->
                        RuleMicroChip(text = label, color = tintColor)
                    }
                }
            } else {
                Text(
                    text = "No active rules",
                    style = typography.bodyMd,
                    color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            }
        }

        // Chevron
        Icon(
            imageVector = WaexIcons.ChevronRight,
            contentDescription = null,
            tint = colors.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(16.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Clear button
        IconButton(
            onClick = onClear,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(colors.error.copy(alpha = 0.08f))
        ) {
            Icon(
                imageVector = WaexIcons.Clear,
                contentDescription = "Remove custom rules",
                tint = colors.error,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun RuleMicroChip(
    text: String,
    color: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bottom sheet confirmation modal to clear all rules
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ClearAllConfirmationModal(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(bottom = 24.dp)
    ) {
        // Grabber handle
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(colors.outlineVariant)
                .align(Alignment.CenterHorizontally)
        )
        Spacer(modifier = Modifier.height(24.dp))

        // Warning Icon + Title
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.pageMargin),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(colors.error.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = WaexIcons.Warning,
                    contentDescription = null,
                    tint = colors.error,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Clear All Custom Rules?",
                style = typography.bodyLg,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Are you sure you want to remove all custom privacy rules? This action will restore default privacy rules for all contacts and cannot be undone.",
                style = typography.bodyMd,
                color = colors.onSurfaceVariant,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.6f))
        Spacer(modifier = Modifier.height(16.dp))

        // Confirm / Cancel Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.pageMargin),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onDismiss,
                shape = radius.lgShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.surfaceDim,
                    contentColor = colors.onSurfaceVariant
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text(text = "Cancel", style = typography.bodyLg, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onConfirm,
                shape = radius.lgShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.error,
                    contentColor = colors.onError
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text(text = "Clear All", style = typography.bodyLg, fontWeight = FontWeight.Bold)
            }
        }
    }
}
