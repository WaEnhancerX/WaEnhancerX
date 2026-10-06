package com.waenhancer.ui.screens.settings

import com.waenhancer.ui.components.WaexProChip

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.components.StitchSwitch
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import kotlinx.coroutines.delay

@Composable
fun StylesSettingsScreen() {
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var selectedTab by remember { mutableStateOf("appearance") } // "appearance" | "layout"

    val navController = LocalWaexNavController.current
    val scrollState = rememberScrollState()
    val itemCoordinates = remember { mutableStateMapOf<String, Float>() }
    var containerY by remember { mutableStateOf(0f) }

    LaunchedEffect(navController.targetSubTabId) {
        val target = navController.targetSubTabId
        if (target == "appearance" || target == "layout") {
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
    val stylesKeys = remember {
        listOf(
            "bubble_colors", "custom_theme", "custom_time_format", "custom_toolbar",
            "custom_view_dpi", "seen_tick_customization", "floating_bottom_bar",
            "dotonline", "showonlinetext", "typing_online_toasts",
            "channels_enhancements", "chat_filters", "hide_ui_tabs",
            "instagram_status_layout", "separate_groups_tabs", "quick_home_menu",
            "contact_blocked_verify",
            "send_audio_as_voice_status", "miscellaneous_enhancements"
        )
    }

    val settingsState = remember {
        mutableStateMapOf<String, Boolean>().apply {
            stylesKeys.forEach { key ->
                val defaultValue = if (key == "send_audio_as_voice_status") {
                    preferenceManager.getBoolean("voice_status_enhancement", false)
                } else false
                put(key, preferenceManager.getBoolean(key, defaultValue))
            }
        }
    }

    val updatePreference: (String, Boolean) -> Unit = { key, value ->
        settingsState[key] = value
        preferenceManager.putBoolean(key, value)
        if (key == "send_audio_as_voice_status") {
            preferenceManager.putBoolean("voice_status_enhancement", value)
        }
    }


    val isPro = com.waenhancer.ui.navigation.LocalIsPro.current
    val onActivatePro = com.waenhancer.ui.navigation.LocalOnActivatePro.current

    val appearanceGroups = listOf(
        StyleGroup(
            "Visual Styles",
            WaexIcons.Palette,
            listOf(
                StyleItem("Change Bubble Colors", "Change Bubble Color on Conversation Screen", "bubble_colors"),
                StyleItem("Custom Theme CSS", "Customize your WhatsApp using CSS styles", "custom_theme", isPro = true),
                StyleItem("Seconds on Timestamp", "Show seconds next to any timestamp in the WhatsApp app", "custom_time_format"),
                StyleItem("New Settings Style", "Enable the new settings style, with profile photo on home screen toolbar", "custom_toolbar"),
                StyleItem("Change Default DPI", "Change the DPI setting for the application. Use 0 to reset to default.", "custom_view_dpi"),
                StyleItem("View Seen Tick", "Show an icon if the message view was sent to the recipient or unique view was seen", "seen_tick_customization"),
                StyleItem("Floating Bottom Bar", "Enable iOS-style floating bottom navigation bar", "floating_bottom_bar")
            )
        )
    )

    val layoutGroups = listOf(
        StyleGroup(
            "Conversation List Activity & Presence",
            WaexIcons.Security,
            listOf(
                StyleItem("Show Online Dot in Conversation List", "Show a green online dot on home screen", "dotonline"),
                StyleItem("Show Online/Last seen in Conversation List", "Show a text online or last seen on home screen", "showonlinetext"),
                StyleItem("Show toast on contact online", "Show a toast when a contact is online", "typing_online_toasts")
            )
        ),
        StyleGroup(
            "Home Layout & Navigation",
            WaexIcons.GridView,
            listOf(
                StyleItem("Remove Channel Recomendations", "Remove Channel Recomendations from tab Status", "channels_enhancements"),
                StyleItem("Enable filter chats for type", "Show options to filter chats for groups, contacts and unseen messages", "chat_filters"),
                StyleItem("Hide Tabs on Home", "Hide tabs on the home screen such as Updates, Communities and Calls", "hide_ui_tabs"),
                StyleItem("Enable IGStatus on Home Screen", "Show status style Instagram in Home Screen", "instagram_status_layout"),
                StyleItem("Separate Groups", "Separate your chats by: Groups, Private Chats, Status, Calls, Communities", "separate_groups_tabs"),
                StyleItem("Quick Action Home Menu", "Add quick actions to home long-press", "quick_home_menu")
            )
        ),
        StyleGroup(
            "Utilities & Data",
            WaexIcons.Extension,
            listOf(
                StyleItem("Show Contact Added Status in Conversation", "This option verifies whether the contact added you or has a public profile photo", "contact_blocked_verify"),
                StyleItem("Audio to Voice Status", "Pick a local audio file and publish it as a voice status", "send_audio_as_voice_status", isPro = true),
                StyleItem("Disable Screen off on proximity sensor", "Disable Screen off on proximity sensor to the whole WhatsApp", "miscellaneous_enhancements")
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

                val activeIndex = if (selectedTab == "appearance") 0 else 1
                val targetOffset = tabWidth * activeIndex
                val animatedOffset by animateDpAsState(
                    targetValue = targetOffset,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "styles_tab_offset"
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
                    listOf("appearance" to "Appearance", "layout" to "Home & Feed").forEach { (tabId, label) ->
                        val isSelected = selectedTab == tabId
                        val textColor by animateColorAsState(
                            targetValue = if (isSelected) colors.primary else colors.onSurfaceVariant,
                            animationSpec = tween(150),
                            label = "styles_tab_text_color"
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

        // Render Style Groups
        val activeGroups = if (selectedTab == "appearance") appearanceGroups else layoutGroups
        activeGroups.forEach { group ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.pageMargin)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                ) {
                    Icon(
                        imageVector = group.icon,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = group.title,
                        style = typography.labelSm,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurfaceVariant
                    )
                }

                Surface(
                    shape = radius.bentoCardShape,
                    color = colors.surfaceDim,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        group.items.forEachIndexed { index, item ->
                            val isLocked = item.isPro && !isPro
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
                                        } else {
                                            val currentVal = settingsState[item.key] ?: false
                                            updatePreference(item.key, !currentVal)
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp)
                                        .then(if (isLocked) Modifier.alpha(0.6f) else Modifier),
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
                                            text = if (isLocked) "${item.sublabel} • Requires Pro" else item.sublabel,
                                            style = typography.bodyMd,
                                            color = colors.onSurfaceVariant,
                                            fontSize = 12.sp
                                        )
                                    }
                                    StitchSwitch(
                                        checked = if (isLocked) false else (settingsState[item.key] ?: false),
                                        onCheckedChange = if (isLocked) null else { isChecked -> updatePreference(item.key, isChecked) },
                                        enabled = !isLocked
                                    )
                                }
                                if (item.isPro) {
                                    WaexProChip(
                                        isUnlocked = isPro,
                                        modifier = Modifier.align(Alignment.TopEnd)
                                    )
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

}

private data class StyleGroup(
    val title: String,
    val icon: ImageVector,
    val items: List<StyleItem>
)

private data class StyleItem(
    val label: String,
    val sublabel: String,
    val key: String,
    val isPro: Boolean = false
)

@Preview(showBackground = true)
@Composable
fun StylesSettingsScreenPreview() {
    WaexTheme {
        StylesSettingsScreen()
    }
}
