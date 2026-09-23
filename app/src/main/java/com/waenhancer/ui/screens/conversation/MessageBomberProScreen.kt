package com.waenhancer.ui.screens.conversation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.components.WaexCard
import com.waenhancer.ui.components.WaexInfoBanner
import com.waenhancer.ui.components.WaexSectionHeader
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController

@Composable
fun MessageBomberProScreen() {
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var targetJid by remember { mutableStateOf("") }
    var messageContent by remember { mutableStateOf("") }
    var messageCount by remember { mutableStateOf(10f) }
    var delayMs by remember { mutableStateOf(500f) }

    var isRunning by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = "Message Bomber Pro",
                onBackClick = { navController.popBack() }
            )
        },
        containerColor = colors.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(spacing.pageMargin),
            verticalArrangement = Arrangement.spacedBy(spacing.stackLg)
        ) {
            val isPro = com.waenhancer.ui.navigation.LocalIsPro.current
            val onActivatePro = com.waenhancer.ui.navigation.LocalOnActivatePro.current

            if (!isPro) {
                WaexInfoBanner(
                    message = "Message Bomber is a Pro-exclusive feature. Please activate your Pro license to use automated messaging bursts.",
                    bannerType = com.waenhancer.ui.components.BannerType.ERROR,
                    title = "Pro License Required"
                )
            } else {
                WaexInfoBanner(
                    message = "Pro Feature: Automated message bursts are subject to account limitations and spam filters. Use responsibly.",
                    bannerType = com.waenhancer.ui.components.BannerType.WARNING,
                    title = "Rate Limit Advisory"
                )
            }

            // Section: Target Selection
            WaexSectionHeader(
                title = "Target Specifications",
                subtitle = "Specify destination phone or WhatsApp JID"
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = radius.mdShape,
                color = colors.surfaceContainerLow,
                border = BorderStroke(1.dp, if (targetJid.isNotEmpty()) colors.primaryContainer else colors.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = WaexIcons.Lock,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    BasicTextField(
                        value = targetJid,
                        onValueChange = { targetJid = it },
                        modifier = Modifier.weight(1f),
                        enabled = isPro,
                        textStyle = typography.bodyLg.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primaryContainer),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            if (targetJid.isEmpty()) {
                                Text(
                                    text = "e.g. +1234567890 or 123456@s.whatsapp.net",
                                    style = typography.bodyLg,
                                    color = colors.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                            innerTextField()
                        }
                    )
                }
            }

            // Section: Message Content
            WaexSectionHeader(
                title = "Message Content",
                subtitle = "Define the repetitive payload text"
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                shape = radius.mdShape,
                color = colors.surfaceContainerLow,
                border = BorderStroke(1.dp, if (messageContent.isNotEmpty()) colors.primaryContainer else colors.outlineVariant)
            ) {
                Box(modifier = Modifier.padding(16.dp)) {
                    BasicTextField(
                        value = messageContent,
                        onValueChange = { messageContent = it },
                        modifier = Modifier.fillMaxSize(),
                        enabled = isPro,
                        textStyle = typography.bodyLg.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primaryContainer),
                        decorationBox = { innerTextField ->
                            if (messageContent.isEmpty()) {
                                Text(
                                    text = "Enter repetitive text payload here...",
                                    style = typography.bodyLg,
                                    color = colors.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                            innerTextField()
                        }
                    )
                }
            }

            // Section: Slider controls
            WaexSectionHeader(
                title = "Burst Velocity & Limits",
                subtitle = "Adjust burst intervals and total message count"
            )

            WaexCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.stackMd)) {
                    // Count
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Message Count",
                                style = typography.bodyLg.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.onSurface
                            )
                            Text(
                                text = "${messageCount.toInt()} msgs",
                                style = typography.bodyLg.copy(color = colors.primaryContainer, fontWeight = FontWeight.Bold)
                            )
                        }
                        Slider(
                            value = messageCount,
                            onValueChange = { messageCount = it },
                            valueRange = 5f..100f,
                            enabled = isPro,
                            colors = SliderDefaults.colors(
                                thumbColor = colors.primaryContainer,
                                activeTrackColor = colors.primaryContainer,
                                inactiveTrackColor = colors.outlineVariant
                            )
                        )
                    }

                    // Delay
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Sending Interval",
                                style = typography.bodyLg.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.onSurface
                            )
                            Text(
                                text = "${delayMs.toInt()} ms",
                                style = typography.bodyLg.copy(color = colors.primaryContainer, fontWeight = FontWeight.Bold)
                            )
                        }
                        Slider(
                            value = delayMs,
                            onValueChange = { delayMs = it },
                            valueRange = 100f..2000f,
                            enabled = isPro,
                            colors = SliderDefaults.colors(
                                thumbColor = colors.primaryContainer,
                                activeTrackColor = colors.primaryContainer,
                                inactiveTrackColor = colors.outlineVariant
                            )
                        )
                    }
                }
            }

            // Start / Upgrade Button
            if (!isPro) {
                Button(
                    onClick = onActivatePro,
                    shape = radius.buttonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = "Unlock Pro to Use Message Bomber",
                        style = typography.bodyLg.copy(fontWeight = FontWeight.Bold)
                    )
                }
            } else {
                Button(
                    onClick = { isRunning = !isRunning },
                    shape = radius.buttonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) colors.error else colors.primaryContainer,
                        contentColor = colors.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = targetJid.isNotEmpty() && messageContent.isNotEmpty()
                ) {
                    Text(
                        text = if (isRunning) "Stop Bombing Process" else "Initiate Message Burst",
                        style = typography.bodyLg.copy(fontWeight = FontWeight.Bold)
                    )
                }

                if (isRunning) {
                    WaexInfoBanner(
                        message = "Active: Sending burst payload to ${targetJid}. Sent 0 / ${messageCount.toInt()} elements.",
                        bannerType = com.waenhancer.ui.components.BannerType.INFO,
                        title = "Running Automated Cycle"
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MessageBomberProScreenPreview() {
    WaexTheme {
        MessageBomberProScreen()
    }
}
