package com.waenhancer.ui.screens.settings

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
            "backup_restore", "contact_blocked_verify",
            "voice_status_enhancement", "miscellaneous_enhancements"
        )
    }

    val settingsState = remember {
        mutableStateMapOf<String, Boolean>().apply {
            stylesKeys.forEach { key ->
                put(key, preferenceManager.getBoolean(key, false))
            }
        }
    }

    val updatePreference: (String, Boolean) -> Unit = { key, value ->
        settingsState[key] = value
        preferenceManager.putBoolean(key, value)
    }


    val appearanceGroups = listOf(
        StyleGroup(
            "Visual Styles",
            WaexIcons.Palette,
            listOf(
                StyleItem("Chat Bubble Custom Colors", "Customize background colors of bubbles", "bubble_colors"),
                StyleItem("Dynamic Theme Customization", "Apply fully custom app-wide styling theme", "custom_theme"),
                StyleItem("Custom Time Format", "Set 24h or relative time display", "custom_time_format"),
                StyleItem("Custom Toolbar Layout", "Customize quick actions in main header toolbar", "custom_toolbar"),
                StyleItem("Custom View & DPI Settings", "Adjust UI scale and density overrides", "custom_view_dpi"),
                StyleItem("Seen Tick Style Customization", "Change WhatsApp seen tick icons", "seen_tick_customization"),
                StyleItem("Floating Bottom Navigation Bar", "Convert main bottom bar to floating capsule", "floating_bottom_bar")
            )
        )
    )

    val layoutGroups = listOf(
        StyleGroup(
            "Conversation List Activity & Presence",
            WaexIcons.Security,
            listOf(
                StyleItem("Online Dot in Chat List", "Show a green dot indicator on avatars when contacts are online", "dotonline"),
                StyleItem("Online / Last Seen in Chat List", "Display contact online or last seen text directly in chat rows", "showonlinetext"),
                StyleItem("Contact Online & Typing Toasts", "Get real-time toast alerts when contacts come online or type", "typing_online_toasts")
            )
        ),
        StyleGroup(
            "Home Layout & Navigation",
            WaexIcons.GridView,
            listOf(
                StyleItem("Channels Enhancements", "Clean feed, disable channel recommendations", "channels_enhancements"),
                StyleItem("Chat Filter Visibility Control", "Show or hide standard filter chips", "chat_filters"),
                StyleItem("Hide Home UI Elements", "Remove communities, call or status tabs", "hide_ui_tabs"),
                StyleItem("Instagram-style Status Layout", "Render statuses as story circles at top", "instagram_status_layout"),
                StyleItem("Separate Groups & Personal Chats", "Split chats into two distinct home tabs", "separate_groups_tabs"),
                StyleItem("Quick Action Home Menu", "Add quick actions to home long-press", "quick_home_menu")
            )
        ),
        StyleGroup(
            "Utilities & Data",
            WaexIcons.Extension,
            listOf(
                StyleItem("Backup & Restore Preferences", "Import or export WAEX configurations", "backup_restore"),
                StyleItem("Contact Blocked Verifier", "Verify if a contact has blocked you", "contact_blocked_verify"),
                StyleItem("Voice Status Enhancement", "Upload high-quality voice status updates", "voice_status_enhancement"),
                StyleItem("Miscellaneous Enhancements", "Miscellaneous minor feature options", "miscellaneous_enhancements")
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
    val key: String
)

@Preview(showBackground = true)
@Composable
fun StylesSettingsScreenPreview() {
    WaexTheme {
        StylesSettingsScreen()
    }
}
