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

    // Automation states
    var autoReplyEnabled by remember { mutableStateOf(false) }
    var statusForwardEnabled by remember { mutableStateOf(false) }
    var alwaysTypingEnabled by remember { mutableStateOf(true) }
    var scheduledActionsEnabled by remember { mutableStateOf(false) }

    var intentAction by remember { mutableStateOf("com.waenhancerx.ACTION_SEND") }
    var intentData by remember { mutableStateOf("content://media/external") }
    var intentPackage by remember { mutableStateOf("com.whatsapp") }
    var intentCategory by remember { mutableStateOf("android.intent.category.DEFAULT") }

    // Audio & AI states
    var assemblyKey by remember { mutableStateOf("") }
    var assemblyStatus by remember { mutableStateOf("idle") } // "idle" | "verifying" | "ok" | "fail"
    var groqKey by remember { mutableStateOf("") }
    var groqModel by remember { mutableStateOf("Llama 3 8B") }
    var showModelDropdown by remember { mutableStateOf(false) }
    var temperature by remember { mutableStateOf(0.7f) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }

    var transcriptionEnabled by remember { mutableStateOf(true) }
    var sttEnabled by remember { mutableStateOf(false) }
    var offlineModelsEnabled by remember { mutableStateOf(false) }

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
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                listOf("automation" to "Automation", "ai" to "Audio & AI").forEach { (tabId, label) ->
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

        if (selectedTab == "automation") {
            // Automation Cards
            val cards = listOf(
                AutomationCardData("auto_reply", WaexIcons.Settings, "Auto Reply", "Respond automatically to incoming messages", autoReplyEnabled, false) { autoReplyEnabled = it },
                AutomationCardData("status_forward", WaexIcons.Share, "Status Forward", "Auto-forward received statuses to contacts", statusForwardEnabled, false) { statusForwardEnabled = it },
                AutomationCardData("always_typing", WaexIcons.Lock, "Always Typing", "Maintain typing indicator at all times", alwaysTypingEnabled, true) { alwaysTypingEnabled = it },
                AutomationCardData("scheduled_actions", WaexIcons.Refresh, "Scheduled Actions", "Trigger tasks at specific times or intervals", scheduledActionsEnabled, true) { scheduledActionsEnabled = it }
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

            // Tasker Integration
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.pageMargin)
            ) {
                Text(
                    text = "Tasker Integration",
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
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val fields = listOf(
                            TaskerFieldData("Intent Action", intentAction, "intent_action") { intentAction = it },
                            TaskerFieldData("Intent Data", intentData, "intent_data") { intentData = it },
                            TaskerFieldData("Intent Package", intentPackage, "intent_package") { intentPackage = it },
                            TaskerFieldData("Intent Category", intentCategory, "intent_category") { intentCategory = it }
                        )

                        fields.forEach { (label, value, key, onValChange) ->
                            val isHighlighted = navController.highlightTargetKey == key
                            val highlightBgColor by animateColorAsState(
                                targetValue = if (isHighlighted) colors.primary.copy(alpha = 0.15f) else Color.Transparent,
                                animationSpec = tween(durationMillis = 300),
                                label = "highlight_bg"
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(highlightBgColor)
                                    .onGloballyPositioned { coordinates ->
                                        val y = coordinates.positionInRoot().y - containerY + scrollState.value
                                        itemCoordinates[key] = y
                                    }
                            ) {
                                Text(text = label, style = typography.labelSm, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
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
                                        onValueChange = onValChange,
                                        singleLine = true,
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
                    }
                }
            }

            // Execution History
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.pageMargin)
            ) {
                Text(
                    text = "Execution History",
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
                        // Table Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF0F0F2))
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Text(text = "Time", style = typography.labelSm, fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant, modifier = Modifier.weight(0.8f))
                            Text(text = "Trigger", style = typography.labelSm, fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant, modifier = Modifier.weight(1.2f))
                            Text(text = "Result", style = typography.labelSm, fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant, modifier = Modifier.weight(1.2f))
                        }
                        HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)

                        val history = listOf(
                            Triple("14:32", "Auto Reply", Pair("Sent to Alex J.", true)),
                            Triple("13:58", "Status Forward", Pair("3 contacts updated", true)),
                            Triple("12:15", "Always Typing", Pair("Session resumed", true)),
                            Triple("11:40", "Scheduled Action", Pair("Rate limit hit", false)),
                            Triple("09:07", "Auto Reply", Pair("Message delivered", true))
                        )

                        history.forEachIndexed { idx, (time, trigger, res) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = time, style = typography.bodyMd.copy(fontFamily = FontFamily.Monospace), color = colors.onSurface, modifier = Modifier.weight(0.8f))
                                Text(text = trigger, style = typography.bodyMd, color = colors.onSurface, modifier = Modifier.weight(1.2f))
                                Row(
                                    modifier = Modifier.weight(1.2f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(if (res.second) Color(0xFF22C55E) else Color(0xFFF44336))
                                    )
                                    Text(text = res.first, style = typography.labelSm, color = colors.onSurfaceVariant, maxLines = 1)
                                }
                            }
                            if (idx < history.lastIndex) {
                                HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)
                            }
                        }
                    }
                }
            }

        } else {
            // Audio & AI Tab Content

            // AssemblyAI Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.pageMargin)
            ) {
                Text(
                    text = "AssemblyAI",
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
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .onGloballyPositioned { coordinates ->
                                    val y = coordinates.positionInRoot().y - containerY + scrollState.value
                                    itemCoordinates["assembly_key"] = y
                                }
                        ) {
                            val isHighlighted = navController.highlightTargetKey == "assembly_key"
                            val highlightBgColor by animateColorAsState(
                                targetValue = if (isHighlighted) colors.primary.copy(alpha = 0.15f) else Color.Transparent,
                                animationSpec = tween(300),
                                label = "highlight_bg"
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(highlightBgColor)
                            ) {
                                Text(text = "API Key", style = typography.labelSm, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

            // Groq Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.pageMargin)
            ) {
                Text(
                    text = "Groq",
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
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .onGloballyPositioned { coordinates ->
                                    val y = coordinates.positionInRoot().y - containerY + scrollState.value
                                    itemCoordinates["groq_key"] = y
                                }
                        ) {
                            val isHighlighted = navController.highlightTargetKey == "groq_key"
                            val highlightBgColor by animateColorAsState(
                                targetValue = if (isHighlighted) colors.primary.copy(alpha = 0.15f) else Color.Transparent,
                                animationSpec = tween(300),
                                label = "highlight_bg"
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(highlightBgColor)
                            ) {
                                Text(text = "API Key", style = typography.labelSm, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
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
                                    onValueChange = { groqKey = it },
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

                        // Model select dropdown
                        Column {
                            Text(text = "Model", style = typography.labelSm, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                            Box {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp)
                                        .clip(radius.mdShape)
                                        .background(colors.surface)
                                        .border(1.dp, colors.outlineVariant, radius.mdShape)
                                        .clickable { showModelDropdown = true }
                                        .padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(text = groqModel, style = typography.bodyMd, color = colors.onSurface)
                                }
                                DropdownMenu(
                                    expanded = showModelDropdown,
                                    onDismissRequest = { showModelDropdown = false }
                                ) {
                                    listOf("Llama 3 8B", "Llama 3 70B", "Mixtral 8x7B", "Gemma 7B").forEach { model ->
                                        DropdownMenuItem(
                                            text = { Text(text = model, style = typography.bodyMd) },
                                            onClick = {
                                                groqModel = model
                                                showModelDropdown = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Temperature Slider
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "Temperature", style = typography.labelSm, color = colors.onSurfaceVariant)
                                Text(text = String.format("%.1f", temperature), style = typography.bodyMd, fontWeight = FontWeight.Bold, color = colors.onSurface)
                            }
                            Slider(
                                value = temperature,
                                onValueChange = { temperature = it },
                                valueRange = 0f..1f,
                                colors = SliderDefaults.colors(
                                    thumbColor = colors.primary,
                                    activeTrackColor = colors.primary,
                                    inactiveTrackColor = colors.outlineVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // Voice Feature Toggles
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.pageMargin)
            ) {
                Text(
                    text = "Voice Features",
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
                        val voiceFeatures = listOf(
                            VoiceFeatureData("Audio Transcription", "Convert voice messages to text", "transcription", transcriptionEnabled) { transcriptionEnabled = it },
                            VoiceFeatureData("Speech To Text", "Live voice input for messages", "stt", sttEnabled) { sttEnabled = it },
                            VoiceFeatureData("Offline Models", "Use on-device processing", "offline", offlineModelsEnabled) { offlineModelsEnabled = it }
                        )

                        voiceFeatures.forEachIndexed { idx, (label, sub, key, enabled, onChecked) ->
                            val isHighlighted = navController.highlightTargetKey == key
                            val highlightBgColor by animateColorAsState(
                                targetValue = if (isHighlighted) colors.primary.copy(alpha = 0.15f) else Color.Transparent,
                                animationSpec = tween(300),
                                label = "highlight_bg"
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(highlightBgColor)
                                    .onGloballyPositioned { coordinates ->
                                        val y = coordinates.positionInRoot().y - containerY + scrollState.value
                                        itemCoordinates[key] = y
                                    }
                                    .clickable { onChecked(!enabled) }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(text = label, style = typography.bodyLg, fontWeight = FontWeight.Medium, color = colors.onSurface)
                                    Text(text = sub, style = typography.bodyMd, color = colors.onSurfaceVariant, fontSize = 12.sp)
                                }
                                StitchSwitch(
                                    checked = enabled,
                                    onCheckedChange = onChecked
                                )
                            }
                            if (idx < voiceFeatures.lastIndex) {
                                HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)
                            }
                        }
                    }
                }
            }

            // Playback Speed Slider
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.pageMargin)
            ) {
                Text(
                    text = "Voice Playback Speed",
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
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(imageVector = WaexIcons.Mic, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            // Format to nearest speed label (0.5x, 1x, 1.5x, 2x, 3x)
                            val speedLabels = listOf("0.5x", "1x", "1.5x", "2x", "3x")
                            val speedValues = listOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f)
                            val nearestSpeedIdx = speedValues.indices.minByOrNull { Math.abs(speedValues[it] - playbackSpeed) } ?: 1
                            val nearestSpeedLabel = speedLabels[nearestSpeedIdx]

                            Text(
                                text = nearestSpeedLabel,
                                style = typography.headlineMd,
                                fontWeight = FontWeight.Bold,
                                color = colors.primary
                            )
                            Icon(imageVector = WaexIcons.Mic, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = playbackSpeed,
                            onValueChange = {
                                // Snap to nearest 0.5 step
                                val stepValue = Math.round(it / 0.5f) * 0.5f
                                playbackSpeed = stepValue.coerceIn(0.5f, 3.0f)
                            },
                            valueRange = 0.5f..3.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = colors.primary,
                                activeTrackColor = colors.primary,
                                inactiveTrackColor = colors.outlineVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            listOf("0.5x", "1x", "1.5x", "2x", "3x").forEach { label ->
                                Text(text = label, style = typography.labelSm, color = colors.onSurfaceVariant)
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