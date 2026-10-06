package com.waenhancer.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.components.WaexSearchBar
import com.waenhancer.ui.components.WaexEmptyState
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController

data class SearchablePreference(
    val key: String,
    val title: String,
    val description: String,
    val section: String,
    val tabIndex: Int,
    val subTabId: String?,
    val tabLabel: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen() {
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val navController = LocalWaexNavController.current

    var query by remember { mutableStateOf("") }

    // Registry of all 50+ available preference items
    val preferenceRegistry = remember {
        listOf(
            // --- PRIVACY TAB (Tab 1), Sub-tab "privacy" ---
            SearchablePreference("always_online", "Always Online", "Show you online even if you minimize WhatsApp (Doesn't work if you remove it from recents)", "Presence & Online Visibility", 1, "privacy", "Privacy"),
            SearchablePreference("freezelastseen", "Freeze Last Seen", "Your last seen time will be frozen", "Presence & Online Visibility", 1, "privacy", "Privacy"),
            SearchablePreference("ghostmode_t", "Hide Typing", "Users cannot see that you are typing", "Presence & Online Visibility", 1, "privacy", "Privacy"),
            SearchablePreference("ghostmode_r", "Hide Recording Audio", "Users cannot see that you are recording audio", "Presence & Online Visibility", 1, "privacy", "Privacy"),
            SearchablePreference("stealth_status_view", "Hide Status View", "View statuses without users knowing", "Presence & Online Visibility", 1, "privacy", "Privacy"),
            SearchablePreference("hide_read_receipts", "Hide Blue Ticks", "View messages within conversation screen without users knowing", "Message & Delivery Receipts", 1, "privacy", "Privacy"),
            SearchablePreference("hideread_group", "Hide Blue Tick in Groups", "View messages in groups screen without users knowing", "Message & Delivery Receipts", 1, "privacy", "Privacy"),
            SearchablePreference("blueonreply", "Send Blue Ticks upon Reply", "Sends blue ticks after replying to a message", "Message & Delivery Receipts", 1, "privacy", "Privacy"),
            SearchablePreference("seentick", "Show Button to send blue tick", "Show Button to send blue tick (mark as read/viewed)", "Message & Delivery Receipts", 1, "privacy", "Privacy"),
            SearchablePreference("hide_delivery_receipts", "Hide Delivered", "Users will not know that their messages have been delivered to you", "Message & Delivery Receipts", 1, "privacy", "Privacy"),
            SearchablePreference("hide_seen_receipts", "Hide audio seen", "Hides the sending of audio reading when listening to it", "Message & Delivery Receipts", 1, "privacy", "Privacy"),
            SearchablePreference("hideonceseen", "Hide View Once Seen", "Hide that view one media has been seen", "Message & Delivery Receipts", 1, "privacy", "Privacy"),
            SearchablePreference("hide_forwarded_tag", "Hide \"Forwarded\" Tag", "Forward messages without the tag \"Forwarded\"", "Message & Delivery Receipts", 1, "privacy", "Privacy"),
            SearchablePreference("anti_revoke", "Anti-Revoke Messages", "Messages will not be deleted for you", "Message Protection & Security", 1, "privacy", "Privacy"),
            SearchablePreference("antirevokestatus", "Anti-Delete Status", "View statuses that users have since deleted", "Message Protection & Security", 1, "privacy", "Privacy"),
            SearchablePreference("anti_view_once", "Unlimited View Once", "Allows you to open view once media multiple times", "Message Protection & Security", 1, "privacy", "Privacy"),
            SearchablePreference("locked_chats_enhancer", "Enhanced Locked Chats", "Improve locked chats by hiding notifications and contacts from the contact list", "Message Protection & Security", 1, "privacy", "Privacy"),
            SearchablePreference("typearchive", "Hide Archived Chats", "Hide archived chats, to access click 5 times or hold on the \"WhatsApp\" title", "Message Protection & Security", 1, "privacy", "Privacy"),
            SearchablePreference("dnd_mode", "DND Mode", "When Do Not Disturb mode is on, you will be unable to send or receive messages", "Message Protection & Security", 1, "privacy", "Privacy"),
            SearchablePreference("hide_chats", "Hide Locked Chats", "Hide locked chats, view them by holding WhatsApp title on home screen", "Message Protection & Security", 1, "privacy", "Privacy"),
            SearchablePreference("custom_privacy", "Custom Privacy per contact", "Activate the custom privacy button on the contact information screen", "Advanced Rules & Filtering", 1, "privacy", "Privacy"),
            SearchablePreference("call_privacy", "Block Calls", "Block WhatsApp calls based on privacy type", "Advanced Rules & Filtering", 1, "privacy", "Privacy"),
            SearchablePreference("autonext_status", "Disable Auto Skip Status", "Prevents the current status from advancing automatically to the next one", "Status Privacy", 1, "privacy", "Privacy"),
            SearchablePreference("toast_viewed_status", "Show toast on viewed your status", "Shows a toast if someone views your status", "Status Privacy", 1, "privacy", "Privacy"),
            SearchablePreference("antidisappearing", "Anti Disappearing Messages", "Temporary messages will not be deleted for you", "Anti-Revoke & Deletion Defense", 1, "privacy", "Privacy"),
            SearchablePreference("toastdeleted", "Show toast notification on delete message", "Show toast notification if any contact deletes any message", "Anti-Revoke & Deletion Defense", 1, "privacy", "Privacy"),
            SearchablePreference("toast_viewed_message", "Show toast on viewed your message", "Shows a toast if someone views your message", "Anti-Revoke & Deletion Defense", 1, "privacy", "Privacy"),
            SearchablePreference("ghostmode", "Show Ghost Mode Button", "Show a Ghost Mode button on the home screen toolbar", "Home Menu Shortcuts", 1, "privacy", "Privacy"),
            SearchablePreference("show_dndmode", "Show DND Button", "Show button for DND Mode on home screen toolbar", "Home Menu Shortcuts", 1, "privacy", "Privacy"),
            SearchablePreference("restartbutton", "Enable Restart Button", "Add button in Home Screen to Restart App", "Home Menu Shortcuts", 1, "privacy", "Privacy"),
            SearchablePreference("open_wae", "Enable Wa Enhancer Button", "Add button in Home Screen or WhatsApp Settings to open Wa Enhancer", "Home Menu Shortcuts", 1, "privacy", "Privacy"),

            // --- PRIVACY TAB (Tab 1), Sub-tab "conversation" ---
            SearchablePreference("message_bomber", "Message Bomber", "Send a short, delayed sequence of messages from inside a WhatsApp chat", "Message Controls", 1, "conversation", "Conversation"),
            SearchablePreference("anti_edit_messages", "Show Edited Message History", "Show edited message history when clicking \"Edited\" on a message", "Message Controls", 1, "conversation", "Conversation"),
            SearchablePreference("preserve_delete_for_me", "Delete (for me)", "Retain messages locally when Delete for Me is selected", "Message Controls", 1, "conversation", "Conversation"),
            SearchablePreference("revokeallmessages", "Increase limit of \"Delete for everyone\" option", "Increases the limit to 3 days in the option to delete messages for everyone", "Message Controls", 1, "conversation", "Conversation"),
            SearchablePreference("removeforwardlimit", "Remove Forward Limit", "Remove forward limit for 5 chats (normal) and 1 chat (multiple times)", "Message Controls", 1, "conversation", "Conversation"),
            SearchablePreference("sticker_confirm_alert", "Confirmation Before Sending Sticker", "Show a dialog before sending the sticker", "Message Controls", 1, "conversation", "Conversation"),
            SearchablePreference("removeseemore", "Remove \"See More\" Button", "Disable \"See More\" button and show all long message", "Message Controls", 1, "conversation", "Conversation"),
            SearchablePreference("stamp_copied_message", "Remove Stamp from Copied Messages", "Removes name and date when copying more than one message", "Message Controls", 1, "conversation", "Conversation"),
            SearchablePreference("doubletap2like", "Enable Double Click to React", "Activates the possibility of double-clicking on the message to react it", "Message Controls", 1, "conversation", "Conversation"),
            SearchablePreference("jump_to_first_message", "Jump to First Message", "Add a button to skip the first message in the conversations screen", "Message Controls", 1, "conversation", "Conversation"),
            SearchablePreference("copy_status_text", "Enable Copy Status", "Activates the possibility of copying the description and caption of statuses by holding on them", "Message Controls", 1, "conversation", "Conversation"),
            SearchablePreference("inline_translation", "Enable Google Translate", "Replaces Whatsapp's native translator with Google Translate", "Message Controls", 1, "conversation", "Conversation"),
            SearchablePreference("direct_chat_dialer", "New Chat", "Message numbers directly without saving them to contacts", "Chat & Group Utilities", 1, "conversation", "Conversation"),
            SearchablePreference("unlimited_pinned_chats", "Disable Pinned Chats Limit", "Disable limit of 3 pinned chats", "Chat & Group Utilities", 1, "conversation", "Conversation"),
            SearchablePreference("broadcast_tag", "Show chat broadcast icon", "Shows an icon if the contact sent a message via broadcast", "Chat & Group Utilities", 1, "conversation", "Conversation"),
            SearchablePreference("group_admin_tools", "Show Admin Group Icon", "Show an admin icon next to the name of the user who is the group admin", "Chat & Group Utilities", 1, "conversation", "Conversation"),
            SearchablePreference("call_type_controller", "Enable selection of call type", "Show an option to select whether you want a call via phone or WhatsApp", "Chat & Group Utilities", 1, "conversation", "Conversation"),
            SearchablePreference("status_text_composer", "Custom colors for text status", "Press and hold on the color selector in the status to customize it", "Chat & Group Utilities", 1, "conversation", "Conversation"),

            // --- MEDIA TAB (Tab 2) ---
            SearchablePreference("status_downloader", "Download and Share Status", "Shows two buttons to share and download status", "Media Quality & Status", 2, null, "Media"),
            SearchablePreference("media_upload_quality", "HD Quality Images & Videos", "Send images and videos in HD quality by default", "Media Quality & Status", 2, null, "Media"),
            SearchablePreference("send_audio_as_voice_status", "Audio to Voice Status", "Pick a local audio file and publish it as a voice status", "Media Quality & Status", 2, null, "Media"),
            SearchablePreference("status_video_splitter", "Status Video Splitter", "Split long videos into 30, 60, or 90 second status clips", "Media Quality & Status", 2, null, "Media"),
            SearchablePreference("file_size_spoofer", "File Size Spoofer", "Set the file size displayed to the recipient from WhatsApp's send preview", "Media Quality & Status", 2, null, "Media"),
            SearchablePreference("call_recording", "Call Recording", "Record incoming and outgoing calls (Voice & Video) as audio", "Media Utility & Downloader", 2, null, "Media"),
            SearchablePreference("download_profile", "Download Profile Photo", "Download full-resolution profile pictures directly", "Media Utility & Downloader", 2, null, "Media"),
            SearchablePreference("download_view_once", "Download View Once", "Show button to download view once media", "Media Utility & Downloader", 2, null, "Media"),
            SearchablePreference("media_preview", "Enable Media Preview", "Add a button to preview media in a temporary file", "Media Utility & Downloader", 2, null, "Media"),

            // --- AUTOMATION TAB (Tab 3), Sub-tab "automation" ---
            SearchablePreference("always_typing", "Hide Typing", "Users cannot see that you are typing", "Automation", 3, "automation", "Automation"),
            SearchablePreference("tasker_integration", "Enable Tasker Automation", "Enables using intents to receive and send messages in Tasker", "Automation", 3, "automation", "Automation"),

            // --- AUTOMATION TAB (Tab 3), Sub-tab "ai" ---
            SearchablePreference("voice_transcription", "Audio Transcription", "Enable WhatsApp audio transcription with Groq AI or AssemblyAI", "Audio & AI", 3, "ai", "Automation"),

            // --- STYLES TAB (Tab 4), Sub-tab "appearance" ---
            SearchablePreference("bubble_colors", "Change Bubble Colors", "Change Bubble Color on Conversation Screen", "Visual Styles", 4, "appearance", "Styles"),
            SearchablePreference("custom_theme", "Custom Theme CSS", "Customize your WhatsApp using CSS styles", "Visual Styles", 4, "appearance", "Styles"),
            SearchablePreference("custom_time_format", "Seconds on Timestamp", "Show seconds next to any timestamp in the WhatsApp app", "Visual Styles", 4, "appearance", "Styles"),
            SearchablePreference("custom_toolbar", "New Settings Style", "Enable the new settings style, with profile photo on home screen toolbar", "Visual Styles", 4, "appearance", "Styles"),
            SearchablePreference("custom_view_dpi", "Change Default DPI", "Change the DPI setting for the application. Use 0 to reset to default.", "Visual Styles", 4, "appearance", "Styles"),
            SearchablePreference("seen_tick_customization", "View Seen Tick", "Show an icon if the message view was sent to the recipient or unique view was seen", "Visual Styles", 4, "appearance", "Styles"),
            SearchablePreference("floating_bottom_bar", "Floating Bottom Bar", "Enable iOS-style floating bottom navigation bar", "Visual Styles", 4, "appearance", "Styles"),

            // --- STYLES TAB (Tab 4), Sub-tab "layout" ---
            SearchablePreference("dotonline", "Show Online Dot in Conversation List", "Show a green online dot on home screen", "Conversation List Activity & Presence", 4, "layout", "Styles"),
            SearchablePreference("showonlinetext", "Show Online/Last seen in Conversation List", "Show a text online or last seen on home screen", "Conversation List Activity & Presence", 4, "layout", "Styles"),
            SearchablePreference("typing_online_toasts", "Show toast on contact online", "Show a toast when a contact is online", "Conversation List Activity & Presence", 4, "layout", "Styles"),
            SearchablePreference("channels_enhancements", "Remove Channel Recomendations", "Remove Channel Recomendations from tab Status", "Home Layout & Navigation", 4, "layout", "Styles"),
            SearchablePreference("chat_filters", "Enable filter chats for type", "Show options to filter chats for groups, contacts and unseen messages", "Home Layout & Navigation", 4, "layout", "Styles"),
            SearchablePreference("hide_ui_tabs", "Hide Tabs on Home", "Hide tabs on the home screen such as Updates, Communities and Calls", "Home Layout & Navigation", 4, "layout", "Styles"),
            SearchablePreference("instagram_status_layout", "Enable IGStatus on Home Screen", "Show status style Instagram in Home Screen", "Home Layout & Navigation", 4, "layout", "Styles"),
            SearchablePreference("separate_groups_tabs", "Separate Groups", "Separate your chats by: Groups, Private Chats, Status, Calls, Communities", "Home Layout & Navigation", 4, "layout", "Styles"),
            SearchablePreference("quick_home_menu", "Quick Action Home Menu", "Add quick actions to home long-press", "Home Layout & Navigation", 4, "layout", "Styles"),
            SearchablePreference("contact_blocked_verify", "Show Contact Added Status in Conversation", "This option verifies whether the contact added you or has a public profile photo", "Utilities & Data", 4, "layout", "Styles"),
            SearchablePreference("miscellaneous_enhancements", "Disable Screen off on proximity sensor", "Disable Screen off on proximity sensor to the whole WhatsApp", "Utilities & Data", 4, "layout", "Styles"),

            // --- SETTINGS & SYSTEM ---
            SearchablePreference("supported_versions", "Supported WhatsApp Versions", "Manage verified WhatsApp and WA Business versions & wildcards", "System & Compatibility", -1, null, "Settings"),
            SearchablePreference("bypass_version_check", "Disable Version Check", "Disables the supported version check of WhatsApp", "System & Compatibility", -1, null, "Settings"),
            SearchablePreference("bootloader_spoofer", "Bootloader Spoofer", "Spoof verified boot attestation for WhatsApp integrity checks", "System & Compatibility", -1, null, "Settings"),

            // --- PREMIUM (Paywall) ---
            SearchablePreference("license_verification", "License Verification & Activation", "Manages license key entry and validation", "Premium", -1, null, "Premium"),
            SearchablePreference("pro_features_unlock", "Pro Feature Management", "Dynamic locking/unlocking of premium features", "Premium", -1, null, "Premium")
        )
    }

    // Enhanced multi-token search filter: matches all query terms across title, description, section, key, and tabLabel
    val filteredPreferences = remember(query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            preferenceRegistry
        } else {
            val terms = trimmed.lowercase().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            preferenceRegistry.filter { pref ->
                val haystack = "${pref.title} ${pref.description} ${pref.section} ${pref.key} ${pref.tabLabel}".lowercase()
                terms.all { term -> haystack.contains(term) }
            }
        }
    }

    val groupedPreferences = remember(filteredPreferences) {
        filteredPreferences.groupBy { it.section }
    }

    Scaffold(
        topBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colors.surface
            ) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = spacing.pageMargin),
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        IconButton(
                            onClick = { navController.popBack() },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                        ) {
                            Icon(
                                imageVector = WaexIcons.Back,
                                contentDescription = "Back",
                                tint = colors.onBackground,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        WaexSearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = "Search features & preferences...",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.6f))
                }
            }
        },

        containerColor = colors.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (filteredPreferences.isEmpty()) {
                WaexEmptyState(
                    title = "No preferences found",
                    description = "Try searching for a different keyword or feature name.",
                    icon = WaexIcons.Search,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = spacing.pageMargin, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    groupedPreferences.forEach { (section, preferences) ->
                        item(key = "section_$section") {
                            Text(
                                text = section,
                                style = typography.bodyLg,
                                fontWeight = FontWeight.Bold,
                                color = colors.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                        }
                        items(preferences, key = { it.key }) { pref ->
                            // Custom badge color per tab category
                            val (badgeBg, badgeTextColor) = when (pref.tabLabel) {
                                "Privacy" -> Color(0xFF1E88E5).copy(alpha = 0.12f) to Color(0xFF1E88E5)
                                "Conversation" -> Color(0xFF8E24AA).copy(alpha = 0.12f) to Color(0xFF8E24AA)
                                "Media" -> Color(0xFFFB8C00).copy(alpha = 0.12f) to Color(0xFFFB8C00)
                                "Automation" -> Color(0xFF43A047).copy(alpha = 0.12f) to Color(0xFF43A047)
                                "Styles" -> Color(0xFF00ACC1).copy(alpha = 0.12f) to Color(0xFF00ACC1)
                                "Premium" -> Color(0xFFE53935).copy(alpha = 0.12f) to Color(0xFFE53935)
                                else -> colors.primaryContainer to colors.primary
                            }

                            Surface(
                                shape = radius.bentoCardShape,
                                color = colors.surfaceDim,
                                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (pref.key == "pro_features_unlock" || pref.key == "license_verification") {
                                                navController.navigateTo(com.waenhancer.ui.navigation.Screen.LicenseActivation)
                                            } else {
                                                navController.navigateToPreference(
                                                    tabIndex = pref.tabIndex,
                                                    subTabId = pref.subTabId,
                                                    preferenceKey = pref.key
                                                )
                                            }
                                        }
                                        .padding(16.dp)
                                ) {
                                    // Floating Tag Badge on top-right of the card
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(badgeBg)
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = pref.tabLabel,
                                            style = typography.labelSm,
                                            fontWeight = FontWeight.Bold,
                                            color = badgeTextColor,
                                            fontSize = 10.sp
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 64.dp), // Prevent text overlapping the top-right badge
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = pref.title,
                                                style = typography.bodyLg,
                                                fontWeight = FontWeight.SemiBold,
                                                color = colors.onSurface
                                            )
                                            Text(
                                                text = pref.description,
                                                style = typography.bodyMd,
                                                color = colors.onSurfaceVariant,
                                                fontSize = 12.sp,
                                                lineHeight = 17.sp
                                            )
                                            Text(
                                                text = "Section: ${pref.section}",
                                                style = typography.labelSm,
                                                color = colors.primary,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }

                                        Icon(
                                            imageVector = WaexIcons.ChevronRight,
                                            contentDescription = null,
                                            tint = colors.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
