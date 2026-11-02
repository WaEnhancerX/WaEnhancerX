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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.waenhancer.ui.components.WaexModalBottomSheet
import com.waenhancer.ui.designsystem.WaexTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hallmark · redesign: full · scope: component · genre: editorial · theme: WAEX brand
 * Diagnostic log section with live polling, entry metrics, and an expressive Modal Bottom Sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptionLogSection() {
    val context = LocalContext.current
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val scope = rememberCoroutineScope()

    var showBottomSheet by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var isPollingActive by remember { mutableStateOf(true) }

    // Live log reader: polls the log file when the bottom sheet is open so events appear in real-time
    LaunchedEffect(showBottomSheet, isPollingActive) {
        if (!showBottomSheet) return@LaunchedEffect
        while (isPollingActive) {
            val fresh = withContext(Dispatchers.IO) { TranscriptionLog.read(context) }
            logText = fresh
            delay(1000)
        }
    }

    // Trigger button row
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ListAlt,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = UiText.get(R.string.waex_ui_transcription_log, "Live Diagnostics Log"),
                        style = typography.bodyMd,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    Text(
                        text = UiText.get(R.string.waex_ui_steps_and_errors_only_transcripts_and,
                            "Steps and timings only. Private audio & text are never logged."),
                        style = typography.bodySm,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalButton(
                onClick = {
                    showBottomSheet = true
                    isPollingActive = true
                },
                shape = radius.smShape
            ) {
                Text(
                    text = UiText.get(R.string.waex_ui_show, "View Logs"),
                    style = typography.labelSm,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    // Bottom Sheet for live scrollable logs
    if (showBottomSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val logLines = remember(logText) {
            if (logText.isBlank()) emptyList()
            else logText.lines().filter { it.isNotBlank() }.asReversed()
        }

        WaexModalBottomSheet(
            onDismissRequest = {
                showBottomSheet = false
                isPollingActive = false
            },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = UiText.get(R.string.waex_ui_transcription_log, "Transcription Diagnostics"),
                                style = typography.titleMd,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isPollingActive) colors.success.copy(alpha = 0.15f) else colors.outlineVariant.copy(alpha = 0.3f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(if (isPollingActive) colors.success else colors.onSurfaceVariant)
                                    )
                                    Text(
                                        text = if (isPollingActive) "LIVE" else "PAUSED",
                                        style = typography.labelSm,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isPollingActive) colors.success else colors.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Text(
                            text = "${logLines.size} recorded entries",
                            style = typography.labelSm,
                            color = colors.onSurfaceVariant
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = { isPollingActive = !isPollingActive },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(colors.surfaceContainerHigh)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Toggle Live Refresh",
                                tint = if (isPollingActive) colors.primary else colors.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(ClipData.newPlainText("WAEX Transcription Log", logText))
                                Toast.makeText(context, UiText.get(R.string.waex_ui_log_copied, "Log copied"), Toast.LENGTH_SHORT).show()
                            },
                            enabled = logText.isNotBlank(),
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(colors.surfaceContainerHigh)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = UiText.get(R.string.waex_ui_copy, "Copy"),
                                tint = colors.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { TranscriptionLog.clear(context) }
                                    logText = ""
                                }
                            },
                            enabled = logText.isNotBlank(),
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(colors.error.copy(alpha = 0.1f))
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = UiText.get(R.string.waex_ui_clear, "Clear"),
                                tint = colors.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Scrollable log terminal area
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.surfaceDim,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                ) {
                    if (logLines.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(380.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = UiText.get(R.string.waex_ui_no_entries_yet, "No transcription events recorded yet."),
                                style = typography.bodySm,
                                color = colors.onSurfaceVariant
                            )
                        }
                    } else {
                        val listState = rememberLazyListState()
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(logLines) { index, line ->
                                val isError = line.contains("error", ignoreCase = true) || line.contains("fail", ignoreCase = true)
                                val isSuccess = line.contains("done", ignoreCase = true) || line.contains("success", ignoreCase = true) || line.contains("ready", ignoreCase = true)

                                val textColor = when {
                                    isError -> colors.error
                                    isSuccess -> colors.success
                                    else -> colors.onSurface
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = line,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                        color = textColor
                                    )
                                }
                                if (index < logLines.lastIndex) {
                                    HorizontalDivider(
                                        color = colors.outlineVariant.copy(alpha = 0.2f),
                                        thickness = 0.5.dp,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
