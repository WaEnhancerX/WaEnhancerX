package com.waenhancer.ui.screens.privacy

import com.waenhancer.ui.components.WaexProChip

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
import androidx.compose.ui.draw.alpha
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
            "always_online", "online_status_indicator", "freeze_last_seen", "freezelastseen",
            "ghostmode_enabled", "ghostmode", "ghostmode_t", "ghostmode_r", "typing_privacy",
            "hide_read_receipts", "hideread_group", "blueonreply", "seentick", "hide_delivery_receipts", "hide_seen_receipts", "hideonceseen",
            "stealth_status_view", "autonext_status", "toast_viewed_status",
            "anti_view_once", "downloadviewonce",
            "anti_revoke", "antirevokestatus", "antidisappearing", "toastdeleted", "toast_viewed_message",
            "locked_chats_enhancer", "typearchive", "dnd_mode", "hide_chats", "custom_privacy",
            "anti_edit_messages", "preserve_delete_for_me", "revokeallmessages", "hide_forwarded_tag", "removeforwardlimit",
            "sticker_confirm_alert", "removeseemore", "stamp_copied_message", "doubletap2like",
            "jump_to_first_message", "unlimited_pinned_chats", "broadcast_tag", "direct_chat_dialer", "inline_translation", "copy_status_text",
            "call_type_controller", "custom_filter_groups", "group_admin_tools", "status_text_composer",
            "show_dndmode", "restartbutton", "open_wae"
        )
    }

    var waexMenuStyle by remember {
        mutableStateOf(preferenceManager.getString("waex_menu_style", "grouped") ?: "grouped")
    }

    var waexSettingsPlacement by remember {
        mutableStateOf(preferenceManager.getString("open_waex", "home_menu") ?: "home_menu")
    }

    val settingsState = remember {
        mutableStateMapOf<String, Boolean>().apply {
            privacyKeys.forEach { key ->
                var boolVal = preferenceManager.getBoolean(key, false)
                if (!boolVal && key == "typearchive") {
                    val strVal = preferenceManager.getString("typearchive", "0")
                    boolVal = (strVal != null && strVal != "0" && !strVal.equals("false", ignoreCase = true))
                }
                put(key, boolVal)
            }
        }
    }

    val updatePreference: (String, Boolean) -> Unit = { key, value ->
        settingsState[key] = value
        preferenceManager.putBoolean(key, value)
        if (key == "typearchive") {
            preferenceManager.putString("typearchive", if (value) "1" else "0")
        } else if (key == "locked_chats_enhancer") {
            preferenceManager.putBoolean("lockedchats_enhancer", value)
        }
    }

    var showColorPickerDialog by remember { mutableStateOf(false) }
    var deletedMessageColor by remember {
        mutableStateOf(preferenceManager.getString("deleted_message_color", "#EF4444"))
    }

    val privacyGroups = listOf(
        SettingGroup(
            "Presence & Online Visibility",
            listOf(
                SettingItem("Always Online", "Show you online even if you minimize WhatsApp (Doesn't work if you remove it from recents)", "always_online"),
                SettingItem("Freeze Last Seen", "Your last seen time will be frozen", "freezelastseen"),
                SettingItem("Hide Typing", "Users cannot see that you are typing", "ghostmode_t"),
                SettingItem("Hide Recording Audio", "Users cannot see that you are recording audio", "ghostmode_r")
            )
        ),
        SettingGroup(
            "Ticks & Seen Receipts",
            listOf(
                SettingItem("Hide Blue Ticks", "View messages within conversation screen without users knowing", "hide_read_receipts"),
                SettingItem("Hide Blue Tick in Groups", "View messages in groups screen without users knowing", "hideread_group"),
                SettingItem("Send Blue Ticks upon Reply", "Sends blue ticks after replying to a message\n(NOTE: Requires \"Hide Blue Ticks\" active)", "blueonreply"),
                SettingItem("Show Button to send blue tick", "Show Button to send blue tick (mark as read/viewed)\n(NOTE: Requires \"Hide Blue Ticks\" active)", "seentick"),
                SettingItem("Hide Delivered", "Users will not know that their messages have been delivered to you\n(CAUTION: May cause delay in receiving messages)", "hide_delivery_receipts"),
                SettingItem("Hide audio seen", "Hides the sending of audio reading when listening to it", "hide_seen_receipts"),
                SettingItem("Hide View Once Seen", "Hide that view one media has been seen", "hideonceseen")
            )
        ),
        SettingGroup(
            "Status Privacy",
            listOf(
                SettingItem("Hide Status View", "View statuses without users knowing", "stealth_status_view"),
                SettingItem("Disable Auto Skip Status", "Prevents the current status from advancing automatically to the next one", "autonext_status"),
                SettingItem("Show toast on viewed your status", "Shows a toast if someone views your status", "toast_viewed_status")
            )
        ),
        SettingGroup(
            "View Once Protection",
            listOf(
                SettingItem("Unlimited View Once", "Allows you to open view once media multiple times", "anti_view_once"),
                SettingItem("Download View Once", "Show button to download view once media", "downloadviewonce")
            )
        ),
        SettingGroup(
            "Anti-Revoke & Deletion Defense",
            listOf(
                SettingItem("Anti-Revoke Messages", "Messages will not be deleted for you", "anti_revoke"),
                SettingItem("Anti-Delete Status", "View statuses that users have since deleted", "antirevokestatus"),
                SettingItem("Anti Disappearing Messages", "Temporary messages will not be deleted for you", "antidisappearing"),
                SettingItem("Show toast notification on delete message", "Show toast notification if any contact deletes any message", "toastdeleted"),
                SettingItem("Show toast on viewed your message", "Shows a toast if someone views your message", "toast_viewed_message")
            )
        ),
        SettingGroup(
            "Security & Vault",
            listOf(
                SettingItem("Enhanced Locked Chats", "Improve locked chats by hiding notifications and contacts from the contact list", "locked_chats_enhancer"),
                SettingItem("Hide Archived Chats", "Hide archived chats, to access click 5 times or hold on the \"WhatsApp\" title", "typearchive"),
                SettingItem("DND Mode", "When Do Not Disturb mode is on, you will be unable to send or receive messages", "dnd_mode"),
                SettingItem("Hide Locked Chats", "Hide locked chats, view them by holding WhatsApp title on home screen", "hide_chats")
            )
        ),
        SettingGroup(
            "Home Menu Shortcuts",
            listOf(
                SettingItem("Show Ghost Mode Button", "Show a Ghost Mode button on the home screen toolbar", "ghostmode"),
                SettingItem("Show Freeze Last Seen Button", "Show button for Freezing Last Seen on home screen toolbar", "freezelastseen"),
                SettingItem("Show DND Button", "Show button for DND Mode on home screen toolbar", "show_dndmode"),
                SettingItem("Enable Restart Button", "Add button in Home Screen to Restart App", "restartbutton"),
                SettingItem("Enable Wa Enhancer Button", "Add button in Home Screen to open Wa Enhancer", "open_wae")
            )
        )
    )

    val conversationGroups = listOf(
        SettingGroup(
            "Message Protection & Controls",
            listOf(
                SettingItem("Show Edited Message History", "Show edited message history when clicking \"Edited\" on a message", "anti_edit_messages"),
                SettingItem("Delete (for me)", "Retain messages locally when Delete for Me is selected", "preserve_delete_for_me"),
                SettingItem("Increase limit of \"Delete for everyone\" option", "Increases the limit to 3 days in the option to delete messages for everyone", "revokeallmessages"),
                SettingItem("Hide \"Forwarded\" Tag", "Forward messages without the tag \"Forwarded\"", "hide_forwarded_tag"),
                SettingItem("Remove Forward Limit", "Remove forward limit for 5 chats (normal) and 1 chat (multiple times)", "removeforwardlimit"),
                SettingItem("Confirmation Before Sending Sticker", "Show a dialog before sending the sticker", "sticker_confirm_alert"),
                SettingItem("Remove \"See More\" Button", "Disable \"See More\" button and show all long message", "removeseemore"),
                SettingItem("Remove Stamp from Copied Messages", "Removes name and date when copying more than one message", "stamp_copied_message"),
                SettingItem("Enable Double Click to React", "Activates the possibility of double-clicking on the message to react it", "doubletap2like")
            )
        ),
        SettingGroup(
            "Chat Navigation & Shortcuts",
            listOf(
                SettingItem("Jump to First Message", "Add a button to skip the first message in the conversations screen", "jump_to_first_message"),
                SettingItem("Disable Pinned Chats Limit", "Disable limit of 3 pinned chats", "unlimited_pinned_chats"),
                SettingItem("Show chat broadcast icon", "Shows an icon if the contact sent a message via broadcast", "broadcast_tag"),
                SettingItem("New Chat", "Message someone directly without saving their contact info", "direct_chat_dialer"),
                SettingItem("Enable Google Translate", "Replaces Whatsapp's native translator with Google Translate", "inline_translation"),
                SettingItem("Enable Copy Status", "Activates the possibility of copying the description and caption of statuses by holding on them", "copy_status_text")
            )
        ),
        SettingGroup(
            "Chat & Group Utilities",
            listOf(
                SettingItem("Enable selection of call type", "Show an option to select whether you want a call via phone or WhatsApp", "call_type_controller"),
                SettingItem("Show Admin Group Icon", "Show an admin icon next to the name of the user who is the group admin", "group_admin_tools"),
                SettingItem("Custom colors for text status", "Press and hold on the color selector in the status to customize it", "status_text_composer")
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

        // Hubs & Advanced Modules (only on Privacy tab)
        if (selectedTab == "privacy") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.pageMargin)
            ) {
                Text(
                    text = "ADVANCED HUBS & VAULTS",
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
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // 1. Per Contact Rules Tile
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { navController.navigateTo(com.waenhancer.ui.navigation.Screen.PerContactPrivacyList) }
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
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
                                    color = colors.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                            Icon(
                                imageVector = WaexIcons.ChevronRight,
                                contentDescription = null,
                                tint = colors.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        HorizontalDivider(
                            color = colors.outlineVariant.copy(alpha = 0.5f),
                            thickness = 1.dp,
                            modifier = Modifier.padding(horizontal = 18.dp)
                        )

                        // 2. Deleted Messages Vault Tile
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { navController.navigateTo(com.waenhancer.ui.navigation.Screen.DeletedMessages) }
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
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
                                    color = colors.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                            Icon(
                                imageVector = WaexIcons.ChevronRight,
                                contentDescription = null,
                                tint = colors.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        HorizontalDivider(
                            color = colors.outlineVariant.copy(alpha = 0.5f),
                            thickness = 1.dp,
                            modifier = Modifier.padding(horizontal = 18.dp)
                        )

                        // 3. Calls & Recording Hub Tile
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { navController.navigateTo(com.waenhancer.ui.navigation.Screen.CallRecordingSettings) }
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "Calls & Recording Hub",
                                    style = typography.bodyLg,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onSurface
                                )
                                Text(
                                    text = "Call blocker, caller privacy, recording formats & logs",
                                    style = typography.bodyMd,
                                    color = colors.onSurfaceVariant,
                                    fontSize = 12.sp
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
                    val isPro = com.waenhancer.ui.navigation.LocalIsPro.current
                    val onActivatePro = com.waenhancer.ui.navigation.LocalOnActivatePro.current

                    Column {
                        group.items.forEachIndexed { index, item ->
                            val isBlueTickDependent = (item.key == "blueonreply" || item.key == "seentick")
                            val isHideReadEnabled = settingsState["hide_read_receipts"] ?: false
                            val isDependencyDisabled = isBlueTickDependent && !isHideReadEnabled

                            val isLocked = item.isPro && !isPro
                            val isItemDisabled = isLocked || isDependencyDisabled
                            val isHighlighted = navController.highlightTargetKey == item.key
                            val highlightBgColor by animateColorAsState(
                                targetValue = if (isHighlighted) colors.primary.copy(alpha = 0.15f) else Color.Transparent,
                                animationSpec = tween(durationMillis = 300),
                                label = "highlight_bg"
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(highlightBgColor)
                                    .onGloballyPositioned { coordinates ->
                                        val y = coordinates.positionInRoot().y - containerY + scrollState.value
                                        itemCoordinates[item.key] = y
                                    }
                                    .clickable {
                                        if (isLocked) {
                                            onActivatePro()
                                        } else if (!isDependencyDisabled) {
                                            val currentVal = settingsState[item.key] ?: false
                                            updatePreference(item.key, !currentVal)
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp)
                                        .then(if (isItemDisabled) Modifier.alpha(0.45f) else Modifier),
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
                                            text = when {
                                                isLocked -> "${item.sublabel} • Requires Pro"
                                                isDependencyDisabled -> "${item.sublabel}\n(Disabled: Turn on \"Hide Blue Ticks\" first)"
                                                else -> item.sublabel
                                            },
                                            style = typography.bodyMd,
                                            color = if (isDependencyDisabled) colors.error.copy(alpha = 0.8f) else colors.onSurfaceVariant,
                                            fontSize = 12.sp
                                        )
                                    }
                                    StitchSwitch(
                                        checked = if (isItemDisabled && isLocked) false else (settingsState[item.key] ?: false),
                                        onCheckedChange = if (isItemDisabled) null else { isChecked -> updatePreference(item.key, isChecked) },
                                        enabled = !isItemDisabled
                                    )
                                }
                                if (item.isPro) {
                                    WaexProChip(
                                        isUnlocked = isPro,
                                        modifier = Modifier.align(Alignment.TopEnd)
                                    )
                                }
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

                            // Options when WA Enhancer Settings shortcut is enabled
                            if (item.key == "open_wae" && (settingsState["open_wae"] == true)) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = colors.surfaceDim.copy(alpha = 0.6f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "WAENHANCERX SETTINGS PLACEMENT",
                                            style = typography.labelSm,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.primary
                                        )
                                        Text(
                                            text = "Choose where to show the WAEX settings entry point",
                                            style = typography.bodyMd,
                                            color = colors.onSurfaceVariant,
                                            fontSize = 11.sp
                                        )
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(colors.surface)
                                                .padding(4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            listOf(
                                                "home_menu" to "Home Menu",
                                                "wa_settings" to "WhatsApp Settings"
                                            ).forEach { (value, label) ->
                                                val isSelected = waexSettingsPlacement == value
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(if (isSelected) colors.primary else Color.Transparent)
                                                        .clickable {
                                                            waexSettingsPlacement = value
                                                            preferenceManager.putString("open_waex", value)
                                                        }
                                                        .padding(vertical = 8.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = label,
                                                        style = typography.labelSm,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (isSelected) Color.White else colors.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Show Menu Display Options for Home Menu Shortcuts
                            if (item.key == "open_wae") {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = colors.surfaceDim.copy(alpha = 0.6f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, top = if (settingsState["open_wae"] == true) 0.dp else 0.dp, bottom = 12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "MENU DISPLAY OPTIONS",
                                            style = typography.labelSm,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.primary
                                        )
                                        Text(
                                            text = "How WAEX items appear in WhatsApp's home menu",
                                            style = typography.bodyMd,
                                            color = colors.onSurfaceVariant,
                                            fontSize = 11.sp
                                        )
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(colors.surface)
                                                .padding(4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            listOf(
                                                "grouped"  to "Grouped",
                                                "separate" to "Separate",
                                                "icons"    to "Icons"
                                            ).forEach { (value, label) ->
                                                val isSelected = waexMenuStyle == value
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(if (isSelected) colors.primary else Color.Transparent)
                                                        .clickable {
                                                            waexMenuStyle = value
                                                            preferenceManager.putString("waex_menu_style", value)
                                                        }
                                                        .padding(vertical = 8.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = label,
                                                        style = typography.labelSm,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (isSelected) Color.White else colors.onSurfaceVariant
                                                    )
                                                }
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
    val key: String,
    val isPro: Boolean = false
)

@Preview(showBackground = true)
@Composable
fun GlobalPrivacySettingsScreenPreview() {
    WaexTheme {
        GlobalPrivacySettingsScreen(onOpenModal = {})
    }
}
