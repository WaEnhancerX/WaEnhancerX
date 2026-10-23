package com.waenhancer.ui.screens.automation

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.core.transcription.TranscriptionLog
import com.waenhancer.ui.designsystem.WaexTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Collapsible view of the transcription diagnostic log, newest first, with Copy and Clear. */
@Composable
fun TranscriptionLogSection() {
    val context = LocalContext.current
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val scope = rememberCoroutineScope()

    var expanded by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    var reloads by remember { mutableStateOf(0) }

    // The log file is read off the UI thread whenever the section opens or is refreshed.
    LaunchedEffect(expanded, reloads) {
        if (expanded) log = withContext(Dispatchers.IO) { TranscriptionLog.read(context) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = UiText.get(R.string.waex_ui_transcription_log, "Transcription log"),
                style = typography.bodyMd,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) UiText.get(R.string.waex_ui_hide, "Hide")
                else UiText.get(R.string.waex_ui_show, "Show"))
            }
        }
        if (!expanded) return@Column
        Text(
            text = UiText.get(R.string.waex_ui_steps_and_errors_only_transcripts_and,
                "Steps and errors only. Transcripts and message text are never logged."),
            style = typography.labelSm,
            color = colors.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .clip(radius.mdShape)
                .background(colors.surface)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(8.dp)
        ) {
            Text(
                text = if (log.isBlank()) UiText.get(R.string.waex_ui_no_entries_yet, "No entries yet.")
                else log.lines().asReversed().joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = colors.onSurface
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("WAEX transcription log", log))
                Toast.makeText(context, UiText.get(R.string.waex_ui_log_copied, "Log copied"),
                    Toast.LENGTH_SHORT).show()
            }, enabled = log.isNotBlank()) {
                Text(UiText.get(R.string.waex_ui_copy, "Copy"))
            }
            TextButton(onClick = { reloads++ }) {
                Text(UiText.get(R.string.waex_ui_refresh, "Refresh"))
            }
            TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { TranscriptionLog.clear(context) }
                    reloads++
                }
            }, enabled = log.isNotBlank()) {
                Text(UiText.get(R.string.waex_ui_clear, "Clear"), color = colors.error)
            }
        }
    }
}
