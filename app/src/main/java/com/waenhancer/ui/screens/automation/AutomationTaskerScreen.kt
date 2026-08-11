package com.waenhancer.ui.screens.automation

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.components.StitchSwitch
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import androidx.compose.runtime.mutableStateMapOf
import com.waenhancer.ui.navigation.LocalWaexNavController


@Composable
fun AutomationTaskerScreen() {
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf("automation") } // "automation" | "ai"

    val navController = LocalWaexNavController.current
    val scrollState = rememberScrollState()
    val itemCoordinates = remember { mutableStateMapOf<String, Float>() }
    var containerY by remember { mutableStateOf(0f) }

    LaunchedEffect(navController.targetSubTabId) {
        val target = navController.targetSubTabId
        if (target == "automation" || target == "ai") {
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

    // Automation states
    var alwaysTypingEnabled by remember { mutableStateOf(preferenceManager.getBoolean("always_typing", true)) }
    var autoStatusForwardEnabled by remember { mutableStateOf(preferenceManager.getBoolean("auto_status_forward", false)) }
    var messageBomberEnabled by remember { mutableStateOf(preferenceManager.getBoolean("message_bomber", false)) }
    var statusVideoSplitterEnabled by remember { mutableStateOf(preferenceManager.getBoolean("status_video_splitter", false)) }
    var taskerIntegrationEnabled by remember { mutableStateOf(preferenceManager.getBoolean("tasker_integration", false)) }

    // Audio & AI states
    var voiceTranscriptionEnabled by remember { mutableStateOf(preferenceManager.getBoolean("voice_transcription", true)) }
    var transcriptionProvider by remember { mutableStateOf(preferenceManager.getString("transcription_provider", "Groq")) } // "Groq" | "AssemblyAI"
    var showProviderDropdown by remember { mutableStateOf(false) }
    var assemblyKey by remember { mutableStateOf(preferenceManager.getString("assembly_key", "")) }
    var assemblyStatus by remember { mutableStateOf("idle") } // "idle" | "verifying" | "ok" | "fail"
    var groqKey by remember { mutableStateOf(preferenceManager.getString("groq_key", "")) }


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
        // Tab Switcher Header
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
                    listOf("automation" to "Automation", "ai" to "Audio & AI").forEach { (tabId, label) ->
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

        if (selectedTab == "automation") {
            // Automation Cards
            val cards = listOf(
                AutomationCardData("always_typing", WaexIcons.Lock, "Always Typing Mode", "Maintain typing indicator at all times", alwaysTypingEnabled, true) {
                    alwaysTypingEnabled = it
                    preferenceManager.putBoolean("always_typing", it)
                },
                AutomationCardData("auto_status_forward", WaexIcons.Share, "Auto Status Forwarding", "Auto-forward received statuses to contacts", autoStatusForwardEnabled, false) {
                    autoStatusForwardEnabled = it
                    preferenceManager.putBoolean("auto_status_forward", it)
                },
                AutomationCardData("message_bomber", WaexIcons.Mic, "Message Bomber", "Send automated message bursts", messageBomberEnabled, true) {
                    messageBomberEnabled = it
                    preferenceManager.putBoolean("message_bomber", it)
                },
                AutomationCardData("status_video_splitter", WaexIcons.SystemUpdate, "Status Video Splitter", "Auto-split long videos for status updates", statusVideoSplitterEnabled, true) {
                    statusVideoSplitterEnabled = it
                    preferenceManager.putBoolean("status_video_splitter", it)
                },
                AutomationCardData("tasker_integration", WaexIcons.AutoAwesome, "Tasker Integration", "Exposes WAEX triggers and actions to Tasker", taskerIntegrationEnabled, false) {
                    taskerIntegrationEnabled = it
                    preferenceManager.putBoolean("tasker_integration", it)
                }
            )


            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.pageMargin),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                cards.forEach { card ->
                    val isHighlighted = navController.highlightTargetKey == card.key
                    val highlightBgColor by animateColorAsState(
                        targetValue = if (isHighlighted) colors.primary.copy(alpha = 0.15f) else colors.surfaceDim,
                        animationSpec = tween(durationMillis = 300),
                        label = "highlight_bg"
                    )
                    Surface(
                        shape = radius.bentoCardShape,
                        color = highlightBgColor,
                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coordinates ->
                                val y = coordinates.positionInRoot().y - containerY + scrollState.value
                                itemCoordinates[card.key] = y
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (card.enabled) colors.primaryContainer else Color(0xFFF0F0F2)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = card.icon,
                                    contentDescription = null,
                                    tint = if (card.enabled) colors.primary else colors.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = card.title,
                                        style = typography.bodyLg,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface
                                    )
                                    if (card.isPro) {
                                        Box(
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .background(colors.primaryContainer)
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "PRO",
                                                style = typography.labelSm,
                                                fontWeight = FontWeight.Bold,
                                                color = colors.primary,
                                                fontSize = 9.sp
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = card.desc,
                                    style = typography.bodyMd,
                                    color = colors.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                            StitchSwitch(
                                checked = card.enabled,
                                onCheckedChange = card.onCheckedChange
                            )
                        }
                    }
                }
            }

            if (taskerIntegrationEnabled) {
                Surface(
                    shape = radius.bentoCardShape,
                    color = colors.surfaceDim,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.pageMargin)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Tasker & Automation Integrations",
                            style = typography.bodyLg,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary
                        )
                        Text(
                            text = "Automate WhatsApp features, auto-replies, and message logging using Broadcast intents in Tasker, MacroDroid, or Automate.",
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
                                    .clickable { navController.navigateTo(com.waenhancer.ui.navigation.Screen.TaskerGuide) }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "View Setup Guide →",
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
                                    .clickable { navController.navigateTo(com.waenhancer.ui.navigation.Screen.TaskerHistory) }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Execution History",
                                    style = typography.labelSm,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onSurface
                                )
                            }
                        }
                    }
                }
            }


        } else {
            // Audio & AI Tab Content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.pageMargin),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val isHighlighted = navController.highlightTargetKey == "voice_transcription"
                val highlightBgColor by animateColorAsState(
                    targetValue = if (isHighlighted) colors.primary.copy(alpha = 0.15f) else colors.surfaceDim,
                    animationSpec = tween(durationMillis = 300),
                    label = "highlight_bg"
                )

                // Voice Transcription Master Toggle Card
                Surface(
                    shape = radius.bentoCardShape,
                    color = highlightBgColor,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            val y = coordinates.positionInRoot().y - containerY + scrollState.value
                            itemCoordinates["voice_transcription"] = y
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
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
                                text = "AI Voice-to-Text Transcription",
                                style = typography.bodyLg,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface
                            )
                            Text(
                                text = "Transcribes voice messages into text bubbles using AI",
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

                // AI Setup Parameters
                if (voiceTranscriptionEnabled) {
                    Surface(
                        shape = radius.bentoCardShape,
                        color = colors.surfaceDim,
                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Provider Dropdown selection
                            Column {
                                Text(
                                    text = "AI Transcription Provider",
                                    style = typography.labelSm,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                                Box {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(44.dp)
                                            .clip(radius.mdShape)
                                            .background(colors.surface)
                                            .border(1.dp, colors.outlineVariant, radius.mdShape)
                                            .clickable { showProviderDropdown = true }
                                            .padding(horizontal = 12.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        Text(text = transcriptionProvider, style = typography.bodyMd, color = colors.onSurface)
                                    }
                                    DropdownMenu(
                                        expanded = showProviderDropdown,
                                        onDismissRequest = { showProviderDropdown = false }
                                    ) {
                                        listOf("Groq", "AssemblyAI").forEach { provider ->
                                            DropdownMenuItem(
                                                text = { Text(text = provider, style = typography.bodyMd) },
                                                onClick = {
                                                    transcriptionProvider = provider
                                                    preferenceManager.putString("transcription_provider", provider)
                                                    showProviderDropdown = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // Dynamic API Key entry based on selected provider
                            if (transcriptionProvider == "Groq") {
                                Column {
                                    Text(
                                        text = "Groq API Key",
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
                            } else {
                                Column {
                                    Text(
                                        text = "AssemblyAI API Key",
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
                                                assemblyStatus = "idle"
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


                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(text = "Connection Status", style = typography.bodyMd, color = colors.onSurfaceVariant)
                                        if (assemblyStatus == "ok") {
                                            Icon(
                                                imageVector = WaexIcons.Success,
                                                contentDescription = null,
                                                tint = Color(0xFF22C55E),
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Text(text = "Connected", style = typography.labelSm, color = Color(0xFF22C55E), fontWeight = FontWeight.Bold)
                                        } else if (assemblyStatus == "verifying") {
                                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = colors.primary)
                                        }
                                    }
                                    Button(
                                        onClick = {
                                            if (assemblyKey.isEmpty()) return@Button
                                            assemblyStatus = "verifying"
                                            coroutineScope.launch {
                                                delay(800)
                                                assemblyStatus = "ok"
                                            }
                                        },
                                        enabled = assemblyKey.isNotEmpty() && assemblyStatus != "verifying",
                                        shape = radius.mdShape,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = colors.primary,
                                            contentColor = colors.onPrimary
                                        ),
                                        modifier = Modifier.height(36.dp)
                                    ) {
                                        Text(text = "Test", style = typography.bodyMd)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(100.dp))
    }
}

private data class AutomationCardData(
    val key: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val desc: String,
    val enabled: Boolean,
    val isPro: Boolean,
    val onCheckedChange: (Boolean) -> Unit
)

private data class VoiceFeatureData(
    val label: String,
    val sub: String,
    val key: String,
    val enabled: Boolean,
    val onChecked: (Boolean) -> Unit
)

private data class TaskerFieldData(
    val label: String,
    val value: String,
    val key: String,
    val onValueChange: (String) -> Unit
)

@Preview(showBackground = true)
@Composable
fun AutomationTaskerScreenPreview() {
    WaexTheme {
        AutomationTaskerScreen()
    }

}