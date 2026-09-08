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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.components.StitchSwitch
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import com.waenhancer.ui.navigation.LocalWaexNavController
import kotlinx.coroutines.delay


@Composable
fun GlobalPrivacySettingsScreen(
    onOpenModal: (String) -> Unit
) {
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var selectedTab by remember { mutableStateOf("privacy") } // "privacy" | "conversation"

    val navController = LocalWaexNavController.current
    val scrollState = rememberScrollState()
    val itemCoordinates = remember { mutableStateMapOf<String, Float>() }
    var containerY by remember { mutableStateOf(0f) }

    LaunchedEffect(navController.targetSubTabId) {
        val target = navController.targetSubTabId
        if (target == "privacy" || target == "conversation") {
            selectedTab = target
            navController.targetSubTabId = null
        }
    }

    LaunchedEffect(navController.scrollToTargetKey, itemCoordinates.keys.toList()) {
        val target = navController.scrollToTargetKey
        if (target != null) {
            delay(100)
            if (itemCoordinates.containsKey(target)) {
                val yOffset = itemCoordinates[target] ?: 0f
                scrollState.animateScrollTo(yOffset.toInt())
                navController.scrollToTargetKey = null
            }
        }
    }

    LaunchedEffect(navController.highlightTargetKey) {
        val target = navController.highlightTargetKey
        if (target != null) {
            delay(2000)
            if (navController.highlightTargetKey == target) {
                navController.highlightTargetKey = null
            }
        }
    }

    val preferenceManager = com.waenhancer.ui.navigation.LocalWaexPreferenceManager.current
    val privacyKeys = remember {
        listOf(
            "always_online", "online_status_indicator", "freeze_last_seen", "ghostmode", "ghostmode_t", "ghostmode_r", "typing_privacy",
            "hide_read_receipts", "hideread_group", "blueonreply", "seentick", "hide_delivery_receipts", "hide_seen_receipts", "hideonceseen",
            "stealth_status_view", "autonext_status", "toast_viewed_status",
            "anti_view_once", "downloadviewonce",
            "anti_revoke", "antirevokestatus", "antidisappearing", "toastdeleted", "toast_viewed_message",
            "call_privacy", "call_info",
            "locked_chats_enhancer", "typearchive", "dnd_mode", "hide_chats", "custom_privacy",
            "anti_edit_messages", "preserve_delete_for_me", "revokeallmessages", "hide_forwarded_tag", "removeforwardlimit",
            "sticker_confirm_alert", "removeseemore", "stamp_copied_message", "doubletap2like",
            "jump_to_first_message", "unlimited_pinned_chats", "broadcast_tag", "direct_chat_dialer", "inline_translation", "copy_status_text",
            "call_type_controller", "custom_filter_groups", "group_admin_tools", "status_text_composer"
        )
    }

    val settingsState = remember {
        mutableStateMapOf<String, Boolean>().apply {
            privacyKeys.forEach { key ->
                put(key, preferenceManager.getBoolean(key, false))
            }
        }
    }

    val updatePreference: (String, Boolean) -> Unit = { key, value ->
        settingsState[key] = value
        preferenceManager.putBoolean(key, value)
    }

    var showColorPickerDialog by remember { mutableStateOf(false) }
    var deletedMessageColor by remember {
        mutableStateOf(preferenceManager.getString("deleted_message_color", "#EF4444"))
    }

    val privacyGroups = listOf(
        SettingGroup(
            "Presence & Online Visibility",
            listOf(
                SettingItem("Always Online", "Keep connection status constantly showing as Online inside WhatsApp", "always_online"),
                SettingItem("Hide Online Status", "Hide your green online dot and online status from others", "online_status_indicator"),
                SettingItem("Freeze Last Seen", "Lock your last seen timestamp in place", "freeze_last_seen"),
                SettingItem("Ghost Mode", "One-tap total invisibility (stealth read, status & presence)", "ghostmode"),
                SettingItem("Hide Typing Indicator", "Hides typing indicator from chats and groups", "ghostmode_t"),
                SettingItem("Hide Recording Indicator", "Hides audio recording indicator from chats", "ghostmode_r")
            )
        ),
        SettingGroup(
            "Ticks & Seen Receipts",
            listOf(
                SettingItem("Hide Read Receipts (Blue Ticks)", "Read messages without sending blue read checkmarks", "hide_read_receipts"),
                SettingItem("Hide Read Receipts for Groups", "Prevent blue ticks on group messages", "hideread_group"),
                SettingItem("Blue Tick on Reply", "Only send blue ticks once you reply to a message", "blueonreply"),
                SettingItem("Show Mark as Seen Button", "Add a manual double-tick button inside chats and statuses", "seentick"),
                SettingItem("Hide Delivery Receipts (Second Tick)", "Receive messages without sending second delivery checkmark", "hide_delivery_receipts"),
                SettingItem("Hide Voice Note Played Receipts", "Listen to voice notes without turning microphone blue", "hide_seen_receipts"),
                SettingItem("Hide View Once Seen Receipts", "Open view once messages without marking them as opened", "hideonceseen")
            )
        ),
        SettingGroup(
            "Status Privacy",
            listOf(
                SettingItem("Stealth Status Viewing", "View status updates without sending view receipts", "stealth_status_view"),
                SettingItem("Disable Auto-Skip Status", "Prevent WhatsApp from automatically advancing to the next status", "autonext_status"),
                SettingItem("Toast on Status Viewed", "Show a toast notification when someone views your status", "toast_viewed_status")
            )
        ),
        SettingGroup(
            "View Once Protection",
            listOf(
                SettingItem("Anti-View Once", "Open and view view-once photos & videos unlimited times", "anti_view_once"),
                SettingItem("Download View Once Media", "Show download button for view once photos and videos", "downloadviewonce")
            )
        ),
        SettingGroup(
            "Anti-Revoke & Deletion Defense",
            listOf(
                SettingItem("Anti-Revoke Messages", "Keep deleted messages visible in conversations", "anti_revoke"),
                SettingItem("Anti-Revoke Statuses", "Keep deleted status updates visible in updates tab", "antirevokestatus"),
                SettingItem("Anti-Disappearing Messages", "Prevent messages from disappearing when timer expires", "antidisappearing"),
                SettingItem("Toast on Deleted Message", "Show alert notification when a contact deletes a message", "toastdeleted"),
                SettingItem("Toast on Viewed Message", "Show alert notification when your message is viewed", "toast_viewed_message")
            )
        ),
        SettingGroup(
            "Calls Privacy & Control",
            listOf(
                SettingItem("Call Privacy & Blocker", "Block incoming WhatsApp calls from non-contacts or everyone", "call_privacy"),
                SettingItem("Additional Call Information", "Display detailed network & codec info during active calls", "call_info")
            )
        ),
        SettingGroup(
            "Security & Vault",
            listOf(
                SettingItem("Locked Chats Enhancer", "Customize and bypass locks for specific chat vaults", "locked_chats_enhancer"),
                SettingItem("Hide Archived Chats", "Completely hide archived chats from the main conversations list", "typearchive"),
                SettingItem("Do Not Disturb (DND) Mode", "Temporarily block all incoming traffic dynamically", "dnd_mode"),
                SettingItem("Hide Chats / Vault", "Hide and lock private chats from the main chat list", "hide_chats")
            )
        )
    )

    val conversationGroups = listOf(
        SettingGroup(
            "Message Protection & Controls",
            listOf(
                SettingItem("Anti-Edit Messages", "Keep original version of edited messages in chat", "anti_edit_messages"),
                SettingItem("Preserve Delete for Me", "Retain messages locally when Delete for Me is selected", "preserve_delete_for_me"),
                SettingItem("Revoke All Messages Bypass", "Allow Delete for Everyone without time window limits", "revokeallmessages"),
                SettingItem("Hide Forwarded Tag", "Prevent forwarded tag from appearing on shared messages", "hide_forwarded_tag"),
                SettingItem("Remove Forward Limit", "Forward messages to unlimited contacts simultaneously", "removeforwardlimit"),
                SettingItem("Sticker Confirmation Alert", "Ask for confirmation before sending clicked stickers", "sticker_confirm_alert"),
                SettingItem("Remove See More Button", "Display entire long text messages without truncating", "removeseemore"),
                SettingItem("Copied Message Timestamp", "Include timestamp information when copying messages", "stamp_copied_message"),
                SettingItem("Double Tap to React", "Double tap any message bubble to send instant reaction", "doubletap2like")
            )
        ),
        SettingGroup(
            "Chat Navigation & Shortcuts",
            listOf(
                SettingItem("Jump to First Message", "Add an option to jump directly to the beginning of any chat", "jump_to_first_message"),
                SettingItem("Unlimited Pinned Chats", "Pin more than 3 chats to the top of your list", "unlimited_pinned_chats"),
                SettingItem("Show Broadcast Tag", "Display broadcast icon on broadcasted conversation messages", "broadcast_tag"),
                SettingItem("Direct Chat Dialer", "Message someone directly without saving their contact info", "direct_chat_dialer"),
                SettingItem("Inline Message Translation", "Tap-to-translate messages directly inline", "inline_translation"),
                SettingItem("Copy Status Text", "Allow copying text from status updates", "copy_status_text")
            )
        ),
        SettingGroup(
            "Chat & Group Utilities",
            listOf(
                SettingItem("Call Type Controller", "Force voice-only or video-only incoming calls", "call_type_controller"),
                SettingItem("Custom Filter Groups", "Group chats by custom categories", "custom_filter_groups"),
                SettingItem("Group Admin Tools", "Unlock hidden moderation controls", "group_admin_tools"),
                SettingItem("Status Text Composer Enhancements", "Format text status updates with custom palettes", "status_text_composer")
            )
        )
    )



    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .onGloballyPositioned { containerCoordinates ->
                containerY = containerCoordinates.positionInRoot().y
            }
            .padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Tab switcher header
        Surface(
            shape = radius.mdShape,
            color = colors.surfaceDim,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.pageMargin)
                .height(48.dp)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
            ) {
                val totalWidth = maxWidth
                val tabWidth = totalWidth / 2

                val activeIndex = if (selectedTab == "privacy") 0 else 1
                val targetOffset = tabWidth * activeIndex
                val animatedOffset by animateDpAsState(
                    targetValue = targetOffset,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "sub_tab_offset"
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
                    listOf("privacy" to "Privacy", "conversation" to "Conversation").forEach { (tabId, label) ->
                        val isSelected = selectedTab == tabId
                        val textColor by animateColorAsState(
                            targetValue = if (isSelected) colors.primary else colors.onSurfaceVariant,
                            animationSpec = tween(150),
                            label = "sub_tab_text_color"
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(1f)
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
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
        }

        // Per Contact Rules shortcut card (only on Privacy tab)
        if (selectedTab == "privacy") {
            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceDim,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.pageMargin)
                    .clickable { navController.navigateTo(com.waenhancer.ui.navigation.Screen.PerContactPrivacyList) }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.primary.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = WaexIcons.Security,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Per Contact Rules",
                            style = typography.bodyLg,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface
                        )
                        Text(
                            text = "Configure granular privacy rules per contact",
                            style = typography.bodyMd,
                            color = colors.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = WaexIcons.ChevronRight,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceDim,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.pageMargin)
                    .clickable { navController.navigateTo(com.waenhancer.ui.navigation.Screen.DeletedMessages) }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFEF4444).copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = WaexIcons.Folder,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "\"Delete For Me\" Messages",
                            style = typography.bodyLg,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface
                        )
                        Text(
                            text = "View preserved delete-for-me chats & group logs",
                            style = typography.bodyMd,
                            color = colors.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = WaexIcons.ChevronRight,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }


        // Setting Groups
        val activeGroups = if (selectedTab == "privacy") privacyGroups else conversationGroups
        activeGroups.forEach { group ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.pageMargin)
            ) {
                Text(
                    text = group.title,
                    style = typography.labelSm,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )

                Surface(
                    shape = radius.bentoCardShape,
                    color = colors.surfaceDim,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        group.items.forEachIndexed { index, item ->
                            val isHighlighted = navController.highlightTargetKey == item.key
                            val highlightBgColor by animateColorAsState(
                                targetValue = if (isHighlighted) colors.primary.copy(alpha = 0.15f) else Color.Transparent,
                                animationSpec = tween(durationMillis = 300),
                                label = "highlight_bg"
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(highlightBgColor)
                                    .onGloballyPositioned { coordinates ->
                                        val y = coordinates.positionInRoot().y - containerY + scrollState.value
                                        itemCoordinates[item.key] = y
                                    }
                                    .clickable {
                                        val currentVal = settingsState[item.key] ?: false
                                        updatePreference(item.key, !currentVal)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = item.label,
                                        style = typography.bodyLg,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = item.sublabel,
                                        style = typography.bodyMd,
                                        color = colors.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                                StitchSwitch(
                                    checked = settingsState[item.key] ?: false,
                                    onCheckedChange = { updatePreference(item.key, it) }
                                )
                            }

                            // If this is anti_revoke and it is enabled, show indicator choice chips and color sub-preferences
                            if (item.key == "anti_revoke" && (settingsState["anti_revoke"] == true)) {
                                val currentIndicator = preferenceManager.getString("anti_revoke_indicator", "2")
                                val isColorEnabled = preferenceManager.getBoolean("anti_revoke_color_enabled", true)

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = colors.surfaceDim.copy(alpha = 0.6f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        // Section 1: Indicator Style with Segmented Control
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(
                                                text = "INDICATOR STYLE",
                                                style = typography.labelSm,
                                                fontWeight = FontWeight.Bold,
                                                color = colors.primary
                                            )
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(colors.surface)
                                                    .padding(4.dp),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                listOf("2" to "Deleted Icon 🚫", "1" to "Text \"Deleted\"").forEach { (valKey, title) ->
                                                    val isSelected = currentIndicator == valKey
                                                    Box(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .background(if (isSelected) colors.primary else Color.Transparent)
                                                            .clickable {
                                                                preferenceManager.putString("anti_revoke_indicator", valKey)
                                                                settingsState["anti_revoke_indicator_dummy"] = !(settingsState["anti_revoke_indicator_dummy"] ?: false)
                                                            }
                                                            .padding(vertical = 8.dp),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = title,
                                                            style = typography.labelSm,
                                                            color = if (isSelected) colors.onPrimary else colors.onSurfaceVariant,
                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        HorizontalDivider(
                                            color = colors.outlineVariant.copy(alpha = 0.5f),
                                            thickness = 0.5.dp
                                        )

                                        // Section 2: Color Deleted Messages Switch
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(
                                                    text = "Color Deleted Message Text",
                                                    style = typography.bodyMd,
                                                    color = colors.onSurface,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Text(
                                                    text = "Apply custom color to deleted message and time",
                                                    style = typography.labelSm,
                                                    color = colors.onSurfaceVariant
                                                )
                                            }
                                            StitchSwitch(
                                                checked = isColorEnabled,
                                                onCheckedChange = { checked ->
                                                    preferenceManager.putBoolean("anti_revoke_color_enabled", checked)
                                                    settingsState["anti_revoke_color_enabled"] = checked
                                                    settingsState["anti_revoke_indicator_dummy"] = !(settingsState["anti_revoke_indicator_dummy"] ?: false)
                                                }
                                            )
                                        }

                                        // Section 3: Color Picker Trigger (only when Color Deleted Messages is ON)
                                        if (isColorEnabled) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(colors.surface)
                                                    .clickable { showColorPickerDialog = true }
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    val parsedCurrentColor = try {
                                                        Color(android.graphics.Color.parseColor(deletedMessageColor))
                                                    } catch (_: Throwable) {
                                                        Color(0xFFEF4444)
                                                    }
                                                    Box(
                                                        modifier = Modifier
                                                            .size(24.dp)
                                                            .clip(CircleShape)
                                                            .background(parsedCurrentColor)
                                                            .border(1.5.dp, colors.outline, CircleShape)
                                                    )
                                                    Text(
                                                        text = "Message Text Color",
                                                        style = typography.bodyMd,
                                                        color = colors.onSurface,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                }
                                                Text(
                                                    text = deletedMessageColor.uppercase(),
                                                    style = typography.labelSm,
                                                    color = colors.primary,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (index < group.items.lastIndex) {
                                HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(100.dp))
    }

    if (showColorPickerDialog) {
        com.waenhancer.ui.components.WaexColorPickerDialog(
            initialColorHex = deletedMessageColor,
            onColorSelected = { selectedHex ->
                deletedMessageColor = selectedHex
                preferenceManager.putString("deleted_message_color", selectedHex)
                settingsState["anti_revoke_indicator_dummy"] = !(settingsState["anti_revoke_indicator_dummy"] ?: false)
            },
            onDismiss = { showColorPickerDialog = false }
        )
    }
}

private data class SettingGroup(
    val title: String,
    val items: List<SettingItem>
)


private data class SettingItem(
    val label: String,
    val sublabel: String,
    val key: String
)

@Preview(showBackground = true)
@Composable
fun GlobalPrivacySettingsScreenPreview() {
    WaexTheme {
        GlobalPrivacySettingsScreen(onOpenModal = {})
    }
}
