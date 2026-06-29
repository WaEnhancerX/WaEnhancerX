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
            SearchablePreference("ghost", "Ghost Mode", "Appear completely offline", "Privacy Core", 1, "privacy", "Privacy"),
            SearchablePreference("seen_tick", "Hide Seen Tick", "Remove seen confirmation", "Privacy Core", 1, "privacy", "Privacy"),
            SearchablePreference("blue_tick", "Hide Blue Tick", "Prevent read receipts", "Privacy Core", 1, "privacy", "Privacy"),
            SearchablePreference("second_tick", "Hide Second Tick", "Delivered state remains hidden", "Privacy Core", 1, "privacy", "Privacy"),
            SearchablePreference("typing", "Hide Typing", "Typing indicator suppressed", "Privacy Core", 1, "privacy", "Privacy"),
            SearchablePreference("recording", "Hide Recording", "Voice recording indicator hidden", "Privacy Core", 1, "privacy", "Privacy"),
            SearchablePreference("anti_revoke_msg", "Anti Revoke Messages", "Keep deleted messages visible", "Message Protection", 1, "privacy", "Privacy"),
            SearchablePreference("anti_revoke_status", "Anti Revoke Status", "Preserve deleted statuses", "Message Protection", 1, "privacy", "Privacy"),
            SearchablePreference("anti_disappear", "Anti Disappearing Messages", "Block self-destruct timers", "Message Protection", 1, "privacy", "Privacy"),
            SearchablePreference("del_history", "Deleted Message History", "View message deletion log", "Message Protection", 1, "privacy", "Privacy"),
            SearchablePreference("del_media", "Deleted Media Recovery", "Recover deleted attachments", "Message Protection", 1, "privacy", "Privacy"),
            SearchablePreference("hide_view_status", "Hide View Status", "View statuses anonymously", "Status Privacy", 1, "privacy", "Privacy"),
            SearchablePreference("freeze_lastseen", "Freeze Last Seen", "Lock your last seen timestamp", "Status Privacy", 1, "privacy", "Privacy"),
            SearchablePreference("custom_online", "Custom Online State", "Control your online visibility", "Status Privacy", 1, "privacy", "Privacy"),
            SearchablePreference("read_receipts", "Disable Read Receipts", "Global read receipt disable", "Status Privacy", 1, "privacy", "Privacy"),

            // --- PRIVACY TAB (Tab 1), Sub-tab "conversation" ---
            SearchablePreference("selectable", "Selectable Messages", "Tap to select any message", "Message Controls", 1, "conversation", "Privacy"),
            SearchablePreference("copy_nolimit", "Copy Without Limits", "Copy protected messages", "Message Controls", 1, "conversation", "Privacy"),
            SearchablePreference("doubletap_like", "Double Tap To Like", "Quick heart reaction", "Message Controls", 1, "conversation", "Privacy"),
            SearchablePreference("device_source", "Message Device Source", "Show sender device type", "Message Controls", 1, "conversation", "Privacy"),
            SearchablePreference("msg_history", "Message History", "Edit history visibility", "Message Controls", 1, "conversation", "Privacy"),
            SearchablePreference("translator", "Enable Translator", "In-chat message translation", "Translation", 1, "conversation", "Privacy"),
            SearchablePreference("auto_translate", "Auto Translate", "Translate on receive", "Translation", 1, "conversation", "Privacy"),
            SearchablePreference("pref_lang", "Preferred Language", "Configure translation target language", "Translation", 1, "conversation", "Privacy"),
            SearchablePreference("quick_reactions", "Quick Reactions", "Custom reaction set", "Chat Utilities", 1, "conversation", "Privacy"),
            SearchablePreference("ctx_icons", "Context Menu Icons", "Icons in long-press menu", "Chat Utilities", 1, "conversation", "Privacy"),
            SearchablePreference("ext_menu", "Extended Menu Actions", "Additional action options", "Chat Utilities", 1, "conversation", "Privacy"),
            SearchablePreference("internal_dialer", "Internal Dialer", "Use built-in call interface", "Chat Utilities", 1, "conversation", "Privacy"),

            // --- MEDIA TAB (Tab 2) ---
            SearchablePreference("hd_images", "HD Images", "Upload images without compression", "Media Quality", 2, null, "Media & Status"),
            SearchablePreference("hd_videos", "HD Videos", "Full quality video uploads", "Media Quality", 2, null, "Media & Status"),
            SearchablePreference("no_compression", "Disable Compression", "Force original quality", "Media Quality", 2, null, "Media & Status"),
            SearchablePreference("quality_selector", "Quality Selector", "Per-send quality control", "Media Quality", 2, null, "Media & Status"),
            SearchablePreference("status_download", "Status Download", "Save any status to gallery", "Status Tools", 2, null, "Media & Status"),
            SearchablePreference("status_categories", "Status Categories", "Organize status by contact group", "Status Tools", 2, null, "Media & Status"),
            SearchablePreference("chron_status", "Chronological Status Feed", "Time-ordered status view", "Status Tools", 2, null, "Media & Status"),
            SearchablePreference("status_music", "Status Music", "Add music to video statuses", "Status Tools", 2, null, "Media & Status"),
            SearchablePreference("unlimited_view", "Unlimited View Once", "View protected media freely", "View Once Controls", 2, null, "Media & Status"),
            SearchablePreference("counter_overlay", "Counter Overlay", "Show view count on media", "View Once Controls", 2, null, "Media & Status"),
            SearchablePreference("save_view_once", "Save View Once Media", "Persist view-once content", "View Once Controls", 2, null, "Media & Status"),
            SearchablePreference("disable_ads", "Disable Ads", "Remove all in-app advertising", "Ads & AI Removal", 2, null, "Media & Status"),
            SearchablePreference("disable_ai_search", "Disable Meta AI Search", "Remove AI search integration", "Ads & AI Removal", 2, null, "Media & Status"),
            SearchablePreference("disable_ai_fab", "Disable Meta AI FAB", "Hide the floating AI button", "Ads & AI Removal", 2, null, "Media & Status"),

            // --- AUTOMATION TAB (Tab 3), Sub-tab "automation" ---
            SearchablePreference("auto_reply", "Auto Reply", "Respond automatically to incoming messages", "Automation", 3, "automation", "Automation"),
            SearchablePreference("status_forward", "Status Forward", "Auto-forward received statuses to contacts", "Automation", 3, "automation", "Automation"),
            SearchablePreference("always_typing", "Always Typing", "Maintain typing indicator at all times", "Automation", 3, "automation", "Automation"),
            SearchablePreference("scheduled_actions", "Scheduled Actions", "Trigger tasks at specific times or intervals", "Automation", 3, "automation", "Automation"),
            SearchablePreference("intent_action", "Intent Action", "Configure background Tasker Action", "Tasker Integration", 3, "automation", "Automation"),
            SearchablePreference("intent_data", "Intent Data", "Configure background Tasker Data URI", "Tasker Integration", 3, "automation", "Automation"),
            SearchablePreference("intent_package", "Intent Package", "Configure background Tasker Package target", "Tasker Integration", 3, "automation", "Automation"),
            SearchablePreference("intent_category", "Intent Category", "Configure background Tasker Category", "Tasker Integration", 3, "automation", "Automation"),

            // --- AUTOMATION TAB (Tab 3), Sub-tab "ai" ---
            SearchablePreference("assembly_key", "AssemblyAI API Key", "AssemblyAI connection API configuration", "AssemblyAI", 3, "ai", "Automation"),
            SearchablePreference("groq_key", "Groq API Key", "Groq connection API configuration", "Groq", 3, "ai", "Automation"),
            SearchablePreference("transcription", "Audio Transcription", "Convert voice messages to text", "Voice Features", 3, "ai", "Automation"),
            SearchablePreference("stt", "Speech To Text", "Live voice input for messages", "Voice Features", 3, "ai", "Automation"),
            SearchablePreference("offline", "Offline Models", "Use on-device processing", "Voice Features", 3, "ai", "Automation")
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
                color = colors.surface,
                shadowElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .padding(horizontal = spacing.pageMargin),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { navController.popBack() },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .align(Alignment.CenterVertically)
                        ) {
                            Icon(
                                imageVector = WaexIcons.Back,
                                contentDescription = "Back",
                                tint = colors.onBackground,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        WaexSearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = "Search features & preferences...",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)
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
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                            )
                        }
                        items(preferences, key = { it.key }) { pref ->
                            Surface(
                                shape = radius.bentoCardShape,
                                color = colors.surfaceDim,
                                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .clickable {
                                            navController.navigateToPreference(
                                                tabIndex = pref.tabIndex,
                                                subTabId = pref.subTabId,
                                                preferenceKey = pref.key
                                            )
                                        }
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = pref.title,
                                                style = typography.bodyLg,
                                                fontWeight = FontWeight.Bold,
                                                color = colors.onSurface
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(colors.primaryContainer)
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = pref.tabLabel,
                                                    style = typography.labelSm,
                                                    fontWeight = FontWeight.Bold,
                                                    color = colors.primary,
                                                    fontSize = 9.sp
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = pref.description,
                                            style = typography.bodyMd,
                                            color = colors.onSurfaceVariant,
                                            fontSize = 12.sp
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Section: ${pref.section}",
                                            style = typography.labelSm,
                                            color = colors.primary.copy(alpha = 0.8f),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium
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
            }
        }
    }
}
