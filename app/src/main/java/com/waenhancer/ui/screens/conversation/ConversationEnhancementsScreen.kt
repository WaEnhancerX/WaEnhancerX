package com.waenhancer.ui.screens.conversation

import com.waenhancer.app.R
import com.waenhancer.utils.UiText
import com.waenhancer.utils.EmojiInputPolicy

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
import androidx.compose.ui.unit.dp
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.waenhancer.ui.components.WaexEmojiPickerDialog
import com.waenhancer.ui.components.WaexPreferenceItem

@Composable
fun ConversationEnhancementsScreen(targetEpoch: Int = 0) {
    val navController = LocalWaexNavController.current
    val preferenceManager = LocalWaexPreferenceManager.current
    val isPro = LocalIsPro.current
    val onActivatePro = LocalOnActivatePro.current
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
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
            "message_bomber", "anti_edit_messages", "message_device_source", "preserve_own_revoke", "revokeallmessages", "hide_forwarded_tag",
            "removeforwardlimit", "sticker_confirm_alert", "removeseemore", "stamp_copied_message", "doubletap2like",
            "jump_to_first_message", "unlimited_pinned_chats", "broadcast_tag", "direct_chat_dialer",
            "inline_translation", "copy_status_text", "animation_emojis", "disable_defemojis"
        )
    }

    val stateMap = remember(targetEpoch) {
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
                title = UiText.get(R.string.title_conversation_messages, "Conversation & Messages"),
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
                title = UiText.get(R.string.waex_ui_message_protection_controls, "Message Protection & Controls"),
                subtitle = UiText.get(R.string.waex_ui_preserve_edits_prevent_limits_and_safe, "Preserve edits, prevent limits, and safeguard messages")
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
                        title = UiText.get(R.string.waex_ui_message_bomber_pro, "Message Bomber · Pro"),
                        description = UiText.get(R.string.feat_message_bomber_desc, "Send a short, delayed sequence of messages from inside a WhatsApp chat"),
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
                        title = UiText.get(R.string.feat_anti_edit_messages_title, "Show Edited Message History"),
                        description = UiText.get(R.string.feat_anti_edit_messages_desc, "Show edited message history when clicking \"Edited\" on a message"),
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
                    val mdsHighlighted = navController.highlightTargetKey == "message_device_source"
                    val mdsHighlightBg by animateColorAsState(
                        targetValue = if (mdsHighlighted) colors.primary.copy(alpha = 0.15f) else Color.Transparent,
                        animationSpec = tween(durationMillis = 300),
                        label = "mds_highlight_bg"
                    )
                    WaexSwitchPreference(
                        title = UiText.get(R.string.feat_message_device_source_title, "Show Message Device Source"),
                        description = UiText.get(R.string.feat_message_device_source_desc, "Shows whether a message was sent from the primary device or a linked device"),
                        checked = stateMap["message_device_source"] ?: false,
                        onCheckedChange = { onToggle("message_device_source", it) },
                        icon = WaexIcons.Extension,
                        showDivider = true,
                        modifier = Modifier
                            .background(mdsHighlightBg)
                            .onGloballyPositioned { coordinates ->
                                val y = coordinates.positionInRoot().y - containerY + scrollState.value
                                itemCoordinates["message_device_source"] = y
                            }
                    )
                    WaexSwitchPreference(
                        title = UiText.get(R.string.feat_preserve_own_revoke_title, "Keep My Revoked Messages"),
                        description = UiText.get(R.string.feat_preserve_own_revoke_desc, "Keep your local copy after Delete for Everyone while deleting it for recipients"),
                        checked = stateMap["preserve_own_revoke"] ?: false,
                        onCheckedChange = { onToggle("preserve_own_revoke", it) },
                        icon = WaexIcons.Security,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = UiText.get(R.string.feat_revokeallmessages_title, "Increase limit of \"Delete for everyone\" option"),
                        description = UiText.get(R.string.feat_revokeallmessages_desc, "Increases the limit to 3 days in the option to delete messages for everyone"),
                        checked = stateMap["revokeallmessages"] ?: false,
                        onCheckedChange = { onToggle("revokeallmessages", it) },
                        icon = WaexIcons.Refresh,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = UiText.get(R.string.feat_hide_forwarded_tag_title, "Hide \"Forwarded\" Tag"),
                        description = UiText.get(R.string.feat_hide_forwarded_tag_desc, "Forward messages without the tag \"Forwarded\""),
                        checked = stateMap["hide_forwarded_tag"] ?: false,
                        onCheckedChange = { onToggle("hide_forwarded_tag", it) },
                        icon = WaexIcons.Share,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = UiText.get(R.string.feat_removeforwardlimit_title, "Remove Forward Limit"),
                        description = UiText.get(R.string.feat_removeforwardlimit_desc, "Remove forward limit for 5 chats (normal) and 1 chat (multiple times)"),
                        checked = stateMap["removeforwardlimit"] ?: false,
                        onCheckedChange = { onToggle("removeforwardlimit", it) },
                        icon = WaexIcons.Share,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = UiText.get(R.string.feat_sticker_confirm_alert_title, "Confirmation Before Sending Sticker"),
                        description = UiText.get(R.string.feat_sticker_confirm_alert_desc, "Show a dialog before sending the sticker"),
                        checked = stateMap["sticker_confirm_alert"] ?: false,
                        onCheckedChange = { onToggle("sticker_confirm_alert", it) },
                        icon = WaexIcons.Settings,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = UiText.get(R.string.feat_removeseemore_title, "Remove \"See More\" Button"),
                        description = UiText.get(R.string.waex_ui_disable_see_more_button_and_show_all_l, "Disable \"See More\" button and show all long message"),
                        checked = stateMap["removeseemore"] ?: false,
                        onCheckedChange = { onToggle("removeseemore", it) },
                        icon = WaexIcons.Info,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = UiText.get(R.string.feat_stamp_copied_message_title, "Remove Stamp from Copied Messages"),
                        description = UiText.get(R.string.feat_stamp_copied_message_desc, "Removes name and date when copying more than one message"),
                        checked = stateMap["stamp_copied_message"] ?: false,
                        onCheckedChange = { onToggle("stamp_copied_message", it) },
                        icon = WaexIcons.Folder,
                        showDivider = true
                    )
                    val doubleTapEnabled = stateMap["doubletap2like"] ?: false
                    WaexSwitchPreference(
                        title = UiText.get(R.string.feat_doubletap2like_title, "Enable Double Click to React"),
                        description = UiText.get(R.string.feat_doubletap2like_desc, "Activates the possibility of double-clicking on the message to react it"),
                        checked = doubleTapEnabled,
                        onCheckedChange = { onToggle("doubletap2like", it) },
                        icon = WaexIcons.AutoAwesome,
                        showDivider = true
                    )

                    if (doubleTapEnabled) {
                        var showEmojiPicker by remember { mutableStateOf(false) }
                        var currentEmoji by remember {
                            mutableStateOf(EmojiInputPolicy.singleEmojiOrDefault(preferenceManager.getString("doubletap2like_emoji", "👍")))
                        }

                        WaexPreferenceItem(
                            title = UiText.get(R.string.waex_ui_custom_reaction, "Custom Reaction"),
                            description = UiText.get(R.string.waex_ui_change_the_default_emoji_sent_when_dou, "Change the default emoji sent when double-clicking (%1\$s)", currentEmoji),
                            icon = WaexIcons.Palette,
                            onClick = { showEmojiPicker = true },
                            showDivider = true,
                            trailing = {
                                Surface(
                                    shape = CircleShape,
                                    color = colors.surfaceContainerHighest,
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    Text(
                                        text = currentEmoji,
                                        style = typography.titleMd,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        )

                        if (showEmojiPicker) {
                            WaexEmojiPickerDialog(
                                initialEmoji = currentEmoji,
                                onEmojiSelected = { emoji ->
                                    currentEmoji = emoji
                                    preferenceManager.putString("doubletap2like_emoji", emoji)
                                },
                                onDismiss = { showEmojiPicker = false }
                            )
                        }
                    }

                    WaexSwitchPreference(
                        title = UiText.get(R.string.feat_disable_defemojis_title, "Use Device Default Emojis"),
                        description = UiText.get(R.string.feat_disable_defemojis_desc, "Use system emojis (iOS, Samsung, Google) instead of WhatsApp's built-in emojis"),
                        checked = stateMap["disable_defemojis"] ?: false,
                        onCheckedChange = { onToggle("disable_defemojis", it) },
                        icon = WaexIcons.Mood,
                        showDivider = false
                    )
                }
            }

            // Section 2: Chat Navigation & Shortcuts
            WaexSectionHeader(
                title = UiText.get(R.string.sec_chat_navigation_shortcuts, "Chat Navigation & Shortcuts"),
                subtitle = UiText.get(R.string.waex_ui_fast_navigation_and_conversation_produ, "Fast navigation and conversation productivity tools")
            )

            WaexCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    WaexSwitchPreference(
                        title = UiText.get(R.string.feat_jump_to_first_message_title, "Jump to First Message"),
                        description = UiText.get(R.string.feat_jump_to_first_message_desc, "Add a button to skip the first message in the conversations screen"),
                        checked = stateMap["jump_to_first_message"] ?: false,
                        onCheckedChange = { onToggle("jump_to_first_message", it) },
                        icon = WaexIcons.Play,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = UiText.get(R.string.feat_unlimited_pinned_chats_title, "Disable Pinned Chats Limit"),
                        description = UiText.get(R.string.feat_unlimited_pinned_chats_desc, "Disable limit of 3 pinned chats"),
                        checked = stateMap["unlimited_pinned_chats"] ?: false,
                        onCheckedChange = { onToggle("unlimited_pinned_chats", it) },
                        icon = WaexIcons.Lock,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = UiText.get(R.string.feat_broadcast_tag_title, "Show chat broadcast icon"),
                        description = UiText.get(R.string.feat_broadcast_tag_desc, "Shows an icon if the contact sent a message via broadcast"),
                        checked = stateMap["broadcast_tag"] ?: false,
                        onCheckedChange = { onToggle("broadcast_tag", it) },
                        icon = WaexIcons.Notifications,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = UiText.get(R.string.feat_direct_chat_dialer_title, "New Chat"),
                        description = UiText.get(R.string.feat_direct_chat_dialer_desc, "Message numbers directly without saving them to contacts"),
                        checked = stateMap["direct_chat_dialer"] ?: false,
                        onCheckedChange = { onToggle("direct_chat_dialer", it) },
                        icon = WaexIcons.Contacts,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = UiText.get(R.string.feat_inline_translation_title, "Enable Google Translate"),
                        description = UiText.get(R.string.feat_inline_translation_desc, "Replaces Whatsapp's native translator with Google Translate"),
                        checked = stateMap["inline_translation"] ?: false,
                        onCheckedChange = { onToggle("inline_translation", it) },
                        icon = WaexIcons.Extension,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = UiText.get(R.string.feat_copy_status_text_title, "Enable Copy Status"),
                        description = UiText.get(R.string.feat_copy_status_text_desc, "Activates the possibility of copying the description and caption of statuses by holding on them"),
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
