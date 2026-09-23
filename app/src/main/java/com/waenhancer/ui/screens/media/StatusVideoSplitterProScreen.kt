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
fun StatusVideoSplitterProScreen() {
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var selectedVideo by remember { mutableStateOf<String?>(null) }
    var splitDuration by remember { mutableStateOf("30s") }
    var isProcessing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = "Status Video Splitter Pro",
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
                    message = "Status Video Splitter is a Pro-exclusive feature. Please activate your Pro license to split videos.",
                    bannerType = com.waenhancer.ui.components.BannerType.ERROR,
                    title = "Pro License Required"
                )
            } else {
                WaexInfoBanner(
                    message = "Pro Feature: Splits long video files into perfect contiguous chunks to upload as a continuous status story. Cuts are made seamlessly on keyframes.",
                    bannerType = com.waenhancer.ui.components.BannerType.INFO,
                    title = "Status Duration Enhancer"
                )
            }

            // Select Video Card
            WaexSectionHeader(
                title = "Select Video File",
                subtitle = "Choose source movie from device storage"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(radius.cardShape)
                    .background(colors.surfaceContainerLow)
                    .border(1.dp, colors.outline, radius.cardShape)
                    .clickable(enabled = isPro) { selectedVideo = "TripToParis_2026.mp4 (3m 42s)" },
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
                        text = selectedVideo ?: "Choose target video file...",
                        style = typography.bodyLg.copy(fontWeight = FontWeight.Medium),
                        color = colors.onSurface
                    )
                    if (selectedVideo == null) {
                        Text(
                            text = "Supports MP4, MKV, AVI, MOV",
                            style = typography.labelSm,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }

            // Split Length Parameters
            WaexSectionHeader(
                title = "Split Parameters",
                subtitle = "Select desired chunk length"
            )

            WaexCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.stackMd)) {
                    Text(
                        text = "Split Interval",
                        style = typography.bodyLg.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.onSurface
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.stackSm)
                    ) {
                        val durations = listOf("15s", "30s", "60s")
                        durations.forEach { dur ->
                            val isSelected = (splitDuration == dur)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(radius.defaultShape)
                                    .background(if (isSelected) colors.primaryContainer.copy(alpha = 0.05f) else colors.surfaceContainerLow)
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) colors.primaryContainer else colors.outlineVariant,
                                        shape = radius.defaultShape
                                    )
                                    .clickable(enabled = isPro) { splitDuration = dur }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = dur,
                                    style = typography.labelSm,
                                    color = if (isSelected) colors.primaryContainer else colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Split / Upgrade CTA button
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
                    Text(text = "Unlock Pro to Use Video Splitter", style = typography.bodyLg.copy(fontWeight = FontWeight.Bold))
                }
            } else {
                Button(
                    onClick = { if (selectedVideo != null) isProcessing = true },
                    shape = radius.buttonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryContainer,
                        contentColor = colors.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = selectedVideo != null
                ) {
                    Text(text = "Process and Split Video", style = typography.bodyLg.copy(fontWeight = FontWeight.Bold))
                }

                if (isProcessing) {
                    WaexInfoBanner(
                        message = "Processing: Splitting video into 8 chunks of ${splitDuration} each. Ready to share to WhatsApp status.",
                        bannerType = com.waenhancer.ui.components.BannerType.INFO,
                        title = "Video Processing Completed"
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun StatusVideoSplitterProScreenPreview() {
    WaexTheme {
        StatusVideoSplitterProScreen()
    }
}
