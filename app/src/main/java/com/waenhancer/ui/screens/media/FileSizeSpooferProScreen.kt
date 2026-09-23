package com.waenhancer.ui.screens.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
fun FileSizeSpooferProScreen() {
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var selectedFile by remember { mutableStateOf<String?>(null) }
    var targetSizeMb by remember { mutableStateOf(100f) }
    var isSpoofed by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = "File Size Spoofer Pro",
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
                    message = "File Size Spoofer is a Pro-exclusive feature. Please activate your Pro license to spoof upload envelopes.",
                    bannerType = com.waenhancer.ui.components.BannerType.ERROR,
                    title = "Pro License Required"
                )
            } else {
                WaexInfoBanner(
                    message = "Pro Feature: Bypasses standard WhatsApp media limits by writing custom size tags into the upload envelope. Maximum spoof limit: 500MB.",
                    bannerType = com.waenhancer.ui.components.BannerType.INFO,
                    title = "Media Limit Bypass active"
                )
            }

            // File Selector card
            WaexSectionHeader(
                title = "Target File Selector",
                subtitle = "Choose document, image or video to spoof"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(radius.cardShape)
                    .background(colors.surfaceContainerLow)
                    .border(1.dp, colors.outline, radius.cardShape)
                    .clickable(enabled = isPro) { selectedFile = "MyVideo_Summer.mp4 (14.2 MB)" },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = WaexIcons.Folder,
                        contentDescription = null,
                        tint = colors.primaryContainer,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(spacing.stackSm))
                    Text(
                        text = selectedFile ?: "Tap to choose target media file...",
                        style = typography.bodyLg.copy(fontWeight = FontWeight.Medium),
                        color = colors.onSurface
                    )
                    if (selectedFile == null) {
                        Text(
                            text = "Supports MP4, JPG, PNG, PDF, ZIP",
                            style = typography.labelSm,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }

            // Spoof Settings Slider
            WaexSectionHeader(
                title = "Spoof Parameters",
                subtitle = "Select mock size values to write"
            )

            WaexCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.stackMd)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Target Mock Size",
                            style = typography.bodyLg.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.onSurface
                        )
                        Text(
                            text = "${targetSizeMb.toInt()} MB",
                            style = typography.bodyLg.copy(color = colors.primaryContainer, fontWeight = FontWeight.Bold)
                        )
                    }
                    Slider(
                        value = targetSizeMb,
                        onValueChange = { targetSizeMb = it },
                        valueRange = 20f..500f,
                        enabled = isPro,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.primaryContainer,
                            activeTrackColor = colors.primaryContainer,
                            inactiveTrackColor = colors.outlineVariant
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.stackSm)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(radius.defaultShape)
                                .background(colors.primaryContainer.copy(alpha = 0.05f))
                                .border(1.dp, colors.primaryContainer, radius.defaultShape)
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "MB Suffix", style = typography.labelSm, color = colors.primaryContainer)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(radius.defaultShape)
                                .background(colors.surfaceContainerLow)
                                .border(1.dp, colors.outlineVariant, radius.defaultShape)
                                .clickable { }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "GB Suffix", style = typography.labelSm, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }

            // Spoof Action / Upgrade CTA Button
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
                    Text(text = "Unlock Pro to Use File Spoofer", style = typography.bodyLg.copy(fontWeight = FontWeight.Bold))
                }
            } else {
                Button(
                    onClick = { if (selectedFile != null) isSpoofed = true },
                    shape = radius.buttonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryContainer,
                        contentColor = colors.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = selectedFile != null
                ) {
                    Text(text = "Spoof File Size & Share", style = typography.bodyLg.copy(fontWeight = FontWeight.Bold))
                }

                if (isSpoofed) {
                    WaexInfoBanner(
                        message = "Success: Header tags updated. The file will now report as ${targetSizeMb.toInt()} MB inside WhatsApp chats.",
                        bannerType = com.waenhancer.ui.components.BannerType.INFO,
                        title = "File Spoofed Successfully"
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun FileSizeSpooferProScreenPreview() {
    WaexTheme {
        FileSizeSpooferProScreen()
    }
}
