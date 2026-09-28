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
            SearchablePreference("always_online", "Always Online", "Keep connection status constantly showing as Online inside WhatsApp", "Presence & Online Visibility", 1, "privacy", "Privacy"),
            SearchablePreference("online_status_indicator", "Hide Online Status", "Hide your green online dot and online status from others", "Presence & Online Visibility", 1, "privacy", "Privacy"),
            SearchablePreference("freeze_last_seen", "Freeze Last Seen", "Lock your last seen timestamp in place", "Presence & Online Visibility", 1, "privacy", "Privacy"),
            SearchablePreference("typing_privacy", "Hide Typing & Recording Indicators", "Hides typing and recording status from others", "Presence & Online Visibility", 1, "privacy", "Privacy"),
            SearchablePreference("stealth_status_view", "Stealth Status Viewing", "View status updates without sending view receipts", "Presence & Online Visibility", 1, "privacy", "Privacy"),
            SearchablePreference("hide_read_receipts", "Hide Read Receipts (Blue Ticks)", "Read messages without sending blue read checkmarks", "Message & Delivery Receipts", 1, "privacy", "Privacy"),
            SearchablePreference("hide_delivery_receipts", "Hide Delivery Receipts (Second Tick)", "Receive messages without sending second delivery checkmark", "Message & Delivery Receipts", 1, "privacy", "Privacy"),
            SearchablePreference("hide_seen_receipts", "Hide Voice & Media Seen Receipts", "Listen to audio notes and view media without seen checkmarks", "Message & Delivery Receipts", 1, "privacy", "Privacy"),
            SearchablePreference("hide_forwarded_tag", "Hide Forwarded Tag", "Prevent forwarded tag from appearing on shared messages", "Message & Delivery Receipts", 1, "privacy", "Privacy"),
            SearchablePreference("anti_revoke", "Anti-Revoke Messages & Statuses", "Keep deleted messages and statuses visible to you", "Message Protection & Security", 1, "privacy", "Privacy"),
            SearchablePreference("anti_view_once", "Anti-View Once", "Bypass view-once constraints on incoming media", "Message Protection & Security", 1, "privacy", "Privacy"),
            SearchablePreference("locked_chats_enhancer", "Locked Chats Enhancer", "Customize and bypass locks for specific chat vaults", "Message Protection & Security", 1, "privacy", "Privacy"),
            SearchablePreference("typearchive", "Hide Archived Chats", "Completely hide archived chats from the main conversations list", "Message Protection & Security", 1, "privacy", "Privacy"),
            SearchablePreference("dnd_mode", "Do Not Disturb (DND) Mode", "Temporarily block incoming messages dynamically", "Message Protection & Security", 1, "privacy", "Privacy"),
            SearchablePreference("hide_chats", "Hide Chats / Vault", "Hide and lock private chats from the main chat list", "Message Protection & Security", 1, "privacy", "Privacy"),
            SearchablePreference("custom_privacy", "Per-Contact Custom Privacy", "Set separate rules for specific contacts", "Advanced Rules & Filtering", 1, "privacy", "Privacy"),
            SearchablePreference("call_privacy", "Call Privacy & Filtering", "Block calls from unwanted contacts", "Advanced Rules & Filtering", 1, "privacy", "Privacy"),

            // --- PRIVACY TAB (Tab 1), Sub-tab "conversation" ---
            SearchablePreference("anti_edit_messages", "Anti-Edit Messages", "Keep original version of edited messages in chat", "Message Controls", 1, "conversation", "Conversation"),
            SearchablePreference("preserve_delete_for_me", "Preserve Delete For Me", "Keep local copy when messages are deleted for me", "Message Controls", 1, "conversation", "Conversation"),
            SearchablePreference("sticker_confirm_alert", "Sticker Confirmation Alert", "Ask before sending clicked stickers", "Message Controls", 1, "conversation", "Conversation"),
            SearchablePreference("jump_to_first_message", "Jump to First Message", "Add an option to jump directly to the beginning of any chat", "Message Controls", 1, "conversation", "Conversation"),
            SearchablePreference("copy_status_text", "Copy Status Text", "Allow copying text from status updates", "Message Controls", 1, "conversation", "Conversation"),
            SearchablePreference("inline_translation", "Inline Message Translation", "Tap-to-translate messages directly inline", "Message Controls", 1, "conversation", "Conversation"),
            SearchablePreference("direct_chat_dialer", "Direct Chat Dialer", "Message someone without saving their contact info", "Chat & Group Utilities", 1, "conversation", "Conversation"),
            SearchablePreference("unlimited_pinned_chats", "Unlimited Pinned Chats", "Pin more than 3 chats to the top", "Chat & Group Utilities", 1, "conversation", "Conversation"),
            SearchablePreference("group_admin_tools", "Group Admin Tools", "Unlock hidden moderation controls", "Chat & Group Utilities", 1, "conversation", "Conversation"),
            SearchablePreference("chat_limits_bypass", "Chat Limits Bypass", "Bypass group sharing and forwarding constraints", "Chat & Group Utilities", 1, "conversation", "Conversation"),
            SearchablePreference("call_type_controller", "Call Type Controller", "Force voice-only or video-only incoming calls", "Chat & Group Utilities", 1, "conversation", "Conversation"),
            SearchablePreference("custom_filter_groups", "Custom Filter Groups", "Group chats by custom categories", "Chat & Group Utilities", 1, "conversation", "Conversation"),
            SearchablePreference("status_text_composer", "Status Text Composer Enhancements", "Format text status updates beautifully", "Chat & Group Utilities", 1, "conversation", "Conversation"),

            // --- MEDIA TAB (Tab 2) ---
            SearchablePreference("media_upload_quality", "Media Upload Quality Enhancer", "Advanced control over upload compression algorithms", "Media Quality & Status Enhancements", 2, null, "Media"),
            SearchablePreference("status_downloader", "Status Downloader", "Adds a direct download button to save statuses", "Media Quality & Status Enhancements", 2, null, "Media"),
            SearchablePreference("voice_status_enhancement", "Voice Status Enhancement", "Upload high-quality voice status updates", "Media Quality & Status Enhancements", 2, null, "Media"),
            SearchablePreference("call_recording", "Call Recording", "Enable automatic call recording for voice/video", "Media Utility & Downloader", 2, null, "Media"),
            SearchablePreference("download_profile", "Download Profile Photo", "Download full-res profile pictures directly", "Media Utility & Downloader", 2, null, "Media"),
            SearchablePreference("download_video_note", "Download Video Notes", "Save circular video notes to device", "Media Utility & Downloader", 2, null, "Media"),
            SearchablePreference("download_view_once", "Download View-Once Media", "Save view-once media items directly to gallery", "Media Utility & Downloader", 2, null, "Media"),
            SearchablePreference("file_size_spoofer", "File Size Spoofer", "Bypass large file limits when sending media", "Media Utility & Downloader", 2, null, "Media"),
            SearchablePreference("media_preview", "Direct Media Preview", "View media files directly from notification or chat list", "Media Utility & Downloader", 2, null, "Media"),
            SearchablePreference("video_note_converter", "Video Note Converter", "Convert standard videos into circular video notes", "Media Utility & Downloader", 2, null, "Media"),

            // --- AUTOMATION TAB (Tab 3), Sub-tab "automation" ---
            SearchablePreference("always_typing", "Always Typing Mode", "Maintain typing indicator at all times", "Automation", 3, "automation", "Automation"),
            SearchablePreference("auto_status_forward", "Auto Status Forwarding", "Auto-forward received statuses to contacts", "Automation", 3, "automation", "Automation"),
            SearchablePreference("message_bomber", "Message Bomber", "Send automated message bursts", "Automation", 3, "automation", "Automation"),
            SearchablePreference("status_video_splitter", "Status Video Splitter", "Auto-split long videos for status updates", "Automation", 3, "automation", "Automation"),
            SearchablePreference("tasker_integration", "Tasker Integration", "Exposes WAEX triggers and actions to Tasker", "Automation", 3, "automation", "Automation"),

            // --- AUTOMATION TAB (Tab 3), Sub-tab "ai" ---
            SearchablePreference("voice_transcription", "AI Voice-to-Text Transcription", "Transcribes voice messages into text bubbles using AI", "Audio & AI", 3, "ai", "Automation"),

            // --- STYLES TAB (Tab 4), Sub-tab "appearance" ---
            SearchablePreference("bubble_colors", "Chat Bubble Custom Colors", "Customize background colors of bubbles", "Visual Styles", 4, "appearance", "Styles"),
            SearchablePreference("custom_theme", "Dynamic Theme Customization", "Apply fully custom app-wide styling theme", "Visual Styles", 4, "appearance", "Styles"),
            SearchablePreference("custom_time_format", "Custom Time Format", "Set 24h or relative time display", "Visual Styles", 4, "appearance", "Styles"),
            SearchablePreference("custom_toolbar", "Custom Toolbar Layout", "Customize quick actions in main header toolbar", "Visual Styles", 4, "appearance", "Styles"),
            SearchablePreference("custom_view_dpi", "Custom View & DPI Settings", "Adjust UI scale and density overrides", "Visual Styles", 4, "appearance", "Styles"),
            SearchablePreference("seen_tick_customization", "Seen Tick Style Customization", "Change WhatsApp seen tick icons", "Visual Styles", 4, "appearance", "Styles"),
            SearchablePreference("floating_bottom_bar", "Floating Bottom Navigation Bar", "Convert main bottom bar to floating capsule", "Visual Styles", 4, "appearance", "Styles"),

            // --- STYLES TAB (Tab 4), Sub-tab "layout" ---
            SearchablePreference("dotonline", "Online Dot in Chat List", "Show a green dot indicator on avatars when contacts are online", "Conversation List Activity & Presence", 4, "layout", "Styles"),
            SearchablePreference("showonlinetext", "Online / Last Seen in Chat List", "Display contact online or last seen text directly in chat rows", "Conversation List Activity & Presence", 4, "layout", "Styles"),
            SearchablePreference("typing_online_toasts", "Contact Online & Typing Toasts", "Get real-time toast alerts when contacts come online or type", "Conversation List Activity & Presence", 4, "layout", "Styles"),
            SearchablePreference("channels_enhancements", "Channels Enhancements", "Clean feed, disable channel recommendations", "Home Layout & Navigation", 4, "layout", "Styles"),
            SearchablePreference("chat_filters", "Chat Filter Visibility Control", "Show or hide standard filter chips", "Home Layout & Navigation", 4, "layout", "Styles"),
            SearchablePreference("hide_ui_tabs", "Hide Home UI Elements", "Remove communities, call or status tabs", "Home Layout & Navigation", 4, "layout", "Styles"),
            SearchablePreference("instagram_status_layout", "Instagram-style Status Layout", "Render statuses as story circles at top", "Home Layout & Navigation", 4, "layout", "Styles"),
            SearchablePreference("separate_groups_tabs", "Separate Groups & Personal Chats", "Split chats into two distinct home tabs", "Home Layout & Navigation", 4, "layout", "Styles"),
            SearchablePreference("quick_home_menu", "Quick Action Home Menu", "Add quick actions to home long-press", "Home Layout & Navigation", 4, "layout", "Styles"),
            SearchablePreference("backup_restore", "Backup & Restore Preferences", "Import or export WAEX configurations", "Utilities & Data", 4, "layout", "Styles"),
            SearchablePreference("contact_blocked_verify", "Contact Blocked Verifier", "Verify if a contact has blocked you", "Utilities & Data", 4, "layout", "Styles"),
            // --- SETTINGS & SYSTEM ---
            SearchablePreference("supported_versions", "Supported WhatsApp Versions", "Manage verified WhatsApp and WA Business versions & wildcards", "System & Compatibility", -1, null, "Settings"),
            SearchablePreference("bypass_version_check", "Bypass Version Verification", "Force WAEX hooks to load on any WhatsApp version", "System & Compatibility", -1, null, "Settings"),

            // --- PREMIUM (Paywall) ---
            SearchablePreference("license_verification", "License Verification & Activation", "Manages license key entry and validation", "Premium", -1, null, "Premium"),
            SearchablePreference("pro_features_unlock", "Pro Feature Management", "Dynamic locking/unlocking of premium features", "Premium", -1, null, "Premium")
        )
    }

    // Filter registry based on query
    val filteredPreferences = remember(query) {
        if (query.trim().isEmpty()) {
            preferenceRegistry
        } else {
            preferenceRegistry.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.description.contains(query, ignoreCase = true) ||
                it.section.contains(query, ignoreCase = true)
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

