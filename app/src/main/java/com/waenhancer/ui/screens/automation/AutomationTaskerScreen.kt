package com.waenhancer.ui.screens.automation

import com.waenhancer.utils.UiText

import com.waenhancer.app.R
import androidx.compose.ui.res.stringResource
import com.waenhancer.ui.components.WaexProChip

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
import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.waenhancer.ui.components.WaexCircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.components.StitchSwitch
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.waenhancer.core.transcription.TranscriptionRequestHandler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.LaunchedEffect
import com.waenhancer.config.FeatureCatalog
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.utils.LocalizationHelper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.vector.ImageVector
import com.waenhancer.ui.navigation.LocalIsPro
import com.waenhancer.ui.navigation.LocalOnActivatePro
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import com.waenhancer.ui.navigation.Screen


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AutomationTaskerScreen(targetEpoch: Int = 0) {
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf("automation") } // "automation" | "ai"

    val navController = LocalWaexNavController.current
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val expandedSections = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(navController.targetSubTabId) {
        val target = navController.targetSubTabId
        if (target == "automation" || target == "ai") {
            selectedTab = target
            navController.targetSubTabId = null
        }
    }

    val preferenceManager = LocalWaexPreferenceManager.current

    // Automation states
    var taskerIntegrationEnabled by remember(targetEpoch) { mutableStateOf(preferenceManager.getBoolean("tasker_integration", false)) }

    // Audio & AI states
    var voiceTranscriptionEnabled by remember(targetEpoch) { mutableStateOf(preferenceManager.getBoolean("voice_transcription", true)) }

    val aiConfigKeys = remember {
        setOf(
            "voice_transcription_auto", "voice_note_transcript_button", "voice_dictation_button",
            "transcription_provider", "assembly_key", "groq_key",
            "custom_transcription_url", "custom_transcription_key", "custom_transcription_model"
        )
    }

    LaunchedEffect(navController.scrollToTargetKey) {
        val target = navController.scrollToTargetKey
        if (target != null) {
            if (target == "tasker_integration") {
                selectedTab = "automation"
                expandedSections["automation"] = true
                delay(120)
                listState.animateScrollToItem(1)
            } else if (target == "voice_transcription") {
                selectedTab = "ai"
                expandedSections["ai_service"] = true
                delay(120)
                listState.animateScrollToItem(1)
            } else if (target in aiConfigKeys) {
                selectedTab = "ai"
                voiceTranscriptionEnabled = true
                expandedSections["ai_service"] = true
                expandedSections["ai_config"] = true
                delay(150)
                listState.animateScrollToItem(3)
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
    var transcriptionProvider by remember(targetEpoch) {
        mutableStateOf(
            preferenceManager.getString("transcription_provider", "Groq")
                ?.let { if (it.equals("Device", ignoreCase = true)) "Local" else it }
        )
    } // "Local" | "Groq" | "AssemblyAI" | "Custom"
    var customUrl by remember(targetEpoch) { mutableStateOf(preferenceManager.getString("custom_transcription_url", "")) }
    var customKey by remember(targetEpoch) { mutableStateOf(preferenceManager.getString("custom_transcription_key", "")) }
    var customModel by remember(targetEpoch) { mutableStateOf(preferenceManager.getString("custom_transcription_model", "")) }
    var autoTranscribe by remember(targetEpoch) { mutableStateOf(preferenceManager.getBoolean("voice_transcription_auto", false)) }
    var transcriptButton by remember(targetEpoch) { mutableStateOf(preferenceManager.getBoolean("voice_note_transcript_button", true)) }
    var dictationButton by remember(targetEpoch) { mutableStateOf(preferenceManager.getBoolean("voice_dictation_button", true)) }
    var showProviderDropdown by remember { mutableStateOf(false) }
    var assemblyKey by remember(targetEpoch) { mutableStateOf(preferenceManager.getString("assembly_key", "")) }
    var connectionStatus by remember { mutableStateOf("idle") } // "idle" | "verifying" | "ok" | "fail"
    var connectionError by remember { mutableStateOf("") }
    var discoveringModels by remember { mutableStateOf(false) }
    var discoveredModels by remember { mutableStateOf(emptyList<String>()) }
    var showModelDropdown by remember { mutableStateOf(false) }
    var discoveryError by remember { mutableStateOf("") }
    var groqKey by remember(targetEpoch) { mutableStateOf(preferenceManager.getString("groq_key", "")) }


    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Tab Switcher Header
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

                    val activeIndex = if (selectedTab == "automation") 0 else 1
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
                        listOf("automation" to stringResource(R.string.subtab_automation), "ai" to stringResource(R.string.subtab_audio_ai)).forEach { (tabId, label) ->
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

        if (selectedTab == "automation") {
            // Automation Cards
            val cards = listOf(
                AutomationCardData("tasker_integration", WaexIcons.AutoAwesome, taskerIntegrationEnabled, FeatureCatalog.findByKey("tasker_integration")?.isPro ?: false) {
                    taskerIntegrationEnabled = it
                    preferenceManager.putBoolean("tasker_integration", it)
                }
            )

            val isAutomationExpanded = expandedSections["automation"] ?: true
            stickyHeader(key = "header_automation") {
                WaexCategoryHeader(
                    title = stringResource(R.string.subtab_automation),
                    icon = WaexIcons.AutoAwesome,
                    isExpanded = isAutomationExpanded,
                    onToggleExpand = {
                        expandedSections["automation"] = !isAutomationExpanded
                    }
                )
            }

            item(key = "card_automation") {
                AnimatedVisibility(
                    visible = isAutomationExpanded,
                    enter = expandVertically(tween(250)) + fadeIn(tween(250)),
                    exit = shrinkVertically(tween(250)) + fadeOut(tween(200))
                ) {
                    val isPro = LocalIsPro.current
                    val onActivatePro = LocalOnActivatePro.current
                    val card = cards.first()
                    val isLocked = card.isPro && !isPro
                    val isHighlighted = navController.highlightTargetKey == card.key
                    val bringIntoViewRequester = remember { BringIntoViewRequester() }
                    if (isHighlighted) {
                        LaunchedEffect(Unit) {
                            delay(200)
                            bringIntoViewRequester.bringIntoView()
                        }
                    }
                    val highlightBgColor by animateColorAsState(
                        targetValue = if (isHighlighted) colors.primary.copy(alpha = 0.15f) else colors.surfaceDim,
                        animationSpec = tween(durationMillis = 300),
                        label = "highlight_bg"
                    )
                    Surface(
                        shape = radius.cardShape,
                        color = highlightBgColor,
                        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .bringIntoViewRequester(bringIntoViewRequester)
                            .padding(horizontal = spacing.pageMargin)
                            .clickable(enabled = isLocked) {
                                onActivatePro()
                            }
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                                    .then(if (isLocked) Modifier.alpha(0.6f) else Modifier),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(radius.defaultShape)
                                        .background(if (card.enabled && !isLocked) colors.primaryContainer else Color(0xFFF0F0F2)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = card.icon,
                                        contentDescription = null,
                                        tint = if (card.enabled && !isLocked) colors.primary else colors.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                val item = FeatureCatalog.findByKey(card.key)
                                val locTitle = if (item != null) LocalizationHelper.getTitle(context, item) else LocalizationHelper.getLocalizedFeatureTitle(context, card.key)
                                val locDesc = if (item != null) LocalizationHelper.getDesc(context, item) else LocalizationHelper.getLocalizedFeatureDesc(context, card.key)
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = locTitle,
                                        style = typography.bodyLg,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface
                                    )
                                    val reqProText = stringResource(R.string.requires_pro_label)
                                    Text(
                                        text = if (isLocked) "$locDesc • $reqProText" else locDesc,
                                        style = typography.bodyMd,
                                        color = colors.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                                StitchSwitch(
                                    checked = if (isLocked) false else card.enabled,
                                    onCheckedChange = if (isLocked) null else card.onCheckedChange,
                                    enabled = !isLocked
                                )
                            }
                            if (card.isPro) {
                                WaexProChip(
                                    isUnlocked = isPro,
                                    modifier = Modifier.align(Alignment.TopEnd)
                                )
                            }
                        }
                    }
                }
            }

            if (taskerIntegrationEnabled) {
                val isTaskerToolsExpanded = expandedSections["tasker_tools"] ?: true
                stickyHeader(key = "header_tasker_tools") {
                    WaexCategoryHeader(
                        title = stringResource(R.string.tasker_integration_box_title),
                        icon = WaexIcons.Extension,
                        isExpanded = isTaskerToolsExpanded,
                        onToggleExpand = {
                            expandedSections["tasker_tools"] = !isTaskerToolsExpanded
                        }
                    )
                }

                item(key = "card_tasker_tools") {
                    AnimatedVisibility(
                        visible = isTaskerToolsExpanded,
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
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.tasker_integration_box_title),
                                    style = typography.bodyLg,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.primary
                                )
                                Text(
                                    text = stringResource(R.string.tasker_integration_box_desc),
                                    style = typography.bodyMd,
                                    color = colors.onSurfaceVariant,
                                    fontSize = 12.sp
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(radius.defaultShape)
                                            .background(colors.primary.copy(alpha = 0.1f))
                                            .clickable { navController.navigateTo(Screen.TaskerGuide) }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(R.string.tasker_view_setup_guide),
                                            style = typography.labelSm,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.primary
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(radius.defaultShape)
                                            .background(colors.surface)
                                            .border(1.dp, colors.outlineVariant, radius.defaultShape)
                                            .clickable { navController.navigateTo(Screen.TaskerHistory) }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(R.string.tasker_execution_history),
                                            style = typography.labelSm,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }


        } else {
            // Audio & AI Tab Content
            val isAiServiceExpanded = expandedSections["ai_service"] ?: true
            stickyHeader(key = "header_ai_service") {
                WaexCategoryHeader(
                    title = UiText.get(R.string.feat_voice_transcription_title, "Audio Transcription"),
                    icon = WaexIcons.Mic,
                    isExpanded = isAiServiceExpanded,
                    onToggleExpand = {
                        expandedSections["ai_service"] = !isAiServiceExpanded
                    }
                )
            }

            item(key = "card_ai_master") {
                AnimatedVisibility(
                    visible = isAiServiceExpanded,
                    enter = expandVertically(tween(250)) + fadeIn(tween(250)),
                    exit = shrinkVertically(tween(250)) + fadeOut(tween(200))
                ) {
                    val isHighlighted = navController.highlightTargetKey == "voice_transcription"
                    val bringIntoViewRequester = remember { BringIntoViewRequester() }
                    if (isHighlighted) {
                        LaunchedEffect(Unit) {
                            delay(200)
                            bringIntoViewRequester.bringIntoView()
                        }
                    }
                    val highlightBgColor by animateColorAsState(
                        targetValue = if (isHighlighted) colors.primary.copy(alpha = 0.15f) else colors.surfaceDim,
                        animationSpec = tween(durationMillis = 300),
                        label = "highlight_bg"
                    )

                    // Voice Transcription Master Toggle Card
                    Surface(
                        shape = radius.cardShape,
                        color = highlightBgColor,
                        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .bringIntoViewRequester(bringIntoViewRequester)
                            .padding(horizontal = spacing.pageMargin)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(radius.defaultShape)
                                    .background(if (voiceTranscriptionEnabled) colors.primaryContainer else Color(0xFFF0F0F2)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = WaexIcons.Mic,
                                    contentDescription = null,
                                    tint = if (voiceTranscriptionEnabled) colors.primary else colors.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = UiText.get(R.string.feat_voice_transcription_title, "Audio Transcription"),
                                    style = typography.bodyLg,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSurface
                                )
                                Text(
                                    text = UiText.get(R.string.feat_voice_transcription_desc, "Enable WhatsApp audio transcription with Groq AI or AssemblyAI"),
                                    style = typography.bodyMd,
                                    color = colors.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                            StitchSwitch(
                                checked = voiceTranscriptionEnabled,
                                onCheckedChange = {
                                    voiceTranscriptionEnabled = it
                                    preferenceManager.putBoolean("voice_transcription", it)
                                }
                            )
                        }
                    }
                }
            }

            // AI Setup Parameters
            if (voiceTranscriptionEnabled) {
                val isAiConfigExpanded = expandedSections["ai_config"] ?: true
                stickyHeader(key = "header_ai_config") {
                    WaexCategoryHeader(
                        title = UiText.get(R.string.waex_ui_ai_transcription_provider, "AI Engine Configuration"),
                        icon = WaexIcons.Settings,
                        isExpanded = isAiConfigExpanded,
                        onToggleExpand = {
                            expandedSections["ai_config"] = !isAiConfigExpanded
                        }
                    )
                }

                item(key = "card_ai_config") {
                    AnimatedVisibility(
                        visible = isAiConfigExpanded,
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
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = UiText.get(R.string.waex_ui_transcribe_automatically, "Transcribe automatically"),
                                        style = typography.bodyMd,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = UiText.get(R.string.waex_ui_off_each_new_voice_note_sent_or_receiv, "Off: each new voice note, sent or received, gets a notification with a Transcribe button."),
                                        style = typography.labelSm,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                StitchSwitch(
                                    checked = autoTranscribe,
                                    onCheckedChange = {
                                        autoTranscribe = it
                                        preferenceManager.putBoolean("voice_transcription_auto", it)
                                    }
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = UiText.get(R.string.waex_ui_transcript_button_on_voice_notes, "Transcript button on voice notes"),
                                        style = typography.bodyMd,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = "Adds a CC icon next to the timestamp on each voice note bubble. Tap the CC icon to transcribe that message.",
                                        style = typography.labelSm,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                StitchSwitch(
                                    checked = transcriptButton,
                                    onCheckedChange = {
                                        transcriptButton = it
                                        preferenceManager.putBoolean("voice_note_transcript_button", it)
                                    }
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = UiText.get(R.string.waex_ui_dictation_button, "Dictation button [Experimental]"),
                                        style = typography.bodyMd,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = "Adds a 🎙️ to the message text field. Tap to record speech, tap again to insert it as text.",
                                        style = typography.labelSm,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                StitchSwitch(
                                    checked = dictationButton,
                                    onCheckedChange = {
                                        dictationButton = it
                                        preferenceManager.putBoolean("voice_dictation_button", it)
                                    }
                                )
                            }


                            // Provider Segmented / Pill selection
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = UiText.get(R.string.waex_ui_ai_transcription_provider, "AI Transcription Engine"),
                                    style = typography.labelSm,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(
                                        "Local" to "Device",
                                        "Groq" to "Groq",
                                        "AssemblyAI" to "Assembly",
                                        "Custom" to "Custom"
                                    ).forEach { (provider, label) ->
                                        val isSelected = transcriptionProvider == provider
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isSelected) colors.primary else colors.surfaceContainerLow,
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.dp,
                                                if (isSelected) colors.primary else colors.outlineVariant.copy(alpha = 0.5f)
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(38.dp)
                                                .clickable {
                                                    transcriptionProvider = provider
                                                    preferenceManager.putString("transcription_provider", provider)
                                                    connectionStatus = "idle"
                                                }
                                        ) {
                                            Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = label,
                                                    style = typography.labelSm,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (isSelected) colors.onPrimary else colors.onSurface,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Dynamic API Key entry based on selected provider
                            if (transcriptionProvider == "Groq") {
                                Column {
                                    Text(
                                        text = UiText.get(R.string.waex_ui_groq_api_key, "Groq API Key"),
                                        style = typography.labelSm,
                                        color = colors.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(44.dp)
                                            .clip(radius.mdShape)
                                            .background(colors.surface)
                                            .border(1.dp, colors.outlineVariant, radius.mdShape)
                                            .padding(horizontal = 12.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        BasicTextField(
                                            value = groqKey,
                                            onValueChange = {
                                                groqKey = it
                                                preferenceManager.putString("groq_key", it)
                                                connectionStatus = "idle"
                                            },
                                            singleLine = true,
                                            visualTransformation = PasswordVisualTransformation(),
                                            textStyle = typography.bodyMd.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 13.sp,
                                                color = colors.onSurface
                                            ),
                                            cursorBrush = SolidColor(colors.primary),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            } else if (transcriptionProvider == "Local") {
                                LocalModelsSection()
                            } else if (transcriptionProvider == "Custom") {
                                Text(
                                    text = UiText.get(R.string.waex_ui_uses_the_openai_audio_transcriptions_r, "Uses the OpenAI /audio/transcriptions request format."),
                                    style = typography.labelSm,
                                    color = colors.onSurfaceVariant
                                )
                                TranscriptionTextField(
                                    label = UiText.get(R.string.waex_ui_custom_endpoint_url, "Custom Endpoint URL"),
                                    value = customUrl,
                                    onValueChange = {
                                        customUrl = it
                                        preferenceManager.putString("custom_transcription_url", it.trim())
                                        connectionStatus = "idle"
                                    }
                                )
                                TranscriptionTextField(
                                    label = UiText.get(R.string.waex_ui_custom_api_key, "Custom API Key"),
                                    value = customKey,
                                    onValueChange = {
                                        customKey = it
                                        preferenceManager.putString("custom_transcription_key", it)
                                        connectionStatus = "idle"
                                    },
                                    isSecret = true
                                )
                                TranscriptionTextField(
                                    label = UiText.get(R.string.waex_ui_model_name, "Model Name"),
                                    value = customModel,
                                    onValueChange = {
                                        customModel = it
                                        preferenceManager.putString("custom_transcription_model", it.trim())
                                    }
                                )
                                // Model discovery: GET <base>/models with the saved URL and key.
                                Box {
                                    TextButton(
                                        onClick = {
                                            discoveringModels = true
                                            discoveryError = ""
                                            coroutineScope.launch {
                                                val result = withContext(Dispatchers.IO) {
                                                    runCatching { TranscriptionRequestHandler.listCustomModels(context.applicationContext) }
                                                }
                                                discoveringModels = false
                                                result.onSuccess {
                                                    discoveredModels = it
                                                    showModelDropdown = it.isNotEmpty()
                                                }.onFailure { discoveryError = it.message ?: "Failed" }
                                            }
                                        },
                                        enabled = !discoveringModels && customUrl.isNotBlank()
                                    ) {
                                        Text(
                                            if (discoveringModels) UiText.get(R.string.waex_ui_searching, "Searching…")
                                            else UiText.get(R.string.waex_ui_find_models, "Find models")
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showModelDropdown,
                                        onDismissRequest = { showModelDropdown = false }
                                    ) {
                                        discoveredModels.forEach { model ->
                                            DropdownMenuItem(
                                                text = { Text(text = model, style = typography.bodyMd) },
                                                onClick = {
                                                    customModel = model
                                                    preferenceManager.putString("custom_transcription_model", model)
                                                    showModelDropdown = false
                                                }
                                            )
                                        }
                                    }
                                }
                                if (discoveryError.isNotEmpty()) {
                                    Text(text = discoveryError, style = typography.labelSm, color = colors.error)
                                }
                            } else {
                                Column {
                                    Text(
                                        text = UiText.get(R.string.waex_ui_assemblyai_api_key, "AssemblyAI API Key"),
                                        style = typography.labelSm,
                                        color = colors.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(44.dp)
                                            .clip(radius.mdShape)
                                            .background(colors.surface)
                                            .border(1.dp, colors.outlineVariant, radius.mdShape)
                                            .padding(horizontal = 12.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        BasicTextField(
                                            value = assemblyKey,
                                            onValueChange = {
                                                assemblyKey = it
                                                connectionStatus = "idle"
                                                preferenceManager.putString("assembly_key", it)
                                            },
                                            singleLine = true,
                                            visualTransformation = PasswordVisualTransformation(),
                                            textStyle = typography.bodyMd.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 13.sp,
                                                color = colors.onSurface
                                            ),
                                            cursorBrush = SolidColor(colors.primary),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }

                            // Connection test: sends one second of silence with the current settings
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(text = UiText.get(R.string.waex_ui_connection_status, "Connection Status"), style = typography.bodyMd, color = colors.onSurfaceVariant)
                                    when (connectionStatus) {
                                        "ok" -> {
                                            Icon(
                                                imageVector = WaexIcons.Success,
                                                contentDescription = null,
                                                tint = colors.success,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Text(text = UiText.get(R.string.waex_ui_connected, "Connected"), style = typography.labelSm, color = colors.success, fontWeight = FontWeight.Bold)
                                        }
                                        "fail" -> Text(text = UiText.get(R.string.waex_ui_connection_failed, "Connection failed"), style = typography.labelSm, color = colors.error, fontWeight = FontWeight.Bold)
                                        "verifying" -> WaexCircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = colors.primary)
                                    }
                                }
                                Button(
                                    onClick = {
                                        connectionStatus = "verifying"
                                        connectionError = ""
                                        coroutineScope.launch {
                                            val error = withContext(Dispatchers.IO) {
                                                TranscriptionRequestHandler.testConnection(context.applicationContext)
                                            }
                                            connectionStatus = if (error == null) "ok" else "fail"
                                            connectionError = error ?: ""
                                        }
                                    },
                                    enabled = connectionStatus != "verifying",
                                    shape = radius.mdShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colors.primary,
                                        contentColor = colors.onPrimary
                                    ),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Text(text = UiText.get(R.string.waex_ui_test, "Test"), style = typography.bodyMd)
                                }
                            }
                            if (connectionStatus == "fail" && connectionError.isNotEmpty()) {
                                Text(text = connectionError, style = typography.labelSm, color = colors.error)
                            }

                            TranscriptionLogSection()
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptionTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isSecret: Boolean = false
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    Column {
        Text(
            text = label,
            style = typography.labelSm,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(radius.mdShape)
                .background(colors.surface)
                .border(1.dp, colors.outlineVariant, radius.mdShape)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
                textStyle = typography.bodyMd.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = colors.onSurface
                ),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private data class AutomationCardData(
    val key: String,
    val icon: ImageVector,
    val enabled: Boolean,
    val isPro: Boolean,
    val onCheckedChange: (Boolean) -> Unit
)


@Preview(showBackground = true)
@Composable
fun AutomationTaskerScreenPreview() {
    WaexTheme {
        AutomationTaskerScreen()
    }

}
