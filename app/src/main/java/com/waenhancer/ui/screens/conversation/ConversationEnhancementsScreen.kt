package com.waenhancer.ui.screens.conversation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.waenhancer.ui.components.WaexCard
import com.waenhancer.ui.components.WaexSectionHeader
import com.waenhancer.ui.components.WaexSwitchPreference
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalIsPro
import com.waenhancer.ui.navigation.LocalOnActivatePro
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import kotlinx.coroutines.delay

@Composable
fun ConversationEnhancementsScreen() {
    val navController = LocalWaexNavController.current
    val preferenceManager = LocalWaexPreferenceManager.current
    val isPro = LocalIsPro.current
    val onActivatePro = LocalOnActivatePro.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing

    val scrollState = rememberScrollState()
    val itemCoordinates = remember { mutableStateMapOf<String, Float>() }
    var containerY by remember { mutableStateOf(0f) }

    LaunchedEffect(navController.scrollToTargetKey, itemCoordinates.keys.toList()) {
        val target = navController.scrollToTargetKey
        if (target != null && itemCoordinates.containsKey(target)) {
            val yOffset = itemCoordinates[target] ?: 0f
            scrollState.animateScrollTo(yOffset.toInt())
            navController.scrollToTargetKey = null
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

    val convKeys = remember {
        listOf(
            "message_bomber", "anti_edit_messages", "preserve_delete_for_me", "revokeallmessages", "hide_forwarded_tag",
            "removeforwardlimit", "sticker_confirm_alert", "removeseemore", "stamp_copied_message", "doubletap2like",
            "jump_to_first_message", "unlimited_pinned_chats", "broadcast_tag", "direct_chat_dialer",
            "inline_translation", "copy_status_text", "animation_emojis", "disable_defemojis"
        )
    }

    val stateMap = remember {
        mutableStateMapOf<String, Boolean>().apply {
            convKeys.forEach { key ->
                put(key, preferenceManager.getBoolean(key, false))
            }
        }
    }

    val onToggle: (String, Boolean) -> Unit = { key, value ->
        stateMap[key] = value
        preferenceManager.putBoolean(key, value)
    }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = "Conversation & Messages",
                onBackClick = { navController.popBack() }
            )
        },
        containerColor = colors.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .onGloballyPositioned { containerCoordinates ->
                    containerY = containerCoordinates.positionInRoot().y
                }
                .padding(spacing.pageMargin),
            verticalArrangement = Arrangement.spacedBy(spacing.stackLg)
        ) {
            // Section 1: Message Protection & Controls
            WaexSectionHeader(
                title = "Message Protection & Controls",
                subtitle = "Preserve edits, prevent limits, and safeguard messages"
            )

            WaexCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    val bomberHighlighted = navController.highlightTargetKey == "message_bomber"
                    val bomberHighlightBg by animateColorAsState(
                        targetValue = if (bomberHighlighted) colors.primary.copy(alpha = 0.15f) else Color.Transparent,
                        animationSpec = tween(durationMillis = 300),
                        label = "bomber_highlight_bg"
                    )
                    WaexSwitchPreference(
                        title = "Message Bomber · Pro",
                        description = "Send a short, delayed sequence of messages from inside a WhatsApp chat",
                        checked = isPro && (stateMap["message_bomber"] ?: false),
                        onCheckedChange = { enabled ->
                            if (isPro) onToggle("message_bomber", enabled) else onActivatePro()
                        },
                        icon = WaexIcons.AutoAwesome,
                        showDivider = true,
                        modifier = Modifier
                            .background(bomberHighlightBg)
                            .onGloballyPositioned { coordinates ->
                                val y = coordinates.positionInRoot().y - containerY + scrollState.value
                                itemCoordinates["message_bomber"] = y
                            }
                    )
                    val aeHighlighted = navController.highlightTargetKey == "anti_edit_messages"
                    val aeHighlightBg by animateColorAsState(
                        targetValue = if (aeHighlighted) colors.primary.copy(alpha = 0.15f) else Color.Transparent,
                        animationSpec = tween(durationMillis = 300),
                        label = "ae_highlight_bg"
                    )
                    WaexSwitchPreference(
                        title = "Show Edited Message History",
                        description = "Show edited message history when clicking \"Edited\" on a message",
                        checked = stateMap["anti_edit_messages"] ?: false,
                        onCheckedChange = { onToggle("anti_edit_messages", it) },
                        icon = WaexIcons.Lock,
                        showDivider = true,
                        modifier = Modifier
                            .background(aeHighlightBg)
                            .onGloballyPositioned { coordinates ->
                                val y = coordinates.positionInRoot().y - containerY + scrollState.value
                                itemCoordinates["anti_edit_messages"] = y
                            }
                    )
                    WaexSwitchPreference(
                        title = "Delete (for me)",
                        description = "Retain messages locally when Delete for Me is pressed",
                        checked = stateMap["preserve_delete_for_me"] ?: false,
                        onCheckedChange = { onToggle("preserve_delete_for_me", it) },
                        icon = WaexIcons.Security,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = "Increase limit of \"Delete for everyone\" option",
                        description = "Increases the limit to 3 days in the option to delete messages for everyone",
                        checked = stateMap["revokeallmessages"] ?: false,
                        onCheckedChange = { onToggle("revokeallmessages", it) },
                        icon = WaexIcons.Refresh,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = "Hide \"Forwarded\" Tag",
                        description = "Forward messages without the tag \"Forwarded\"",
                        checked = stateMap["hide_forwarded_tag"] ?: false,
                        onCheckedChange = { onToggle("hide_forwarded_tag", it) },
                        icon = WaexIcons.Share,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = "Remove Forward Limit",
                        description = "Remove forward limit for 5 chats (normal) and 1 chat (multiple times)",
                        checked = stateMap["removeforwardlimit"] ?: false,
                        onCheckedChange = { onToggle("removeforwardlimit", it) },
                        icon = WaexIcons.Share,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = "Confirmation Before Sending Sticker",
                        description = "Show a dialog before sending the sticker",
                        checked = stateMap["sticker_confirm_alert"] ?: false,
                        onCheckedChange = { onToggle("sticker_confirm_alert", it) },
                        icon = WaexIcons.Settings,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = "Remove \"See More\" Button",
                        description = "Disable \"See More\" button and show all long message",
                        checked = stateMap["removeseemore"] ?: false,
                        onCheckedChange = { onToggle("removeseemore", it) },
                        icon = WaexIcons.Info,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = "Remove Stamp from Copied Messages",
                        description = "Removes name and date when copying more than one message",
                        checked = stateMap["stamp_copied_message"] ?: false,
                        onCheckedChange = { onToggle("stamp_copied_message", it) },
                        icon = WaexIcons.Folder,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = "Enable Double Click to React",
                        description = "Activates the possibility of double-clicking on the message to react it",
                        checked = stateMap["doubletap2like"] ?: false,
                        onCheckedChange = { onToggle("doubletap2like", it) },
                        icon = WaexIcons.AutoAwesome,
                        showDivider = false
                    )
                }
            }

            // Section 2: Chat Navigation & Shortcuts
            WaexSectionHeader(
                title = "Chat Navigation & Shortcuts",
                subtitle = "Fast navigation and conversation productivity tools"
            )

            WaexCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    WaexSwitchPreference(
                        title = "Jump to First Message",
                        description = "Add a button to skip the first message in the conversations screen",
                        checked = stateMap["jump_to_first_message"] ?: false,
                        onCheckedChange = { onToggle("jump_to_first_message", it) },
                        icon = WaexIcons.Play,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = "Disable Pinned Chats Limit",
                        description = "Disable limit of 3 pinned chats",
                        checked = stateMap["unlimited_pinned_chats"] ?: false,
                        onCheckedChange = { onToggle("unlimited_pinned_chats", it) },
                        icon = WaexIcons.Lock,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = "Show chat broadcast icon",
                        description = "Shows an icon if the contact sent a message via broadcast",
                        checked = stateMap["broadcast_tag"] ?: false,
                        onCheckedChange = { onToggle("broadcast_tag", it) },
                        icon = WaexIcons.Notifications,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = "New Chat",
                        description = "Message numbers directly without saving them to contacts",
                        checked = stateMap["direct_chat_dialer"] ?: false,
                        onCheckedChange = { onToggle("direct_chat_dialer", it) },
                        icon = WaexIcons.Contacts,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = "Enable Google Translate",
                        description = "Replaces Whatsapp's native translator with Google Translate",
                        checked = stateMap["inline_translation"] ?: false,
                        onCheckedChange = { onToggle("inline_translation", it) },
                        icon = WaexIcons.Extension,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = "Enable Copy Status",
                        description = "Activates the possibility of copying the description and caption of statuses by holding on them",
                        checked = stateMap["copy_status_text"] ?: false,
                        onCheckedChange = { onToggle("copy_status_text", it) },
                        icon = WaexIcons.Folder,
                        showDivider = false
                    )
                }
            }
        }
    }
}
