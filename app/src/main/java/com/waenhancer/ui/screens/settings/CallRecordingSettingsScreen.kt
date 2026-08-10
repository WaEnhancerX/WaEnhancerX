package com.waenhancer.ui.screens.settings

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.components.StitchSwitch
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager

@Composable
fun CallRecordingSettingsScreen() {
    val navController = LocalWaexNavController.current
    val prefManager = LocalWaexPreferenceManager.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var callRecordingEnabled by remember { mutableStateOf(prefManager.getBoolean("call_recording_enabled", true)) }
    var useRootStream by remember { mutableStateOf(prefManager.getBoolean("call_recording_use_root", false)) }
    var autoRecordAll by remember { mutableStateOf(prefManager.getBoolean("call_recording_auto_all", true)) }
    var audioFormat by remember { mutableStateOf(prefManager.getString("call_recording_format", "m4a")) }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = "Call Recording Settings",
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
                .padding(horizontal = spacing.pageMargin, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Main Switch
            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceDim,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "WhatsApp Call Recording",
                            style = typography.headlineMd,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                        Text(
                            text = "Automatically capture incoming and outgoing voice calls",
                            style = typography.bodyMd,
                            color = colors.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                    StitchSwitch(
                        checked = callRecordingEnabled,
                        onCheckedChange = {
                            callRecordingEnabled = it
                            prefManager.putBoolean("call_recording_enabled", it)
                        }
                    )
                }
            }

            // Audio Capture Mode
            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceDim,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "CAPTURE ENGINE MODE",
                        style = typography.labelSm,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // Root Stream Capture
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(radius.defaultShape)
                            .background(if (useRootStream) colors.primary.copy(alpha = 0.08f) else Color.Transparent)
                            .border(
                                1.dp,
                                if (useRootStream) colors.primary else colors.outlineVariant,
                                radius.defaultShape
                            )
                            .clickable {
                                useRootStream = true
                                prefManager.putBoolean("call_recording_use_root", true)
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .border(2.dp, if (useRootStream) colors.primary else colors.outlineVariant, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (useRootStream) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(colors.primary)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Root Direct Audio Pipeline (Recommended)",
                                style = typography.bodyLg,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface
                            )
                            Text(
                                text = "Directly records both sides of call crystal clear via kernel audio stream",
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Non-Root Microphone Capture
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(radius.defaultShape)
                            .background(if (!useRootStream) colors.primary.copy(alpha = 0.08f) else Color.Transparent)
                            .border(
                                1.dp,
                                if (!useRootStream) colors.primary else colors.outlineVariant,
                                radius.defaultShape
                            )
                            .clickable {
                                useRootStream = false
                                prefManager.putBoolean("call_recording_use_root", false)
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .border(2.dp, if (!useRootStream) colors.primary else colors.outlineVariant, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!useRootStream) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(colors.primary)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Microphone Accessibility Capture",
                                style = typography.bodyLg,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface
                            )
                            Text(
                                text = "Uses standard Android audio record source (Non-root fallback)",
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Audio Format Card
            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceDim,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "ENCODING & FORMAT",
                        style = typography.labelSm,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        listOf("m4a" to "AAC (.m4a)", "wav" to "Lossless (.wav)", "opus" to "Opus (.opus)").forEach { (formatKey, label) ->
                            val active = audioFormat == formatKey
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(radius.defaultShape)
                                    .background(if (active) colors.primary.copy(alpha = 0.12f) else colors.surface)
                                    .border(
                                        1.dp,
                                        if (active) colors.primary else colors.outlineVariant,
                                        radius.defaultShape
                                    )
                                    .clickable {
                                        audioFormat = formatKey
                                        prefManager.putString("call_recording_format", formatKey)
                                    }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = typography.bodyMd,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    color = if (active) colors.primary else colors.onSurface,
                                    fontSize = 12.sp
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
