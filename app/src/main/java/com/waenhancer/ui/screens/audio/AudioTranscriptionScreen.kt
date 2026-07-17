package com.waenhancer.ui.screens.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.components.WaexCard
import com.waenhancer.ui.components.WaexPreferenceItem
import com.waenhancer.ui.components.WaexSectionHeader
import com.waenhancer.ui.components.WaexStatusChip
import com.waenhancer.ui.components.WaexSwitchPreference
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController

@Composable
fun AudioTranscriptionScreen() {
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography

    var enableTranscriptionChecked by remember { mutableStateOf(true) }
    var enableSummaryChecked by remember { mutableStateOf(false) }
    var autoDownloadChecked by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = "Audio & AI Summary",
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
            WaexSectionHeader(
                title = "Voice Note AI Rules",
                subtitle = "Transcribe voice notes automatically using local model"
            )

            WaexCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    WaexSwitchPreference(
                        title = "Voice Transcription",
                        description = "Convert received voice notes to readable chat text bubbles.",
                        checked = enableTranscriptionChecked,
                        onCheckedChange = { enableTranscriptionChecked = it },
                        icon = WaexIcons.Mic,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = "Summarize Long Audios",
                        description = "Use Gemini nano to write bulleted abstracts for long notes.",
                        checked = enableSummaryChecked,
                        onCheckedChange = { enableSummaryChecked = it },
                        icon = WaexIcons.Settings,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = "Auto Download Models",
                        description = "Get Whisper models over Wi-Fi automatically.",
                        checked = autoDownloadChecked,
                        onCheckedChange = { autoDownloadChecked = it },
                        icon = WaexIcons.Folder,
                        showDivider = false
                    )
                }
            }

            WaexSectionHeader(
                title = "Language & Downloads",
                subtitle = "Set speech interpretation locale"
            )

            WaexCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    WaexPreferenceItem(
                        title = "Default Language",
                        description = "Selected speech locale: English (US)",
                        icon = WaexIcons.Info,
                        onClick = {},
                        showDivider = true,
                        trailing = {
                            WaexStatusChip(text = "en-US", color = colors.primaryContainer)
                        }
                    )
                    WaexPreferenceItem(
                        title = "Model Download Status",
                        description = "Base translation dictionary: 42 MB",
                        icon = WaexIcons.Success,
                        onClick = {},
                        showDivider = false,
                        trailing = {
                            WaexStatusChip(text = "Ready", color = colors.primaryContainer)
                        }
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AudioTranscriptionScreenPreview() {
    WaexTheme {
        AudioTranscriptionScreen()
    }
}
