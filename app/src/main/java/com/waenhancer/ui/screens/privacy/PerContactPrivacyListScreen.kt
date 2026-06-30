package com.waenhancer.ui.screens.privacy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.ContactPrivacy

@Composable
fun PerContactPrivacyListScreen(
    contacts: List<ContactPrivacy>,
    onClearContact: (ContactPrivacy) -> Unit,
    onClearAll: () -> Unit,
    onUpdateContact: (ContactPrivacy) -> Unit
) {
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var selectedContact by remember { mutableStateOf<ContactPrivacy?>(null) }
    var showEditModal by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (contacts.isEmpty()) {
            // Empty state
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = spacing.pageMargin),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(colors.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = WaexIcons.Security,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "No custom rules yet",
                    style = typography.headlineMd,
                    fontWeight = FontWeight.Bold,
                    color = colors.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Contacts with custom privacy rules will appear here. You can set per-contact rules from inside a WhatsApp chat.",
                    style = typography.bodyMd,
                    color = colors.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // Clear all button header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.pageMargin, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${contacts.size} contact${if (contacts.size != 1) "s" else ""} with custom rules",
                        style = typography.bodyMd,
                        color = colors.onSurfaceVariant
                    )
                    TextButton(
                        onClick = onClearAll,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE53935))
                    ) {
                        Text(
                            text = "Clear All",
                            style = typography.bodyMd,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Contact list
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = spacing.pageMargin),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(contacts, key = { it.id }) { contact ->
                        ContactPrivacyCard(
                            contact = contact,
                            onEdit = {
                                selectedContact = contact
                                showEditModal = true
                            },
                            onClear = { onClearContact(contact) }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(100.dp)) }
                }
            }
        }

        // Edit modal overlay
        AnimatedVisibility(
            visible = showEditModal && selectedContact != null,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(250)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable {
                        showEditModal = false
                        selectedContact = null
                    }
            )
        }

        AnimatedVisibility(
            visible = showEditModal && selectedContact != null,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(350)) +
                    fadeIn(animationSpec = tween(300)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)) +
                    fadeOut(animationSpec = tween(250)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = false) { }
                    .clip(radius.bottomSheetShape),
                color = colors.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(top = 12.dp, bottom = 24.dp)) {
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

@Composable
private fun ContactPrivacyCard(
    contact: ContactPrivacy,
    onEdit: () -> Unit,
    onClear: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    val activeRules = listOfNotNull(
        if (contact.ghost) "Ghost" else null,
        if (contact.hideSeen) "Hide Seen" else null,
        if (contact.hideTyping) "Hide Typing" else null,
        if (contact.hideRecording) "Hide Rec." else null,
        if (contact.antiRevoke) "Anti Revoke" else null,
        if (contact.freezeLastSeen) "Freeze LS" else null
    )

    Surface(
        shape = radius.bentoCardShape,
        color = colors.surfaceDim,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(colors.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = contact.name.firstOrNull()?.uppercase() ?: "?",
                        style = typography.bodyLg,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                        fontSize = 18.sp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = contact.name,
                        style = typography.bodyLg,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    Text(
                        text = contact.jid,
                        style = typography.bodyMd,
                        color = colors.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }

                // Scope badge
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.1f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = contact.scope.replaceFirstChar { it.uppercase() },
                        style = typography.labelSm,
                        fontWeight = FontWeight.Medium,
                        color = colors.primary,
                        fontSize = 10.sp
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Clear button
                IconButton(
                    onClick = onClear,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE53935).copy(alpha = 0.08f))
                ) {
                    Icon(
                        imageVector = WaexIcons.Clear,
                        contentDescription = "Clear",
                        tint = Color(0xFFE53935),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // Active rules chips
            if (activeRules.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    activeRules.take(5).forEach { rule ->
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(colors.primary.copy(alpha = 0.08f))
                                .border(1.dp, colors.primary.copy(alpha = 0.2f), CircleShape)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = rule,
                                style = typography.labelSm,
                                color = colors.primary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    if (activeRules.size > 5) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(colors.surfaceDim)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "+${activeRules.size - 5}",
                                style = typography.labelSm,
                                color = colors.onSurfaceVariant,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
