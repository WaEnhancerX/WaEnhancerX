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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController

data class ReleaseNote(
    val version: String,
    val releaseDate: String,
    val isLatest: Boolean = false,
    val isBeta: Boolean = false,
    val highlights: List<String>,
    val fixes: List<String>
)

@Composable
fun ChangelogScreen() {
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var selectedChannel by remember { mutableStateOf("All") } // "All", "Stable", "Beta"

    val releases = remember {
        listOf(
            ReleaseNote(
                version = "v3.2.0-beta2",
                releaseDate = "August 2026",
                isLatest = true,
                isBeta = true,
                highlights = listOf(
                    "Complete Modern Compose UI with Stitch Material 3 tokens",
                    "Redesigned System Health and Hook Diagnostic Inspector",
                    "Per-Contact Privacy granular scheduler support"
                ),
                fixes = listOf(
                    "Fixed status bar overlap on Edge-to-Edge Android 15 devices",
                    "Optimized preference persistence across system reboots",
                    "Resolved DexKit fallback lookup latency"
                )
            ),
            ReleaseNote(
                version = "v3.1.2",
                releaseDate = "July 2026",
                isLatest = false,
                isBeta = false,
                highlights = listOf(
                    "Added WhatsApp Business v2.24.25+ compatibility support",
                    "Audio Transcription background model pipeline",
                    "Message Bomber with randomized anti-ban delay timing"
                ),
                fixes = listOf(
                    "Fixed status downloader timestamp formatting",
                    "Improved root access detection on Magisk/KernelSU"
                )
            ),
            ReleaseNote(
                version = "v3.0.0",
                releaseDate = "May 2026",
                isLatest = false,
                isBeta = false,
                highlights = listOf(
                    "Full rewrite with modular hooking engine architecture",
                    "Dynamic DexKit pattern signature database",
                    "Tasker & MacroDroid broadcast automation interface"
                ),
                fixes = listOf(
                    "Reduced battery footprint by 40%",
                    "Prevented crash when parsing voice note waveform cache"
                )
            )
        )
    }

    val filteredReleases = when (selectedChannel) {
        "Stable" -> releases.filter { !it.isBeta }
        "Beta" -> releases.filter { it.isBeta }
        else -> releases
    }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = "Changelog & Releases",
                onBackClick = { navController.popBack() }
            )
        },
        containerColor = colors.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = spacing.pageMargin,
                vertical = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Channel Filter Chips
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("All", "Stable", "Beta").forEach { channel ->
                        val active = selectedChannel == channel
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (active) colors.primary else colors.surfaceDim)
                                .border(
                                    1.dp,
                                    if (active) colors.primary else colors.outlineVariant,
                                    CircleShape
                                )
                                .clickable { selectedChannel = channel }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = channel,
                                style = typography.labelSm,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                color = if (active) colors.onPrimary else colors.onSurface
                            )
                        }
                    }
                }
            }

            // Release Cards
            items(filteredReleases) { release ->
                Surface(
                    shape = radius.bentoCardShape,
                    color = colors.surfaceDim,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = release.version,
                                style = typography.headlineMd,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            if (release.isLatest) {
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(Color(0xFF22C55E).copy(alpha = 0.15f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Latest",
                                        style = typography.labelSm,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF22C55E),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            if (release.isBeta) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(Color(0xFFF59E0B).copy(alpha = 0.15f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Beta",
                                        style = typography.labelSm,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFF59E0B),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = release.releaseDate,
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "New Features & Enhancements",
                            style = typography.labelSm,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        release.highlights.forEach { highlight ->
                            Row(
                                verticalAlignment = Alignment.Top,
                                modifier = Modifier.padding(vertical = 3.dp)
                            ) {
                                Text(
                                    text = "•",
                                    style = typography.bodyMd,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.primary,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text(
                                    text = highlight,
                                    style = typography.bodyMd,
                                    color = colors.onSurface
                                )
                            }
                        }

                        if (release.fixes.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Bug Fixes & Refinements",
                                style = typography.labelSm,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurfaceVariant,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            release.fixes.forEach { fix ->
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    modifier = Modifier.padding(vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "✓",
                                        style = typography.bodyMd,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF22C55E),
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text(
                                        text = fix,
                                        style = typography.bodyMd,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
