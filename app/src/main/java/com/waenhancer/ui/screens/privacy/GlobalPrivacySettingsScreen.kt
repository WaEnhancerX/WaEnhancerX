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

    // Settings local state tracking
    val settingsState = remember {
        mutableStateMapOf(
            "ghost" to true,
            "seen_tick" to true,
            "blue_tick" to false,
            "second_tick" to false,
            "typing" to true,
            "recording" to false,
            "anti_revoke_msg" to true,
            "anti_revoke_status" to true,
            "anti_disappear" to false,
            "del_history" to true,
            "del_media" to false,
            "hide_view_status" to true,
            "freeze_lastseen" to false,
            "custom_online" to false,
            "read_receipts" to true,
            "selectable" to true,
            "copy_nolimit" to true,
            "doubletap_like" to false,
            "device_source" to false,
            "msg_history" to true,
            "translator" to false,
            "auto_translate" to false,
            "pref_lang" to false,
            "quick_reactions" to true,
            "ctx_icons" to true,
            "ext_menu" to false,
            "internal_dialer" to false
        )
    }

    val privacyGroups = listOf(
        SettingGroup(
            "Privacy Core",
            listOf(
                SettingItem("Ghost Mode", "Appear completely offline", "ghost"),
                SettingItem("Hide Seen Tick", "Remove seen confirmation", "seen_tick"),
                SettingItem("Hide Blue Tick", "Prevent read receipts", "blue_tick"),
                SettingItem("Hide Second Tick", "Delivered state remains hidden", "second_tick"),
                SettingItem("Hide Typing", "Typing indicator suppressed", "typing"),
                SettingItem("Hide Recording", "Voice recording indicator hidden", "recording")
            )
        ),
        SettingGroup(
            "Message Protection",
            listOf(
                SettingItem("Anti Revoke Messages", "Keep deleted messages visible", "anti_revoke_msg"),
                SettingItem("Anti Revoke Status", "Preserve deleted statuses", "anti_revoke_status"),
                SettingItem("Anti Disappearing Messages", "Block self-destruct timers", "anti_disappear"),
                SettingItem("Deleted Message History", "View message deletion log", "del_history"),
                SettingItem("Deleted Media Recovery", "Recover deleted attachments", "del_media")
            )
        ),
        SettingGroup(
            "Status Privacy",
            listOf(
                SettingItem("Hide View Status", "View statuses anonymously", "hide_view_status"),
                SettingItem("Freeze Last Seen", "Lock your last seen timestamp", "freeze_lastseen"),
                SettingItem("Custom Online State", "Control your online visibility", "custom_online"),
                SettingItem("Disable Read Receipts", "Global read receipt disable", "read_receipts")
            )
        )
    )

    val conversationGroups = listOf(
        SettingGroup(
            "Message Controls",
            listOf(
                SettingItem("Selectable Messages", "Tap to select any message", "selectable"),
                SettingItem("Copy Without Limits", "Copy protected messages", "copy_nolimit"),
                SettingItem("Double Tap To Like", "Quick heart reaction", "doubletap_like"),
                SettingItem("Message Device Source", "Show sender device type", "device_source"),
                SettingItem("Message History", "Edit history visibility", "msg_history")
            )
        ),
        SettingGroup(
            "Translation",
            listOf(
                SettingItem("Enable Translator", "In-chat message translation", "translator"),
                SettingItem("Auto Translate", "Translate on receive", "auto_translate"),
                SettingItem("Preferred Language", "English (US)", "pref_lang")
            )
        ),
        SettingGroup(
            "Chat Utilities",
            listOf(
                SettingItem("Quick Reactions", "Custom reaction set", "quick_reactions"),
                SettingItem("Context Menu Icons", "Icons in long-press menu", "ctx_icons"),
                SettingItem("Extended Menu Actions", "Additional action options", "ext_menu"),
                SettingItem("Internal Dialer", "Use built-in call interface", "internal_dialer")
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
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                listOf("privacy" to "Privacy", "conversation" to "Conversation").forEach { (tabId, label) ->
                    val isSelected = selectedTab == tabId
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f)
                            .clip(radius.defaultShape)
                            .background(if (isSelected) colors.surface else Color.Transparent)
                            .border(
                                width = if (isSelected) 1.dp else 0.dp,
                                color = if (isSelected) colors.outlineVariant else Color.Transparent,
                                shape = radius.defaultShape
                            )
                            .clickable { selectedTab = tabId },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = typography.bodyMd,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) colors.primary else colors.onSurfaceVariant
                        )
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
                    .clickable { onOpenModal("per-contact") }
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
                            imageVector = WaexIcons.Security, // User icon analogue
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Per Contact Rules",
                            style = typography.bodyLg,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface
                        )
                        Text(
                            text = "Set privacy rules per contact",
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
                                        settingsState[item.key] = !currentVal
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
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
                                    onCheckedChange = { settingsState[item.key] = it }
                                )
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
