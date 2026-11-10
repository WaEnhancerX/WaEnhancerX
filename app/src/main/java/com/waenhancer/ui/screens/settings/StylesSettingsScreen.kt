package com.waenhancer.ui.screens.settings

import com.waenhancer.utils.UiText

import android.widget.Toast
import com.waenhancer.ui.components.WaexProChip

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import com.waenhancer.ui.components.WaexCategoryHeader
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
import androidx.compose.ui.platform.LocalContext
import com.waenhancer.config.FeatureCatalog
import com.waenhancer.config.FeatureItem
import com.waenhancer.ui.components.StitchSwitch
import com.waenhancer.ui.components.WaexModalBottomSheet
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.utils.LocalizationHelper
import kotlinx.coroutines.delay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.res.stringResource
import com.waenhancer.app.R
import com.waenhancer.core.preferences.WaexPreferenceManager
import com.waenhancer.ui.navigation.LocalIsPro
import com.waenhancer.ui.navigation.LocalOnActivatePro
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import com.waenhancer.ui.navigation.Screen
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StylesSettingsScreen(targetEpoch: Int = 0) {
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var selectedTab by remember { mutableStateOf("appearance") } // "appearance" | "layout"
    var typedEditorKey by remember { mutableStateOf<String?>(null) }
    var typedValueRevision by remember { mutableIntStateOf(0) }

    val navController = LocalWaexNavController.current
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val appearanceGroups = remember {
        FeatureCatalog.groupsFor(4, "appearance").map { StyleGroup(it.id, WaexIcons.Palette, it.items()) }
    }
    val layoutGroups = remember {
        FeatureCatalog.groupsFor(4, "layout").map { group ->
            val icon = when (group.id) {
                "conversation_list_activity_presence" -> WaexIcons.Security
                "home_layout_navigation" -> WaexIcons.GridView
                else -> WaexIcons.Extension
            }
            StyleGroup(group.id, icon, group.items())
        }
    }
    val expandedSections = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(navController.targetSubTabId) {
        val target = navController.targetSubTabId
        if (target == "appearance" || target == "layout") {
            selectedTab = target
            navController.targetSubTabId = null
        }
    }

    val density = LocalDensity.current

    LaunchedEffect(navController.scrollToTargetKey) {
        val target = navController.scrollToTargetKey
        if (target != null) {
            val inLayout = layoutGroups.any { grp -> grp.items.any { it.key == target } }
            val inAppearance = appearanceGroups.any { grp -> grp.items.any { it.key == target } }
            if (inLayout && selectedTab != "layout") {
                selectedTab = "layout"
                delay(120)
            } else if (inAppearance && selectedTab != "appearance") {
                selectedTab = "appearance"
                delay(120)
            }
            delay(80)
            val groups = if (selectedTab == "layout") layoutGroups else appearanceGroups
            val groupIdx = groups.indexOfFirst { grp -> grp.items.any { it.key == target } }
            if (groupIdx != -1) {
                val group = groups[groupIdx]
                expandedSections[group.title] = true
                val itemIdx = group.items.indexOfFirst { it.key == target }.coerceAtLeast(0)
                val cardIndex = 2 + (groupIdx * 2)
                val estimatedRowHeight = with(density) { 72.dp.roundToPx() }
                val offsetPx = itemIdx * estimatedRowHeight
                listState.animateScrollToItem(cardIndex, offsetPx)
            }
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

    val preferenceManager = LocalWaexPreferenceManager.current
    var removeMetaAiHomeFab by remember(targetEpoch) {
        mutableStateOf(preferenceManager.getBoolean("metaai_remove_home_fab", true))
    }
    var removeMetaAiEntryPoints by remember(targetEpoch) {
        mutableStateOf(preferenceManager.getBoolean("metaai_remove_entry_points", true))
    }
    var blockMetaAiChat by remember(targetEpoch) {
        mutableStateOf(preferenceManager.getBoolean("metaai_block_chat", true))
    }
    var showTimestampFormatSheet by remember { mutableStateOf(false) }
    var showModifyTextSheet by remember { mutableStateOf(false) }
    var timestampFormat by remember(targetEpoch, typedValueRevision) {
        mutableStateOf(preferenceManager.getString("timestamp_format", "HH:mm a") ?: "HH:mm a")
    }
    var timestampCustomPattern by remember(targetEpoch, typedValueRevision) {
        mutableStateOf(preferenceManager.getString("timestamp_custom_pattern", "HH:mm a") ?: "HH:mm a")
    }
    var timestampCustomText by remember(targetEpoch, typedValueRevision) {
        mutableStateOf(preferenceManager.getString("timestamp_custom_text", "\${t}") ?: "\${t}")
    }
    val stylesKeys = remember {
        listOf(
            "bubble_colors", "custom_theme", "custom_time_format", "custom_toolbar",
            "custom_view_dpi", "seen_tick_customization", "floating_bottom_bar",
            "unlock_premium_customization", "custom_filters", "custom_ab_props",
            "dotonline", "showonlinetext", "typing_online_toasts",
            "metaai", "channels_enhancements", "chat_filters", "hide_ui_tabs",
            "instagram_status_layout", "native_igstatus", "status_style", "separate_groups_tabs", "quick_home_menu",
            "contact_blocked_verify",
            "disable_sensor_proximity"
        )
    }

    val settingsState = remember(targetEpoch) {
        mutableStateMapOf<String, Boolean>().apply {
            stylesKeys.forEach { key ->
                if (StyleNonBooleanPreferencePolicy.requiresDedicatedEditor(key)) {
                    // Do not decode a STRING or STRING_SET using getBoolean().
                    put(key, false)
                } else if (key == "seen_tick_customization") {
                    // The receipt hooks read the canonical STRING 'seentick' (0/1/2),
                    // not the old UI-only Boolean 'seen_tick_customization' flag.
                    put(key, ManualSeenTickPreferencePolicy.isEnabled(
                        preferenceManager.getString("seentick", "0")
                    ))
                } else {
                    val defaultValue = key == "native_igstatus"
                    val canonicalKey = StyleBooleanAliasPolicy.canonicalKey(key)
                    val legacyDefault = if (canonicalKey == key) defaultValue
                        else preferenceManager.getBoolean(key, defaultValue)
                    put(key, preferenceManager.getBoolean(canonicalKey, legacyDefault))
                }
            }
        }
    }

    val updatePreference: (String, Boolean) -> Unit = update@{ key, value ->
        if (StyleNonBooleanPreferencePolicy.requiresDedicatedEditor(key)) return@update
        if (key == "seen_tick_customization") {
            val currentPlacement = preferenceManager.getString("seentick", "0")
            val nextPlacement = ManualSeenTickPreferencePolicy.valueAfterExplicitToggle(value, currentPlacement)
            if (preferenceManager.putPublicString("seentick", nextPlacement)) {
                settingsState[key] = ManualSeenTickPreferencePolicy.isEnabled(nextPlacement)
            } else {
                // A failed commit can already have changed memory. Keep the last confirmed UI state.
                Toast.makeText(context, "Unable to save setting", Toast.LENGTH_SHORT).show()
            }
            return@update
        }
        val canonicalKey = StyleBooleanAliasPolicy.canonicalKey(key)
        val changes = mapOf(canonicalKey to value)
        if (preferenceManager.putBooleans(changes)) {
            settingsState[key] = preferenceManager.getBoolean(canonicalKey, value)
        } else {
            // Failed commits must not appear as successful changes in the UI.
            Toast.makeText(context, UiText.get(R.string.waex_ui_unable_to_save_setting, "Unable to save setting"), Toast.LENGTH_SHORT).show()
        }
    }


    val isPro = LocalIsPro.current
    val onActivatePro = LocalOnActivatePro.current

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Tab switcher header
        item(key = "tab_switcher") {
        Surface(
            shape = radius.mdShape,
            color = colors.surfaceDim,
            border = BorderStroke(1.dp, colors.outlineVariant),
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
                    listOf("appearance" to UiText.get(R.string.waex_ui_appearance, "Appearance"), "layout" to UiText.get(R.string.waex_ui_home_feed, "Home & Feed")).forEach { (tabId, label) ->
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
                                    interactionSource = remember { MutableInteractionSource() },
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
        }

        // Render Style Groups
        val activeGroups = if (selectedTab == "appearance") appearanceGroups else layoutGroups
        activeGroups.forEach { group ->
            val isExpanded = expandedSections[group.title] ?: true
            stickyHeader(key = "header_${group.title}") {
                WaexCategoryHeader(
                    title = LocalizationHelper.getLocalizedSectionTitle(context, group.title),
                    icon = group.icon,
                    isExpanded = isExpanded,
                    onToggleExpand = {
                        expandedSections[group.title] = !isExpanded
                    }
                )
            }

            item(key = "card_${group.title}") {
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically(tween(250)) + fadeIn(tween(250)),
                    exit = shrinkVertically(tween(250)) + fadeOut(tween(200))
                ) {
                    Surface(
                    shape = radius.cardShape,
                    color = colors.surfaceDim,
                    border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.pageMargin)
                ) {
                    Column {
                        group.items.forEachIndexed { index, item ->
                            val isLocked = item.isPro && !isPro
                            val needsTypedEditor = StyleNonBooleanPreferencePolicy.requiresDedicatedEditor(item.key)
                            val isHighlighted = navController.highlightTargetKey == item.key
                            val bringIntoViewRequester = remember { BringIntoViewRequester() }
                            if (isHighlighted) {
                                LaunchedEffect(Unit) {
                                    delay(200)
                                    bringIntoViewRequester.bringIntoView()
                                }
                            }
                            val highlightBgColor by animateColorAsState(
                                targetValue = if (isHighlighted) colors.primary.copy(alpha = 0.15f) else Color.Transparent,
                                animationSpec = tween(durationMillis = 300),
                                label = "highlight_bg"
                            )
                            val locTitle = LocalizationHelper.getTitle(context, item)
                            val locDesc = LocalizationHelper.getDesc(context, item)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .bringIntoViewRequester(bringIntoViewRequester)
                                    .background(highlightBgColor)
                                    .clickable {
                                        if (isLocked) {
                                            onActivatePro()
                                        } else if (needsTypedEditor) {
                                            typedEditorKey = item.key
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
                                            text = locTitle,
                                            style = typography.bodyLg,
                                            fontWeight = FontWeight.Medium,
                                            color = colors.onSurface
                                        )
                                        val reqPro = stringResource(R.string.requires_pro_label)
                                        Text(
                                            text = if (needsTypedEditor) "$locDesc • ${typedStyleSummary(item.key, preferenceManager, typedValueRevision)}"
                                                else if (isLocked) "$locDesc • $reqPro" else locDesc,
                                            style = typography.bodyMd,
                                            color = colors.onSurfaceVariant,
                                            fontSize = 12.sp
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (item.key == "floating_bottom_bar") {
                                            IconButton(
                                                onClick = {
                                                    navController.navigateTo(Screen.FloatingBottomBarCustomizer)
                                                },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = WaexIcons.Settings,
                                                    contentDescription = UiText.get(R.string.waex_ui_customize_floating_bottom_bar, "Customize Floating Bottom Bar"),
                                                    tint = colors.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        if (item.key == "custom_filters") {
                                            IconButton(
                                                onClick = {
                                                    navController.navigateTo(Screen.FilterItems)
                                                },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = WaexIcons.Settings,
                                                    contentDescription = UiText.get(R.string.waex_ui_configure_filter_items, "Configure Filter Items"),
                                                    tint = colors.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        if (item.key == "custom_ab_props") {
                                            IconButton(
                                                onClick = { navController.navigateTo(Screen.CustomAbProps) },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = WaexIcons.Settings,
                                                    contentDescription = "Configure Custom AB Properties",
                                                    tint = colors.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        if (needsTypedEditor) {
                                            Icon(
                                                imageVector = WaexIcons.ChevronRight,
                                                contentDescription = UiText.get(R.string.waex_ui_configure_1_s, "Configure %1\$s", locTitle),
                                                tint = colors.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        } else {
                                            StitchSwitch(
                                                checked = if (isLocked) false else (settingsState[item.key] ?: false),
                                                onCheckedChange = if (isLocked) null else { isChecked -> updatePreference(item.key, isChecked) },
                                                enabled = !isLocked
                                            )
                                        }
                                    }
                                }
                                if (item.isPro) {
                                    WaexProChip(
                                        isUnlocked = isPro,
                                        modifier = Modifier.align(Alignment.TopEnd)
                                    )
                                }
                            }
                            if (item.key == "metaai" && settingsState[item.key] == true) {
                                MetaAiRemovalSwitch(
                                    title = stringResource(R.string.meta_ai_remove_home_fab),
                                    checked = removeMetaAiHomeFab,
                                    onCheckedChange = { enabled ->
                                        if (preferenceManager.putBooleans(mapOf("metaai_remove_home_fab" to enabled))) {
                                            removeMetaAiHomeFab = enabled
                                        }
                                    }
                                )
                                MetaAiRemovalSwitch(
                                    title = stringResource(R.string.meta_ai_remove_entry_points),
                                    checked = removeMetaAiEntryPoints,
                                    onCheckedChange = { enabled ->
                                        if (preferenceManager.putBooleans(mapOf("metaai_remove_entry_points" to enabled))) {
                                            removeMetaAiEntryPoints = enabled
                                        }
                                    }
                                )
                                MetaAiRemovalSwitch(
                                    title = stringResource(R.string.meta_ai_block_chat),
                                    checked = blockMetaAiChat,
                                    onCheckedChange = { enabled ->
                                        if (preferenceManager.putBooleans(mapOf("metaai_block_chat" to enabled))) {
                                            blockMetaAiChat = enabled
                                        }
                                    }
                                )
                            }
                            if (item.key == "custom_time_format" && (settingsState[item.key] == true)) {
                                HorizontalDivider(
                                    thickness = 1.dp,
                                    color = colors.outlineVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.padding(start = 32.dp)
                                )
                                // Sub-pref 1: Timestamp format
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showTimestampFormatSheet = true }
                                        .padding(start = 32.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = UiText.get(R.string.timestamp_format_title, "Timestamp format"),
                                            style = typography.bodyLg,
                                            fontWeight = FontWeight.Medium,
                                            color = colors.onSurface
                                        )
                                        Text(
                                            text = if (timestampFormat == "Custom Pattern") {
                                                if (timestampCustomPattern.isNotBlank()) "Custom Pattern ($timestampCustomPattern)" else "Custom Pattern"
                                            } else {
                                                timestampFormat
                                            },
                                            style = typography.bodyMd,
                                            color = colors.onSurfaceVariant,
                                            fontSize = 12.sp
                                        )
                                    }
                                    Icon(
                                        imageVector = WaexIcons.ChevronRight,
                                        contentDescription = UiText.get(R.string.waex_ui_configure_1_s, "Configure %1\$s",
                                            UiText.get(R.string.timestamp_format_title, "Timestamp format")),
                                        tint = colors.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // Custom pattern input field appears right after the format pref when Custom Pattern is selected
                                if (timestampFormat == "Custom Pattern") {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 32.dp, end = 16.dp, bottom = 12.dp)
                                    ) {
                                        var patternInput by remember(timestampCustomPattern) { mutableStateOf(timestampCustomPattern) }
                                        val isPatternValid = remember(patternInput) {
                                            if (patternInput.isBlank()) false else try {
                                                DateTimeFormatter.ofPattern(patternInput.trim())
                                                true
                                            } catch (t: Throwable) {
                                                false
                                            }
                                        }
                                        OutlinedTextField(
                                            value = patternInput,
                                            onValueChange = { input ->
                                                patternInput = input
                                                if (input.isNotBlank()) {
                                                    try {
                                                        DateTimeFormatter.ofPattern(input.trim())
                                                        preferenceManager.putPublicString("timestamp_custom_pattern", input.trim())
                                                        timestampCustomPattern = input.trim()
                                                    } catch (ignored: Throwable) {}
                                                }
                                            },
                                            label = { Text(UiText.get(R.string.timestamp_custom_pattern_label, "Custom Pattern")) },
                                            placeholder = { Text(UiText.get(R.string.timestamp_custom_pattern_hint, "e.g. HH:mm a, dd/MM HH:mm:ss")) },
                                            isError = !isPatternValid && patternInput.isNotEmpty(),
                                            supportingText = {
                                                if (!isPatternValid && patternInput.isNotEmpty()) {
                                                    Text(UiText.get(R.string.timestamp_invalid_pattern, "Invalid date/time pattern"), color = colors.error)
                                                } else if (isPatternValid) {
                                                    val sample = remember(patternInput) {
                                                        try {
                                                            DateTimeFormatter.ofPattern(patternInput.trim(), Locale.getDefault())
                                                                .withZone(ZoneId.systemDefault())
                                                                .format(Instant.now())
                                                        } catch (e: Throwable) { "" }
                                                    }
                                                    if (sample.isNotEmpty()) {
                                                        Text("Preview: $sample", color = colors.primary)
                                                    }
                                                }
                                            },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }

                                HorizontalDivider(
                                    thickness = 1.dp,
                                    color = colors.outlineVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.padding(start = 32.dp)
                                )

                                // Sub-pref 2: Modify text
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showModifyTextSheet = true }
                                        .padding(start = 32.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = UiText.get(R.string.timestamp_modify_text_title, "Modify text"),
                                            style = typography.bodyLg,
                                            fontWeight = FontWeight.Medium,
                                            color = colors.onSurface
                                        )
                                        Text(
                                            text = timestampCustomText,
                                            style = typography.bodyMd,
                                            color = colors.onSurfaceVariant,
                                            fontSize = 12.sp
                                        )
                                    }
                                    Icon(
                                        imageVector = WaexIcons.ChevronRight,
                                        contentDescription = UiText.get(R.string.waex_ui_configure_1_s, "Configure %1\$s",
                                            UiText.get(R.string.timestamp_modify_text_title, "Modify text")),
                                        tint = colors.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            if (index < group.items.lastIndex) {
                                HorizontalDivider(thickness = 0.8.dp, color = colors.outlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }
                }
                }
            }
        }
    }

    typedEditorKey?.let { key ->
        TypedStyleEditorDialog(
            key = key,
            preferenceManager = preferenceManager,
            onDismiss = { typedEditorKey = null },
            onSaved = {
                typedValueRevision++
                typedEditorKey = null
            }
        )
    }

    if (showTimestampFormatSheet) {
        ChoiceValueEditorDialog(
            title = UiText.get(R.string.timestamp_format_title, "Timestamp format"),
            selectedValue = timestampFormat,
            choices = listOf(
                "HH:mm a" to "HH:mm a (default)",
                "HH:mm:ss" to "HH:mm:ss",
                "h:mm:ss a" to "h:mm:ss a",
                "HH:mm:ss.SSS" to "HH:mm:ss.SSS",
                "h:mm:ss.SSS a" to "h:mm:ss.SSS a",
                "Custom Pattern" to "Custom Pattern"
            ),
            optionPreview = { value ->
                val pattern = if (value == "Custom Pattern") timestampCustomPattern else value
                formatTimestampPreview(pattern)
            },
            onDismiss = { showTimestampFormatSheet = false },
            onSave = { selected ->
                preferenceManager.putPublicString("timestamp_format", selected)
                timestampFormat = selected
                showTimestampFormatSheet = false
                typedValueRevision++
            }
        )
    }

    if (showModifyTextSheet) {
        ModifyTimestampTextSheet(
            initialText = timestampCustomText,
            selectedFormat = timestampFormat,
            customPattern = timestampCustomPattern,
            onDismiss = { showModifyTextSheet = false },
            onSave = { savedText ->
                preferenceManager.putPublicString("timestamp_custom_text", savedText)
                timestampCustomText = savedText
                showModifyTextSheet = false
                typedValueRevision++
            }
        )
    }

}

@Composable
private fun MetaAiRemovalSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(start = 32.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = typography.bodyMd,
            color = colors.onSurface
        )
        StitchSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TypedStyleEditorDialog(
    key: String,
    preferenceManager: WaexPreferenceManager,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val completeSave: (Boolean) -> Unit = { saved ->
        if (saved) {
            onSaved()
        } else {
            // Keep the editor and its input open so the user can retry.
            Toast.makeText(context, UiText.get(R.string.waex_ui_unable_to_save_setting, "Unable to save setting"), Toast.LENGTH_SHORT).show()
        }
    }
    when (key) {
        "custom_theme" -> TextValueEditorDialog(
            title = UiText.get(R.string.feat_custom_theme_title, "Custom Theme CSS"),
            description = UiText.get(R.string.waex_ui_style_whatsapp_views_using_ids_widget, "Style WhatsApp views using IDs, widget types, scoped activities, and common CSS properties. Restart WhatsApp after saving."),
            initialValue = preferenceManager.getString("css_theme", "") ?: "",
            singleLine = false,
            placeholder = "#conversation_row_date {\n  color: #FFFFFF;\n  font-size: 12sp;\n}",
            validator = ::isValidCustomCss,
            errorMessage = UiText.get(R.string.waex_ui_check_that_every_selector_has_a_matchi, "Check that every selector has a matching { } block"),
            onDismiss = onDismiss,
            onSave = { completeSave(preferenceManager.putPublicString("css_theme", it.trim())) }
        )
        "custom_view_dpi" -> TextValueEditorDialog(
            title = UiText.get(R.string.waex_ui_default_dpi, "Default DPI"),
            description = UiText.get(R.string.waex_ui_enter_0_to_use_the_device_default_or_a, "Enter 0 to use the device default, or a DPI value from 120 to 1000."),
            initialValue = preferenceManager.getString("change_dpi", "0") ?: "0",
            singleLine = true,
            placeholder = "0",
            validator = { value ->
                value.toIntOrNull()?.let { it == 0 || it in 120..1000 } == true
            },
            errorMessage = UiText.get(R.string.waex_ui_enter_0_or_a_number_from_120_to_1000, "Enter 0 or a number from 120 to 1000"),
            onDismiss = onDismiss,
            onSave = { completeSave(preferenceManager.putPublicString("change_dpi", it.trim())) }
        )
        "chat_filters" -> ChoiceValueEditorDialog(
            title = UiText.get(R.string.waex_ui_chat_filter_display_label, "Chat filter display"),
            selectedValue = preferenceManager.getString("chatfilter", "2") ?: "2",
            choices = listOf("0" to UiText.get(R.string.waex_ui_hide_search, "Hide search"), "1" to UiText.get(R.string.waex_ui_search_icon, "Search icon"), "2" to UiText.get(R.string.waex_ui_search_bar, "Search bar")),
            onDismiss = onDismiss,
            onSave = { completeSave(preferenceManager.putPublicString("chatfilter", it)) }
        )
        "quick_home_menu" -> ChoiceValueEditorDialog(
            title = UiText.get(R.string.feat_quick_home_menu_title, "Quick Action Home Menu"),
            selectedValue = preferenceManager.getString("waex_menu_style", "grouped") ?: "grouped",
            choices = listOf(
                "grouped" to UiText.get(R.string.waex_ui_grouped_under_waex, "Grouped under WAEX"),
                "separate" to UiText.get(R.string.waex_ui_separate_menu_items, "Separate menu items"),
                "icons" to UiText.get(R.string.waex_ui_toolbar_icons, "Toolbar icons")
            ),
            onDismiss = onDismiss,
            onSave = { completeSave(preferenceManager.putPublicString("waex_menu_style", it)) }
        )
        "hide_ui_tabs" -> MultiChoiceValueEditorDialog(
            title = UiText.get(R.string.waex_ui_hide_home_tabs_label, "Hide home tabs"),
            selectedValues = preferenceManager.getStringSet("hidetabs", emptySet()) ?: emptySet(),
            choices = listOf(
                "300" to UiText.get(R.string.waex_ui_updates, "Updates"),
                "600" to UiText.get(R.string.waex_ui_communities, "Communities"),
                "400" to UiText.get(R.string.waex_ui_calls, "Calls"),
                "700" to UiText.get(R.string.waex_ui_tools, "Tools")
            ),
            onDismiss = onDismiss,
            onSave = { completeSave(preferenceManager.putPublicStringSet("hidetabs", it)) }
        )
        "status_style" -> ChoiceValueEditorDialog(
            title = UiText.get(R.string.feat_status_style_title, "Stories & Status Style"),
            selectedValue = preferenceManager.getString("status_style", "1") ?: "1",
            choices = listOf(
                "1" to UiText.get(R.string.status_style_default, "Default"),
                "0" to UiText.get(R.string.status_style_vertical_rows, "Vertical Rows"),
                "3" to UiText.get(R.string.status_style_vertical_circle, "Vertical List (Circular Avatars)"),
                "9" to UiText.get(R.string.status_style_vertical_square, "Vertical List (Square Cards)")
            ),
            onDismiss = onDismiss,
            onSave = { completeSave(preferenceManager.putPublicString("status_style", it)) }
        )
        else -> onDismiss()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextValueEditorDialog(
    title: String,
    description: String,
    initialValue: String,
    singleLine: Boolean,
    placeholder: String,
    validator: (String) -> Boolean = { true },
    errorMessage: String = UiText.get(R.string.waex_ui_invalid_value, "Invalid value"),
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember(title, initialValue) { mutableStateOf(initialValue) }
    val valid = validator(value.trim())
    val colors = WaexTheme.colors
    val radius = WaexTheme.radius
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    WaexModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = radius.bottomSheetShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth().then(if (singleLine) Modifier else Modifier.height(220.dp)),
                singleLine = singleLine,
                placeholder = { Text(placeholder) },
                isError = !valid,
                supportingText = if (!valid) ({ Text(errorMessage) }) else null
            )
            Button(
                onClick = { onSave(value) },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = radius.buttonShape
            ) {
                Text(UiText.get(R.string.action_save, "Save"), fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = radius.buttonShape
            ) {
                Text(UiText.get(R.string.action_cancel, "Cancel"))
            }
        }
    }
}

private fun formatTimestampPreview(pattern: String): String {
    return runCatching {
        DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
            .format(Instant.now().atZone(ZoneId.systemDefault()))
    }.getOrElse { "—" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceValueEditorDialog(
    title: String,
    selectedValue: String,
    choices: List<Pair<String, String>>,
    optionPreview: ((String) -> String)? = null,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    var selected by remember(title, selectedValue) { mutableStateOf(selectedValue) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    WaexModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = radius.bottomSheetShape,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(colors.outlineVariant.copy(alpha = 0.6f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            // Title
            Text(
                text = title,
                style = typography.headlineMd,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Radio options
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                choices.forEachIndexed { index, (value, label) ->
                    val isSelected = selected == value
                    val bgColor by animateColorAsState(
                        targetValue = if (isSelected) colors.primary.copy(alpha = 0.10f) else Color.Transparent,
                        animationSpec = tween(200),
                        label = "choice_bg_$index"
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(bgColor)
                            .clickable { selected = value }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { selected = value },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = colors.primary
                            )
                        )
                        Text(
                            text = label,
                            style = typography.bodyLg,
                            color = if (isSelected) colors.primary else colors.onSurface,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 4.dp)
                        )
                        optionPreview?.let { preview ->
                            Text(
                                text = preview(value),
                                style = typography.bodySm,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                        }
                    }
                    if (index < choices.lastIndex) {
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = colors.outlineVariant.copy(alpha = 0.4f),
                            modifier = Modifier.padding(start = 56.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Confirm button
            Button(
                onClick = { onSave(selected) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = radius.fullShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary
                )
            ) {
                Text(text = UiText.get(R.string.action_apply, "Apply"), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Cancel
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = radius.fullShape
            ) {
                Text(
                    text = UiText.get(R.string.action_cancel, "Cancel"),
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MultiChoiceValueEditorDialog(
    title: String,
    selectedValues: Set<String>,
    choices: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val selected = remember(title, selectedValues) { mutableStateListOf<String>().apply { addAll(selectedValues) } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    WaexModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = radius.bottomSheetShape,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(colors.outlineVariant.copy(alpha = 0.6f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            // Title
            Text(
                text = title,
                style = typography.headlineMd,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Checkbox options
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                choices.forEachIndexed { index, (value, label) ->
                    val checked = value in selected
                    val bgColor by animateColorAsState(
                        targetValue = if (checked) colors.primary.copy(alpha = 0.10f) else Color.Transparent,
                        animationSpec = tween(200),
                        label = "multi_choice_bg_$index"
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(bgColor)
                            .clickable {
                                if (checked) selected.remove(value) else selected.add(value)
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { enabled ->
                                if (enabled) { if (value !in selected) selected.add(value) }
                                else selected.remove(value)
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = colors.primary,
                                uncheckedColor = colors.onSurfaceVariant
                            )
                        )
                        Text(
                            text = label,
                            style = typography.bodyLg,
                            color = if (checked) colors.primary else colors.onSurface,
                            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                    if (index < choices.lastIndex) {
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = colors.outlineVariant.copy(alpha = 0.4f),
                            modifier = Modifier.padding(start = 56.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Confirm button
            Button(
                onClick = { onSave(selected.toSet()) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = radius.fullShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary
                )
            ) {
                Text(text = UiText.get(R.string.action_apply, "Apply"), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Cancel
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = radius.fullShape
            ) {
                Text(
                    text = UiText.get(R.string.action_cancel, "Cancel"),
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModifyTimestampTextSheet(
    initialText: String,
    selectedFormat: String,
    customPattern: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var textValue by remember(initialText) { mutableStateOf(initialText.ifEmpty { "\${t}" }) }

    val effectivePattern = remember(selectedFormat, customPattern) {
        if (selectedFormat == "Custom Pattern") {
            customPattern.ifBlank { "HH:mm a" }
        } else {
            selectedFormat.ifBlank { "HH:mm a" }
        }
    }

    val formattedTimestamp = remember(effectivePattern) {
        try {
            DateTimeFormatter.ofPattern(effectivePattern, Locale.getDefault())
                .withZone(ZoneId.systemDefault())
                .format(Instant.now())
        } catch (e: Throwable) {
            try {
                DateTimeFormatter.ofPattern("HH:mm a", Locale.getDefault())
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.now())
            } catch (ignored: Throwable) {
                "12:00 PM"
            }
        }
    }

    val livePreview = remember(textValue, formattedTimestamp) {
        if (textValue.contains("\${t}")) {
            textValue.replace("\${t}", formattedTimestamp)
        } else {
            textValue
        }
    }

    WaexModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = radius.bottomSheetShape,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(colors.outlineVariant.copy(alpha = 0.6f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Instructions on top of sheet
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = UiText.get(R.string.timestamp_modify_text_title, "Modify text"),
                    style = typography.headlineMd,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
                Text(
                    text = UiText.get(
                        R.string.timestamp_modify_text_instructions,
                        "Use \${t} as the timestamp variable according to your selected format. You can add any text before and after \${t}, or enter custom text."
                    ),
                    style = typography.bodyMd,
                    color = colors.onSurfaceVariant
                )
            }

            // Input field
            OutlinedTextField(
                value = textValue,
                onValueChange = { textValue = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                label = { Text("Timestamp Text") },
                placeholder = { Text("\${t}") },
                trailingIcon = {
                    if (!textValue.contains("\${t}")) {
                        TextButton(onClick = {
                            textValue = if (textValue.isEmpty()) "\${t}" else "$textValue \${t}"
                        }) {
                            Text("+\${t}", color = colors.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            )

            // Gap
            Spacer(modifier = Modifier.height(4.dp))

            // Preview under it with a gap
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceContainerHighest.copy(alpha = 0.5f))
                    .border(1.dp, colors.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = UiText.get(R.string.timestamp_modify_text_preview, "Preview"),
                    style = typography.labelLg,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary
                )
                Text(
                    text = livePreview.ifEmpty { "(empty)" },
                    style = typography.bodyLg,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Save button
            Button(
                onClick = { onSave(textValue) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = radius.fullShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary
                )
            ) {
                Text(UiText.get(R.string.action_save, "Save"), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            // Cancel button
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = radius.fullShape
            ) {
                Text(
                    text = UiText.get(R.string.action_cancel, "Cancel"),
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        }
    }
}

private fun isValidCustomCss(css: String): Boolean {
    if (css.isBlank()) return true
    var depth = 0
    var inComment = false
    var index = 0
    while (index < css.length) {
        if (!inComment && index + 1 < css.length && css[index] == '/' && css[index + 1] == '*') {
            inComment = true
            index += 2
            continue
        }
        if (inComment && index + 1 < css.length && css[index] == '*' && css[index + 1] == '/') {
            inComment = false
            index += 2
            continue
        }
        if (!inComment) {
            when (css[index]) {
                '{' -> depth++
                '}' -> if (--depth < 0) return false
            }
        }
        index++
    }
    return !inComment && depth == 0 && css.contains('{')
}

private fun typedStyleSummary(
    key: String,
    preferenceManager: WaexPreferenceManager,
    @Suppress("UNUSED_PARAMETER") revision: Int
): String = when (key) {
    "custom_theme" -> if (preferenceManager.getString("css_theme", "").isNullOrBlank()) UiText.get(R.string.waex_ui_not_configured, "Not configured") else UiText.get(R.string.waex_ui_configured, "Configured")
    "custom_view_dpi" -> preferenceManager.getString("change_dpi", "0")?.let { if (it == "0") UiText.get(R.string.waex_ui_device_default, "Device default") else "$it DPI" } ?: UiText.get(R.string.waex_ui_device_default, "Device default")
    "chat_filters" -> when (preferenceManager.getString("chatfilter", "2")) { "0" -> UiText.get(R.string.waex_ui_search_hidden, "Search hidden"); "1" -> UiText.get(R.string.waex_ui_search_icon, "Search icon"); else -> UiText.get(R.string.waex_ui_search_bar, "Search bar") }
    "hide_ui_tabs" -> preferenceManager.getStringSet("hidetabs", emptySet())?.let { if (it.isEmpty()) UiText.get(R.string.waex_ui_no_tabs_hidden, "No tabs hidden") else UiText.get(R.string.waex_ui_1_s_hidden, "%1\$s hidden", it.size) } ?: UiText.get(R.string.waex_ui_no_tabs_hidden, "No tabs hidden")
    "quick_home_menu" -> when (preferenceManager.getString("waex_menu_style", "grouped")) { "separate" -> UiText.get(R.string.waex_ui_separate_items, "Separate items"); "icons" -> UiText.get(R.string.waex_ui_toolbar_icons, "Toolbar icons"); else -> UiText.get(R.string.waex_ui_grouped, "Grouped") }
    "status_style" -> when (preferenceManager.getString("status_style", "1")) {
        "0" -> UiText.get(R.string.status_style_vertical_rows, "Vertical Rows")
        "3" -> UiText.get(R.string.status_style_vertical_circle, "Vertical List (Circular Avatars)")
        "9" -> UiText.get(R.string.status_style_vertical_square, "Vertical List (Square Cards)")
        else -> UiText.get(R.string.status_style_default, "Default")
    }
    else -> UiText.get(R.string.waex_ui_configure, "Configure")
}

private data class StyleGroup(
    val title: String,
    val icon: ImageVector,
    val items: List<FeatureItem>
)


@Preview(showBackground = true)
@Composable
fun StylesSettingsScreenPreview() {
    WaexTheme {
        StylesSettingsScreen()
    }
}
