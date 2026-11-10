package com.waenhancer.ui.screens.privacy

import com.waenhancer.utils.UiText
import com.waenhancer.utils.EmojiInputPolicy

import android.graphics.Color as AndroidColor
import android.widget.Toast
import com.waenhancer.ui.components.WaexProChip

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import com.waenhancer.ui.components.WaexCategoryHeader
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.mutableStateMapOf
import com.waenhancer.config.FeatureCatalog
import com.waenhancer.config.DndPreferencePolicy
import com.waenhancer.config.FeatureItem
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.utils.LocalizationHelper
import kotlinx.coroutines.delay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.res.stringResource
import com.waenhancer.app.R
import com.waenhancer.ui.components.WaexColorPickerDialog
import com.waenhancer.ui.components.WaexEmojiPickerDialog
import com.waenhancer.ui.navigation.LocalIsPro
import com.waenhancer.ui.navigation.LocalOnActivatePro
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import com.waenhancer.ui.navigation.Screen
import com.waenhancer.ui.screens.privacy.DeletedIndicatorPreferencePolicy
import com.waenhancer.ui.screens.media.MediaQuickSettingPolicy


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlobalPrivacySettingsScreen(
    onOpenModal: (String) -> Unit,
    targetEpoch: Int = 0
) {
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val context = LocalContext.current
    val density = LocalDensity.current

    var selectedTab by remember { mutableStateOf("privacy") } // "privacy" | "conversation"

    val navController = LocalWaexNavController.current
    val listState = rememberLazyListState()

    val privacyGroups = remember { FeatureCatalog.groupsFor(1, "privacy").map { SettingGroup(it.id, it.items()) } }
    val conversationGroups = remember { FeatureCatalog.groupsFor(1, "conversation").map { SettingGroup(it.id, it.items()) } }
    val expandedSections = remember { mutableStateMapOf<String, Boolean>() }

    fun getPrivacyGroupIcon(groupId: String): ImageVector = when (groupId) {
        "presence_online_visibility" -> WaexIcons.Security
        "message_delivery_receipts" -> WaexIcons.Success
        "status_privacy" -> WaexIcons.Image
        "message_protection_security" -> WaexIcons.Lock
        "anti_revoke_deletion_defense" -> WaexIcons.Refresh
        "security_vault" -> WaexIcons.Folder
        "home_menu_shortcuts" -> WaexIcons.GridView
        "message_controls" -> WaexIcons.Settings
        "chat_navigation_shortcuts" -> WaexIcons.ChevronRight
        "chat_group_utilities" -> WaexIcons.AutoAwesome
        else -> WaexIcons.Security
    }

    LaunchedEffect(navController.targetSubTabId) {
        val target = navController.targetSubTabId
        if (target == "privacy" || target == "conversation") {
            selectedTab = target
            navController.targetSubTabId = null
        }
    }

    LaunchedEffect(navController.scrollToTargetKey) {
        val target = navController.scrollToTargetKey
        if (target != null) {
            val inConversation = conversationGroups.any { grp -> grp.items.any { it.key == target } }
            val inPrivacy = privacyGroups.any { grp -> grp.items.any { it.key == target } }
            if (inConversation && selectedTab != "conversation") {
                selectedTab = "conversation"
                delay(120)
            } else if (inPrivacy && selectedTab != "privacy") {
                selectedTab = "privacy"
                delay(120)
            }
            delay(80)
            val groups = if (selectedTab == "conversation") conversationGroups else privacyGroups
            val groupIdx = groups.indexOfFirst { grp -> grp.items.any { it.key == target } }
            if (groupIdx != -1) {
                val group = groups[groupIdx]
                expandedSections[group.title] = true
                val itemIdx = group.items.indexOfFirst { it.key == target }.coerceAtLeast(0)
                val cardIndex = if (selectedTab == "privacy") 4 + (groupIdx * 2) else 2 + (groupIdx * 2)
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
    val privacyKeys = remember {
        listOf(
            "always_online", "online_status_indicator", "freeze_last_seen", "freezelastseen",
            "ghostmode_enabled", "ghostmode", "ghostmode_t", "ghostmode_r", "typing_privacy",
            "hide_read_receipts", "hideread_group", "blueonreply", "seentick", "hide_delivery_receipts", "hide_seen_receipts", "hideonceseen",
            "stealth_status_view", "autonext_status", "toast_viewed_status",
            "anti_view_once", "downloadviewonce", "download_view_once",
            "bypass_screenshot_restriction",
            "anti_revoke", "show_as_deleted", "preserve_own_revoke", "antirevokestatus", "antidisappearing", "toastdeleted", "toast_viewed_message",
            "locked_chats_enhancer", "typearchive", "dnd_mode", "hide_chats", "custom_privacy",
            "message_bomber", "anti_edit_messages", "message_device_source", "revokeallmessages", "hide_forwarded_tag", "removeforwardlimit",
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

    var seenTickPlacement by remember {
        val stored = preferenceManager.getString("seentick", "0") ?: "0"
        mutableStateOf(when (stored) {
            "2" -> "2"
            "1", "true" -> "1"
            else -> "0"
        })
    }
    var manualStatusSeen by remember {
        mutableStateOf(preferenceManager.getBoolean("manual_status_seen", false))
    }
    var manualStatusSeenPlacement by remember {
        mutableStateOf(preferenceManager.getString("manual_status_seen_placement", "menu") ?: "menu")
    }

    var archiveOpenViaMenu by remember {
        mutableStateOf(preferenceManager.getBoolean("archive_open_via_menu", true))
    }

    var archiveOpenViaTitleLongPress by remember {
        mutableStateOf(preferenceManager.getBoolean("archive_open_via_title_long_press", true))
    }

    var archiveOpenViaTitleTripleTap by remember {
        mutableStateOf(preferenceManager.getBoolean("archive_open_via_title_triple_tap", false))
    }

    var lockedOpenViaMenu by remember {
        mutableStateOf(preferenceManager.getBoolean("locked_open_via_menu", false))
    }

    var lockedOpenViaTitleLongPress by remember {
        mutableStateOf(preferenceManager.getBoolean("locked_open_via_title_long_press", false))
    }

    var lockedOpenViaTitleTripleTap by remember {
        mutableStateOf(preferenceManager.getBoolean("locked_open_via_title_triple_tap", false))
    }

    fun readViewOnceDownload(): Boolean = MediaQuickSettingPolicy.isEnabled("download_view_once") { key ->
        preferenceManager.getBoolean(key, false)
    }

    val settingsState = remember(preferenceManager, targetEpoch) {
        mutableStateMapOf<String, Boolean>().apply {
            privacyKeys.forEach { key ->
                var boolVal = if (key == "downloadviewonce" || key == "download_view_once") {
                    readViewOnceDownload()
                } else preferenceManager.getBoolean(key, false)
                if (!boolVal && key == "typearchive") {
                    val strVal = preferenceManager.getString("typearchive", "0")
                    boolVal = (strVal != null && strVal != "0" && !strVal.equals("false", ignoreCase = true))
                } else if (!boolVal && key == "seentick") {
                    val strVal = preferenceManager.getString("seentick", "0")
                    boolVal = strVal == "1" || strVal == "2"
                }
                put(key, boolVal)
            }
            put("dnd_mode", DndPreferencePolicy.isEnabled(
                { key -> preferenceManager.getAll().containsKey(key) },
                { key -> preferenceManager.getBoolean(key, false) }
            ))
        }
    }

    // The same setting also appears in Media. Refresh both display keys on return.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, preferenceManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val enabled = readViewOnceDownload()
                settingsState["downloadviewonce"] = enabled
                settingsState["download_view_once"] = enabled
                manualStatusSeen = preferenceManager.getBoolean("manual_status_seen", false)
                manualStatusSeenPlacement = preferenceManager.getString("manual_status_seen_placement", "menu") ?: "menu"
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val updatePreference: (String, Boolean) -> Unit = update@{ key, value ->
        if (key == "downloadviewonce" || key == "download_view_once") {
            if (preferenceManager.putBooleans(MediaQuickSettingPolicy.changesFor(key, value))) {
                val enabled = readViewOnceDownload()
                settingsState["downloadviewonce"] = enabled
                settingsState["download_view_once"] = enabled
            } else {
                Toast.makeText(context, context.getString(R.string.view_once_setting_save_failed), Toast.LENGTH_SHORT).show()
            }
            return@update
        }
        settingsState[key] = value
        if (key == "seentick") {
            seenTickPlacement = if (value) "1" else "0"
            preferenceManager.putString("seentick", seenTickPlacement)
        } else if (key == "typearchive") {
            // typearchive is STRING in PreferenceSchema. Never first write
            // a transient Boolean under the same key and notify observers.
            preferenceManager.putString("typearchive", if (value) "1" else "0")
        } else if (key == "antirevokestatus") {
            // The status Anti-Revoke setting is declared STRING and accepts 1/0.
            preferenceManager.putString("antirevokestatus", if (value) "1" else "0")
        } else {
            preferenceManager.putBoolean(key, value)
        }
        if (key == "locked_chats_enhancer") {
            preferenceManager.putBoolean("lockedchats_enhancer", value)
        }
        if (key == "hide_read_receipts" && !value) {
            settingsState["blueonreply"] = false
            preferenceManager.putBoolean("blueonreply", false)
            settingsState["seentick"] = false
            seenTickPlacement = "0"
            preferenceManager.putString("seentick", "0")
        }
    }

    var showColorPickerDialog by remember { mutableStateOf(false) }
    var deletedMessageColor by remember(preferenceManager, targetEpoch) {
        // The persisted preference has schema type INT; older releases wrote hex text.
        mutableStateOf(
            DeletedIndicatorPreferencePolicy.toColorHex(
                preferenceManager.getAll()["deleted_message_color"]
            )
        )
    }

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
                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
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
                        val tabPrivacyLabel = stringResource(R.string.tab_privacy)
                        val tabConversationLabel = stringResource(R.string.tab_conversation)
                        listOf("privacy" to tabPrivacyLabel, "conversation" to tabConversationLabel).forEach { (tabId, label) ->
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

        // Hubs & Advanced Modules (only on Privacy tab)
        if (selectedTab == "privacy") {
            val isHubsExpanded = expandedSections["hubs"] ?: true
            stickyHeader(key = "header_hubs") {
                WaexCategoryHeader(
                    title = stringResource(R.string.advanced_hubs_vaults),
                    icon = WaexIcons.Security,
                    isExpanded = isHubsExpanded,
                    onToggleExpand = {
                        expandedSections["hubs"] = !isHubsExpanded
                    }
                )
            }

            item(key = "card_hubs") {
                AnimatedVisibility(
                    visible = isHubsExpanded,
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
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // 1. Per Contact Rules Tile
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { navController.navigateTo(Screen.PerContactPrivacyList) }
                                    .padding(horizontal = 18.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                            Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.hub_per_contact_rules_title),
                                        style = typography.bodyLg,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.hub_per_contact_rules_desc),
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
                                color = colors.outlineVariant.copy(alpha = 0.35f),
                                thickness = 0.8.dp,
                                modifier = Modifier.padding(horizontal = 18.dp)
                            )

                            // 2. Deleted Messages Vault Tile
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { navController.navigateTo(Screen.DeletedMessages) }
                                    .padding(horizontal = 18.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.hub_delete_for_me_title),
                                        style = typography.bodyLg,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.hub_delete_for_me_desc),
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
                                color = colors.outlineVariant.copy(alpha = 0.35f),
                                thickness = 0.8.dp,
                                modifier = Modifier.padding(horizontal = 18.dp)
                            )

                            // 3. Calls & Recording Hub Tile
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { navController.navigateTo(Screen.CallRecordingSettings) }
                                    .padding(horizontal = 18.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.hub_calls_recording_title),
                                        style = typography.bodyLg,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.hub_calls_recording_desc),
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
        }


        // Setting Groups
        val activeGroups = if (selectedTab == "privacy") privacyGroups else conversationGroups
        activeGroups.forEach { group ->
            val isExpanded = expandedSections[group.title] ?: true
            stickyHeader(key = "header_${group.title}") {
                WaexCategoryHeader(
                    title = LocalizationHelper.getLocalizedSectionTitle(context, group.title),
                    icon = getPrivacyGroupIcon(group.title),
                    isExpanded = isExpanded,
                    onToggleExpand = {
                        expandedSections[group.title] = !isExpanded
                    }
                )
            }

            item(key = "card_${group.title}") {
                if (isExpanded) {
                    Surface(
                    shape = radius.cardShape,
                    color = colors.surfaceDim,
                    border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.pageMargin)
                ) {
                    val isPro = LocalIsPro.current
                    val onActivatePro = LocalOnActivatePro.current

                    Column {
                        group.items.forEachIndexed { index, item ->
                            val isBlueTickDependent = (
                                    item.key == "blueonreply" ||
                                    item.key == "seentick" ||
                                    item.key == "hide_seen_receipts" ||
                                    item.key == "hideonceseen"
                                )
                            val isHideReadEnabled = settingsState["hide_read_receipts"] ?: false
                            val isDependencyDisabled = isBlueTickDependent && !isHideReadEnabled

                            val isLocked = item.isPro && !isPro
                            val isItemDisabled = isLocked || isDependencyDisabled
                            val isHighlighted = navController.highlightTargetKey == item.key
                            val bringIntoViewRequester = remember { BringIntoViewRequester() }
                            if (isHighlighted) {
                                LaunchedEffect(Unit) {
                                    delay(200)
                                    bringIntoViewRequester.bringIntoView()
                                }
                            }
                            val highlightBgColor = if (isHighlighted) colors.primary.copy(alpha = 0.15f) else Color.Transparent
                            val locTitle = remember(item.key) { LocalizationHelper.getTitle(context, item) }
                            val locDesc = remember(item.key) { LocalizationHelper.getDesc(context, item) }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .bringIntoViewRequester(bringIntoViewRequester)
                                    .background(highlightBgColor)
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
                                            text = locTitle,
                                            style = typography.bodyLg,
                                            fontWeight = FontWeight.Medium,
                                            color = colors.onSurface
                                        )
                                        val reqPro = stringResource(R.string.requires_pro_label)
                                        Text(
                                            text = if (isLocked) "$locDesc • $reqPro" else locDesc,
                                            style = typography.bodyMd,
                                            color = colors.onSurfaceVariant,
                                            fontSize = 12.sp
                                        )
                                        if (isDependencyDisabled) {
                                            val dependencyTitle = LocalizationHelper.getLocalizedFeatureTitle(
                                                context,
                                                "hide_read_receipts"
                                            )
                                            Text(
                                                text = stringResource(
                                                    R.string.requires_setting_enabled,
                                                    dependencyTitle
                                                ),
                                                style = typography.labelSm,
                                                color = colors.onSurfaceVariant,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
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

                            if (item.key == "stealth_status_view" && settingsState["stealth_status_view"] == true) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = colors.surfaceDim.copy(alpha = 0.6f),
                                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(UiText.get(R.string.manual_status_seen_title, "Send status seen manually"), style = typography.bodyMd, fontWeight = FontWeight.Medium, color = colors.onSurface)
                                                Text(UiText.get(R.string.manual_status_seen_desc, "Add a menu action to mark the current status as seen"), style = typography.labelSm, color = colors.onSurfaceVariant)
                                            }
                                            StitchSwitch(checked = manualStatusSeen, onCheckedChange = { checked ->
                                                preferenceManager.putBoolean("manual_status_seen", checked)
                                                manualStatusSeen = preferenceManager.getBoolean("manual_status_seen", false)
                                            })
                                        }
                                    }
                                }
                            }

                            if (item.key == "stealth_status_view" && settingsState["stealth_status_view"] == true && manualStatusSeen) {
                                Surface(shape = RoundedCornerShape(12.dp), color = colors.surfaceDim.copy(alpha = 0.6f),
                                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(UiText.get(R.string.manual_status_seen_placement_title, "SHOW ACTION AS"), style = typography.labelSm, fontWeight = FontWeight.Bold, color = colors.primary)
                                        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surface).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            listOf("menu" to UiText.get(R.string.manual_status_seen_menu, "Menu option"), "icon" to UiText.get(R.string.manual_status_seen_icon, "Status icon")).forEach { (value, label) ->
                                                val selected = manualStatusSeenPlacement == value
                                                Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (selected) colors.primary else Color.Transparent).clickable {
                                                    manualStatusSeenPlacement = value
                                                    preferenceManager.putString("manual_status_seen_placement", value)
                                                }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                                    Text(label, style = typography.labelSm, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (selected) Color.White else colors.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (item.key == "seentick" && settingsState["seentick"] == true) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = colors.surfaceDim.copy(alpha = 0.6f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = UiText.get(R.string.waex_ui_button_placement, "BUTTON PLACEMENT"),
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
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            listOf("1" to UiText.get(R.string.waex_ui_top_icon, "Top icon"), "2" to UiText.get(R.string.waex_ui_menu_option, "Menu option")).forEach { (value, label) ->
                                                val selected = seenTickPlacement == value
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(if (selected) colors.primary else Color.Transparent)
                                                        .clickable {
                                                            seenTickPlacement = value
                                                            preferenceManager.putString("seentick", value)
                                                        }
                                                        .padding(vertical = 8.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = label,
                                                        style = typography.labelSm,
                                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (selected) Color.White else colors.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (item.key == "typearchive" && settingsState["typearchive"] == true) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = colors.surfaceDim.copy(alpha = 0.6f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = UiText.get(R.string.waex_ui_open_archived_chats_uppercase, "OPEN ARCHIVED CHATS"),
                                            style = typography.labelSm,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.primary
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = UiText.get(R.string.waex_ui_show_in_3_dots_waex_menu, "Show in 3-dots / WAEX menu"),
                                                style = typography.bodySm,
                                                color = colors.onSurface,
                                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                                            )
                                            StitchSwitch(
                                                checked = archiveOpenViaMenu,
                                                onCheckedChange = { checked ->
                                                    archiveOpenViaMenu = checked
                                                    preferenceManager.putBoolean("archive_open_via_menu", checked)
                                                    if (checked) {
                                                        lockedOpenViaMenu = false
                                                        preferenceManager.putBoolean("locked_open_via_menu", false)
                                                    }
                                                }
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = UiText.get(R.string.waex_ui_long_press_whatsapp_title, "Long-press WhatsApp title"),
                                                style = typography.bodySm,
                                                color = colors.onSurface,
                                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                                            )
                                            StitchSwitch(
                                                checked = archiveOpenViaTitleLongPress,
                                                onCheckedChange = { checked ->
                                                    archiveOpenViaTitleLongPress = checked
                                                    preferenceManager.putBoolean("archive_open_via_title_long_press", checked)
                                                    if (checked) {
                                                        lockedOpenViaTitleLongPress = false
                                                        preferenceManager.putBoolean("locked_open_via_title_long_press", false)
                                                    }
                                                }
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = UiText.get(R.string.waex_ui_tap_whatsapp_title_3_times, "Tap WhatsApp title 3 times"),
                                                style = typography.bodySm,
                                                color = colors.onSurface,
                                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                                            )
                                            StitchSwitch(
                                                checked = archiveOpenViaTitleTripleTap,
                                                onCheckedChange = { checked ->
                                                    archiveOpenViaTitleTripleTap = checked
                                                    preferenceManager.putBoolean("archive_open_via_title_triple_tap", checked)
                                                    if (checked) {
                                                        lockedOpenViaTitleTripleTap = false
                                                        preferenceManager.putBoolean("locked_open_via_title_triple_tap", false)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            if (item.key == "hide_chats" && settingsState["hide_chats"] == true) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = colors.surfaceDim.copy(alpha = 0.6f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = UiText.get(R.string.waex_ui_open_locked_chats_uppercase, "OPEN LOCKED CHATS"),
                                            style = typography.labelSm,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.primary
                                        )
                                        listOf(
                                            Triple(UiText.get(R.string.waex_ui_show_in_3_dots_waex_menu, "Show in 3-dots / WAEX menu"), lockedOpenViaMenu) { checked: Boolean ->
                                                lockedOpenViaMenu = checked
                                                preferenceManager.putBoolean("locked_open_via_menu", checked)
                                                if (checked) {
                                                    archiveOpenViaMenu = false
                                                    preferenceManager.putBoolean("archive_open_via_menu", false)
                                                }
                                            },
                                            Triple(UiText.get(R.string.waex_ui_long_press_whatsapp_title, "Long-press WhatsApp title"), lockedOpenViaTitleLongPress) { checked: Boolean ->
                                                lockedOpenViaTitleLongPress = checked
                                                preferenceManager.putBoolean("locked_open_via_title_long_press", checked)
                                                if (checked) {
                                                    archiveOpenViaTitleLongPress = false
                                                    preferenceManager.putBoolean("archive_open_via_title_long_press", false)
                                                }
                                            },
                                            Triple(UiText.get(R.string.waex_ui_tap_whatsapp_title_3_times, "Tap WhatsApp title 3 times"), lockedOpenViaTitleTripleTap) { checked: Boolean ->
                                                lockedOpenViaTitleTripleTap = checked
                                                preferenceManager.putBoolean("locked_open_via_title_triple_tap", checked)
                                                if (checked) {
                                                    archiveOpenViaTitleTripleTap = false
                                                    preferenceManager.putBoolean("archive_open_via_title_triple_tap", false)
                                                }
                                            }
                                        ).forEach { (label, checked, onChange) ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = label,
                                                    style = typography.bodySm,
                                                    color = colors.onSurface,
                                                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                                                )
                                                StitchSwitch(
                                                    checked = checked,
                                                    onCheckedChange = onChange
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // If anti_revoke or antirevokestatus is enabled, show indicator choice chips and color sub-preferences
                            if ((item.key == "anti_revoke" && (settingsState["anti_revoke"] == true)) ||
                                (item.key == "antirevokestatus" && (settingsState["antirevokestatus"] == true) && (settingsState["anti_revoke"] != true))) {
                                val currentIndicator = preferenceManager
                                    .getInt("anti_revoke_indicator", 2).toString()
                                val isShowAsDeleted = preferenceManager.getBoolean("show_as_deleted", false)
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
                                        // Section 1: Show as Deleted Message Switch
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(
                                                    text = UiText.get(R.string.waex_ui_show_as_deleted, "Show as Deleted Message"),
                                                    style = typography.bodyMd,
                                                    color = colors.onSurface,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Text(
                                                text = "Coming soon — native deleted-message layout is temporarily disabled",
                                                    style = typography.labelSm,
                                                    color = colors.onSurfaceVariant
                                                )
                                            }
                                            StitchSwitch(
                                                checked = false,
                                                onCheckedChange = null,
                                                enabled = false
                                            )
                                        }

                                        if (!isShowAsDeleted) {
                                            HorizontalDivider(
                                                color = colors.outlineVariant.copy(alpha = 0.5f),
                                                thickness = 0.5.dp
                                            )

                                            // Section 2: Indicator Style with Segmented Control
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(
                                                    text = UiText.get(R.string.waex_ui_indicator_style, "INDICATOR STYLE"),
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
                                                    listOf(
                                                        "2" to UiText.get(R.string.waex_ui_deleted_icon, "Deleted Icon 🚫"),
                                                        "1" to UiText.get(R.string.waex_ui_text_deleted, "Text \"Deleted\"")
                                                    ).forEach { (valKey, title) ->
                                                        val isSelected = currentIndicator == valKey
                                                        Box(
                                                            modifier = Modifier
                                                                .weight(1f)
                                                                .clip(RoundedCornerShape(8.dp))
                                                                .background(if (isSelected) colors.primary else Color.Transparent)
                                                                .clickable {
                                                                    preferenceManager.putInt("anti_revoke_indicator", valKey.toInt())
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

                                            // Section 3: Color Deleted Messages Switch
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                    Text(
                                                        text = UiText.get(R.string.waex_ui_color_deleted_text_indicator, "Color Deleted Text & Indicator"),
                                                        style = typography.bodyMd,
                                                        color = colors.onSurface,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                    Text(
                                                        text = UiText.get(R.string.waex_ui_apply_custom_color_to_deleted_message, "Apply custom color to deleted message/status and time"),
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

                                            // Section 4: Color Picker Trigger (only when Color Deleted Messages is ON)
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
                                                            Color(AndroidColor.parseColor(deletedMessageColor))
                                                        } catch (_: Throwable) {
                                                            colors.error
                                                        }
                                                        Box(
                                                            modifier = Modifier
                                                                .size(24.dp)
                                                                .clip(CircleShape)
                                                                .background(parsedCurrentColor)
                                                                .border(1.5.dp, colors.outline, CircleShape)
                                                        )
                                                        Text(
                                                            text = UiText.get(R.string.waex_ui_indicator_text_color, "Indicator & Text Color"),
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
                                            text = UiText.get(R.string.waex_ui_waenhancerx_settings_placement, "WAENHANCERX SETTINGS PLACEMENT"),
                                            style = typography.labelSm,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.primary
                                        )
                                        Text(
                                            text = UiText.get(R.string.waex_ui_choose_where_to_show_the_waex_settings, "Choose where to show the WAEX settings entry point"),
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
                                                "home_menu" to UiText.get(R.string.waex_ui_home_menu, "Home Menu"),
                                                "wa_settings" to UiText.get(R.string.waex_ui_whatsapp_settings, "WhatsApp Settings")
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
                                            text = UiText.get(R.string.waex_ui_menu_display_options, "MENU DISPLAY OPTIONS"),
                                            style = typography.labelSm,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.primary
                                        )
                                        Text(
                                            text = UiText.get(R.string.waex_ui_how_waex_items_appear_in_whatsapp_s_ho, "How WAEX items appear in WhatsApp's home menu"),
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
                                                "grouped"  to UiText.get(R.string.waex_ui_grouped, "Grouped"),
                                                "separate" to UiText.get(R.string.waex_ui_separate, "Separate"),
                                                "icons"    to UiText.get(R.string.waex_ui_icons, "Icons")
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

                            // Show Custom Reaction Emoji selection when Double Click to React is enabled
                            if (item.key == "doubletap2like" && (settingsState["doubletap2like"] == true)) {
                                var showEmojiPicker by remember { mutableStateOf(false) }
                                var currentEmoji by remember {
                                    mutableStateOf(EmojiInputPolicy.singleEmojiOrDefault(preferenceManager.getString("doubletap2like_emoji", "👍")))
                                }

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = colors.surfaceDim.copy(alpha = 0.6f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { showEmojiPicker = true }
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = UiText.get(R.string.waex_ui_custom_reaction_emoji, "CUSTOM REACTION EMOJI"),
                                                style = typography.labelSm,
                                                fontWeight = FontWeight.Bold,
                                                color = colors.primary
                                            )
                                            Text(
                                                text = UiText.get(R.string.waex_ui_tap_to_change_default_reaction_emoji_1, "Tap to change default reaction emoji (%1\$s)", currentEmoji),
                                                style = typography.bodyMd,
                                                color = colors.onSurfaceVariant,
                                                fontSize = 11.sp
                                            )
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = colors.surface,
                                            modifier = Modifier.padding(start = 8.dp)
                                        ) {
                                            Text(
                                                text = currentEmoji,
                                                style = typography.titleMd,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }

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

    if (showColorPickerDialog) {
        WaexColorPickerDialog(
            initialColorHex = deletedMessageColor,
            onColorSelected = { selectedHex ->
                val colorInt = DeletedIndicatorPreferencePolicy.toArgbOrNull(selectedHex)
                if (colorInt != null) {
                    // Store the schema's INT ARGB form; do not strand hex strings on disk.
                    preferenceManager.putInt("deleted_message_color", colorInt)
                    deletedMessageColor = DeletedIndicatorPreferencePolicy.toColorHex(colorInt)
                    settingsState["anti_revoke_indicator_dummy"] = !(settingsState["anti_revoke_indicator_dummy"] ?: false)
                }
            },
            onDismiss = { showColorPickerDialog = false }
        )
    }
}

private data class SettingGroup(
    val title: String,
    val items: List<FeatureItem>
)


@Preview(showBackground = true)
@Composable
fun GlobalPrivacySettingsScreenPreview() {
    WaexTheme {
        GlobalPrivacySettingsScreen(onOpenModal = {})
    }
}
