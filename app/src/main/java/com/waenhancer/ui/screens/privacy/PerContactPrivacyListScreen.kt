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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.ContactPrivacy
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.components.StitchSwitch

@Composable
fun PerContactPrivacyListScreen(
    contacts: List<ContactPrivacy>,
    onClearContact: (ContactPrivacy) -> Unit,
    onClearAll: () -> Unit,
    onUpdateContact: (ContactPrivacy) -> Unit
) {
    val navController = LocalWaexNavController.current
    val preferenceManager = com.waenhancer.ui.navigation.LocalWaexPreferenceManager.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var selectedTab by remember { mutableStateOf("config") } // "config" | "rules"
    var isCustomPrivacyEnabled by remember {
        mutableStateOf(preferenceManager.getBoolean("custom_privacy", true))
    }
    var customPrivacyType by remember {
        mutableStateOf(preferenceManager.getString("custom_privacy_type", "1"))
    }

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
                onBackClick = { navController.popBack() },
                actions = {
                    if (selectedTab == "rules" && contacts.isNotEmpty()) {
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
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // ── Segmented Tab Bar ──────────────────────────────────────────
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .height(48.dp)
                        .clip(radius.defaultShape)
                        .background(colors.surfaceDim)
                        .border(1.dp, colors.outlineVariant.copy(alpha = 0.5f), radius.defaultShape)
                        .padding(4.dp)
                ) {
                    val tabWidth = maxWidth / 2
                    val tabOffset = if (selectedTab == "config") 0.dp else tabWidth

                    val animatedOffset by animateDpAsState(
                        targetValue = tabOffset,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessLow
                        ),
                        label = "tab_offset"
                    )

                    // Sliding highlight box
                    Box(
                        modifier = Modifier
                            .offset(x = animatedOffset)
                            .width(tabWidth)
                            .fillMaxHeight()
                            .clip(radius.defaultShape)
                            .background(colors.surface)
                            .border(1.dp, colors.outlineVariant, radius.defaultShape)
                    )

                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        listOf(
                            "config" to "Configuration",
                            "rules" to if (contacts.isNotEmpty()) "Contact Rules (${contacts.size})" else "Contact Rules"
                        ).forEach { (tabId, label) ->
                            val isSelected = selectedTab == tabId
                            val textColor by animateColorAsState(
                                targetValue = if (isSelected) colors.primary else colors.onSurfaceVariant,
                                animationSpec = tween(150),
                                label = "tab_text_color"
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight(1f)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { selectedTab = tabId },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = typography.bodyMd,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = textColor
                                )
                            }
                        }
                    }
                }

                // ── Tab Content ────────────────────────────────────────────────
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(150))
                    },
                    modifier = Modifier.fillMaxSize(),
                    label = "per_contact_tab_content"
                ) { tab ->
                    if (tab == "config") {
                        // ── Tab 1: Configuration ──────────────────────────────
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Master Toggle Card
                            Surface(
                                shape = radius.bentoCardShape,
                                color = colors.surfaceDim,
                                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Enable Per-Contact Privacy",
                                            style = typography.bodyLg,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.onSurface
                                        )
                                        Text(
                                            text = "Allow granular privacy overrides for specific contacts or groups",
                                            style = typography.bodyMd,
                                            color = colors.onSurfaceVariant,
                                            fontSize = 12.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    StitchSwitch(
                                        checked = isCustomPrivacyEnabled,
                                        onCheckedChange = { checked ->
                                            isCustomPrivacyEnabled = checked
                                            preferenceManager.putBoolean("custom_privacy", checked)
                                        }
                                    )
                                }
                            }

                            // Shortcut Placement Options (when enabled)
                            AnimatedVisibility(
                                visible = isCustomPrivacyEnabled,
                                enter = fadeIn(tween(250)) + slideInVertically(tween(250)),
                                exit = fadeOut(tween(200)) + slideOutVertically(tween(200))
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "IN-APP SHORTCUT PLACEMENT",
                                        style = typography.labelSm,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 4.dp)
                                    )

                                    Surface(
                                        shape = radius.bentoCardShape,
                                        color = colors.surfaceDim,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            val options = listOf(
                                                Triple("1", "Contact & Group Info Screen", "Adds a dedicated Custom Privacy tile on contact and group details page"),
                                                Triple("2", "Chat 3-Dot Options Menu", "Adds 'Custom Privacy' directly into the top-right options menu in chats"),
                                                Triple("3", "Both (Info Screen & 3-Dot Menu)", "Displays the custom privacy shortcut in both places")
                                            )

                                            options.forEachIndexed { index, (typeValue, title, desc) ->
                                                val isSelected = customPrivacyType == typeValue
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            customPrivacyType = typeValue
                                                            preferenceManager.putString("custom_privacy_type", typeValue)
                                                        }
                                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    // Compact Radio Indicator
                                                    Box(
                                                        modifier = Modifier
                                                            .size(18.dp)
                                                            .clip(CircleShape)
                                                            .border(
                                                                1.5.dp,
                                                                if (isSelected) colors.primary else colors.outlineVariant,
                                                                CircleShape
                                                            ),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        if (isSelected) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(9.dp)
                                                                    .clip(CircleShape)
                                                                    .background(colors.primary)
                                                            )
                                                        }
                                                    }

                                                    Spacer(modifier = Modifier.width(12.dp))

                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = title,
                                                            style = typography.bodyMd,
                                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                                            color = if (isSelected) colors.primary else colors.onSurface,
                                                            fontSize = 13.sp
                                                        )
                                                        Text(
                                                            text = desc,
                                                            style = typography.bodyMd,
                                                            color = colors.onSurfaceVariant,
                                                            fontSize = 11.sp
                                                        )
                                                    }
                                                }

                                                if (index < options.size - 1) {
                                                    HorizontalDivider(
                                                        color = colors.outlineVariant.copy(alpha = 0.5f),
                                                        thickness = 1.dp,
                                                        modifier = Modifier.padding(horizontal = 16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // How It Works Guide Card
                            Surface(
                                shape = radius.bentoCardShape,
                                color = colors.surfaceDim.copy(alpha = 0.6f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "How to Configure Rules",
                                        style = typography.bodyMd,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.onSurface,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "1. Open any chat or contact info page in WhatsApp.\n2. Tap 'Custom Privacy' to set specific overrides.\n3. Saved rules take priority over global privacy settings.",
                                        style = typography.bodyMd,
                                        color = colors.onSurfaceVariant,
                                        lineHeight = 17.sp,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            if (contacts.isNotEmpty()) {
                                Button(
                                    onClick = { selectedTab = "rules" },
                                    shape = radius.buttonShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colors.primaryContainer,
                                        contentColor = colors.onPrimary
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "View ${contacts.size} Configured Contact(s) →",
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    } else {
                        // ── Tab 2: Contact Rules List ───────────────────────────
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            if (!isCustomPrivacyEnabled) {
                                Surface(
                                    shape = radius.bentoCardShape,
                                    color = Color(0xFFFFF3E0),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB74D)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = WaexIcons.Warning,
                                            contentDescription = null,
                                            tint = Color(0xFFE65100),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "Per-Contact Privacy is currently disabled in Configuration.",
                                            style = typography.bodyMd,
                                            color = Color(0xFFE65100),
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = {
                                                isCustomPrivacyEnabled = true
                                                preferenceManager.putBoolean("custom_privacy", true)
                                            }
                                        ) {
                                            Text(
                                                text = "Enable",
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFE65100)
                                            )
                                        }
                                    }
                                }
                            }

                            if (contacts.isEmpty()) {
                                // Empty state
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = spacing.pageMargin)
                                        .padding(top = 60.dp),
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
                                // Grouped card – all contacts inside one surface with dividers
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

                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
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
