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
fun UpdateSettingsScreen() {
    val navController = LocalWaexNavController.current
    val prefManager = LocalWaexPreferenceManager.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var autoCheckUpdates by remember { mutableStateOf(prefManager.getBoolean("update_auto_check", true)) }
    var allowDowngrades by remember { mutableStateOf(prefManager.getBoolean("downgrades_enabled", false)) }
    var rootAutoInstall by remember { mutableStateOf(prefManager.getBoolean("root_auto_install", false)) }
    var updateFrequency by remember { mutableStateOf(prefManager.getString("update_frequency", "daily")) }
    var releaseChannel by remember { mutableStateOf(prefManager.getString("release_channel", "stable")) }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = "Update Settings",
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
            // Release Channel Selector Card
            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceDim,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "RELEASE CHANNEL",
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
                        listOf("stable" to "Stable Builds", "beta" to "Beta Preview").forEach { (channelId, label) ->
                            val active = releaseChannel == channelId
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
                                        releaseChannel = channelId
                                        prefManager.putString("release_channel", channelId)
                                    }
                                    .padding(vertical = 14.dp, horizontal = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = typography.bodyMd,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    color = if (active) colors.primary else colors.onSurface
                                )
                            }
                        }
                    }
                }
            }

            // General Update Behaviors Card
            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceDim,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "AUTOMATED CHECKS",
                        style = typography.labelSm,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-Check for Updates",
                                style = typography.bodyLg,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface
                            )
                            Text(
                                text = "Periodically check GitHub releases in background",
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        StitchSwitch(
                            checked = autoCheckUpdates,
                            onCheckedChange = {
                                autoCheckUpdates = it
                                prefManager.putBoolean("update_auto_check", it)
                            }
                        )
                    }

                    HorizontalDivider(
                        thickness = 1.dp,
                        color = colors.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 14.dp)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Allow Version Downgrades",
                                style = typography.bodyLg,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface
                            )
                            Text(
                                text = "Install older builds over newer versions (Root needed)",
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        StitchSwitch(
                            checked = allowDowngrades,
                            onCheckedChange = {
                                allowDowngrades = it
                                prefManager.putBoolean("downgrades_enabled", it)
                            }
                        )
                    }

                    HorizontalDivider(
                        thickness = 1.dp,
                        color = colors.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 14.dp)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Silent Root Install",
                                style = typography.bodyLg,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface
                            )
                            Text(
                                text = "Automatically install downloaded APK via root shell",
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        StitchSwitch(
                            checked = rootAutoInstall,
                            onCheckedChange = {
                                rootAutoInstall = it
                                prefManager.putBoolean("root_auto_install", it)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
