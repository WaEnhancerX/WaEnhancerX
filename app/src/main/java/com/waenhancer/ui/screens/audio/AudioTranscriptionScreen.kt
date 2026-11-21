package com.waenhancer.ui.screens.audio

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

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
                title = UiText.get(R.string.title_audio_ai_summary, "Audio & AI Summary"),
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
                title = UiText.get(R.string.waex_ui_voice_note_ai_rules, "Voice Note AI Rules"),
                subtitle = UiText.get(R.string.waex_ui_transcribe_voice_notes_automatically_u, "Transcribe voice notes automatically using local model")
            )

            WaexCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    WaexSwitchPreference(
                        title = UiText.get(R.string.waex_ui_voice_transcription, "Voice Transcription"),
                        description = UiText.get(R.string.waex_ui_convert_received_voice_notes_to_readab, "Convert received voice notes to readable chat text bubbles."),
                        checked = enableTranscriptionChecked,
                        onCheckedChange = { enableTranscriptionChecked = it },
                        icon = WaexIcons.Mic,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = UiText.get(R.string.waex_ui_summarize_long_audios, "Summarize Long Audios"),
                        description = UiText.get(R.string.waex_ui_use_gemini_nano_to_write_bulleted_abst, "Use Gemini nano to write bulleted abstracts for long notes."),
                        checked = enableSummaryChecked,
                        onCheckedChange = { enableSummaryChecked = it },
                        icon = WaexIcons.Settings,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = UiText.get(R.string.waex_ui_auto_download_models, "Auto Download Models"),
                        description = UiText.get(R.string.waex_ui_get_whisper_models_over_wi_fi_automati, "Get Whisper models over Wi-Fi automatically."),
                        checked = autoDownloadChecked,
                        onCheckedChange = { autoDownloadChecked = it },
                        icon = WaexIcons.Folder,
                        showDivider = false
                    )
                }
            }

            WaexSectionHeader(
                title = UiText.get(R.string.waex_ui_language_downloads, "Language & Downloads"),
                subtitle = UiText.get(R.string.waex_ui_set_speech_interpretation_locale, "Set speech interpretation locale")
            )

            WaexCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    WaexPreferenceItem(
                        title = UiText.get(R.string.waex_ui_default_language, "Default Language"),
                        description = UiText.get(R.string.waex_ui_selected_speech_locale_english_us, "Selected speech locale: English (US)"),
                        icon = WaexIcons.Info,
                        onClick = {},
                        showDivider = true,
                        trailing = {
                            WaexStatusChip(text = "en-US", color = colors.primaryContainer)
                        }
                    )
                    WaexPreferenceItem(
                        title = UiText.get(R.string.waex_ui_model_download_status, "Model Download Status"),
                        description = UiText.get(R.string.waex_ui_base_translation_dictionary_42_mb, "Base translation dictionary: 42 MB"),
                        icon = WaexIcons.Success,
                        onClick = {},
                        showDivider = false,
                        trailing = {
                            WaexStatusChip(text = UiText.get(R.string.waex_ui_ready, "Ready"), color = colors.primaryContainer)
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
