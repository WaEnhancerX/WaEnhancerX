package com.waenhancer.ui.previews

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.components.BannerType
import com.waenhancer.ui.components.TileType
import com.waenhancer.ui.components.WaexCard
import com.waenhancer.ui.components.WaexFeatureTile
import com.waenhancer.ui.components.WaexInfoBanner
import com.waenhancer.ui.components.WaexPreferenceItem
import com.waenhancer.ui.components.WaexSearchBar
import com.waenhancer.ui.components.WaexSectionHeader
import com.waenhancer.ui.components.WaexStatusChip
import com.waenhancer.ui.components.WaexSwitchPreference
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme

@Composable
fun DesignSystemDashboard() {
    var searchQuery by remember { mutableStateOf("") }
    var switch1Checked by remember { mutableStateOf(true) }
    var switch2Checked by remember { mutableStateOf(false) }

    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing

    Scaffold(
        topBar = {
            WaexTopBar(
                title = "Wa Enhancer X",
                actions = {
                    IconButton(onClick = {}) {
                        Icon(imageVector = WaexIcons.Refresh, contentDescription = "Refresh", tint = colors.onSurface)
                    }
                    IconButton(onClick = {}) {
                        Icon(imageVector = WaexIcons.Settings, contentDescription = "Settings", tint = colors.onSurface)
                    }
                }
            )
        },
        containerColor = colors.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(spacing.pageMargin)
        ) {
            // Section 1: System Info Alert Banner
            WaexInfoBanner(
                title = "System Status: Warning",
                message = "WhatsApp version mismatch detected. Some hooks may be unstable. Click fix to reconcile compatibility.",
                bannerType = BannerType.WARNING,
                actionText = "Reconcile Compatibility",
                onActionClick = {}
            )
            Spacer(modifier = Modifier.height(spacing.stackLg))

            // Section 2: Search
            WaexSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search dashboard..."
            )
            Spacer(modifier = Modifier.height(spacing.stackLg))

            // Section 3: Bento Dashboard Grid (4-column layout representation)
            WaexSectionHeader(
                title = "Dashboard Insights",
                subtitle = "Active system modular tools"
            )
            Spacer(modifier = Modifier.height(spacing.stackMd))

            // Bento row 1 (2x1 Horizontal Tiles)
            Row(modifier = Modifier.fillMaxWidth()) {
                WaexFeatureTile(
                    title = "Media & Status Hub",
                    icon = WaexIcons.Folder,
                    description = "Manage status splitting",
                    statusText = "Active",
                    statusColor = Color(0xFF2E7D32),
                    tileType = TileType.HORIZONTAL_2X1,
                    onClick = {},
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(spacing.bentoGap))

            // Bento row 2 (1x1 and 1x1 tiles representation)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.bentoGap)
            ) {
                WaexFeatureTile(
                    title = "AI Transcription",
                    icon = WaexIcons.Mic,
                    statusText = "Ready",
                    statusColor = colors.primaryContainer,
                    tileType = TileType.COMPACT_1X1,
                    onClick = {},
                    modifier = Modifier.weight(1f)
                )
                WaexFeatureTile(
                    title = "Pro Settings",
                    icon = WaexIcons.Premium,
                    statusText = "Locked",
                    statusColor = colors.error,
                    tileType = TileType.COMPACT_1X1,
                    onClick = {},
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(spacing.bentoGap))

            // Bento row 3 (2x2 Tall layout representation)
            Row(modifier = Modifier.fillMaxWidth()) {
                WaexFeatureTile(
                    title = "Automation Engine",
                    icon = WaexIcons.Play,
                    description = "Create and run Tasker actions natively. Requires active system package injection.",
                    statusText = "Stopped",
                    statusColor = colors.secondary,
                    tileType = TileType.TALL_2X2,
                    onClick = {},
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(spacing.stackLg))

            // Section 4: General Settings Group
            WaexSectionHeader(
                title = "System Preferences",
                subtitle = "Configure global privacy & engine state"
            )
            Spacer(modifier = Modifier.height(spacing.stackMd))

            WaexCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    WaexSwitchPreference(
                        title = "Stealth Mode",
                        description = "Hide read receipts and presence updates globally.",
                        checked = switch1Checked,
                        onCheckedChange = { switch1Checked = it },
                        icon = WaexIcons.Lock,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = "Log Injection Attempts",
                        description = "Save trace details of incoming system signals.",
                        checked = switch2Checked,
                        onCheckedChange = { switch2Checked = it },
                        icon = WaexIcons.Info,
                        showDivider = true
                    )
                    WaexPreferenceItem(
                        title = "Active Security Verification",
                        description = "Verify hook signatures and WhatsApp security tokens.",
                        icon = WaexIcons.Settings,
                        onClick = {},
                        showDivider = false,
                        trailing = {
                            WaexStatusChip(text = "Secure", color = Color(0xFF2E7D32))
                        }
                    )
                }
            }
        }
    }
}

@Preview(name = "Light Mode - Bento Dashboard", showBackground = true)
@Composable
fun LightDashboardPreview() {
    WaexTheme(darkTheme = false) {
        DesignSystemDashboard()
    }
}

@Preview(name = "Dark Mode - Bento Dashboard", showBackground = true)
@Composable
fun DarkDashboardPreview() {
    WaexTheme(darkTheme = true) {
        DesignSystemDashboard()
    }
}

@Composable
fun TypographyAndColorsSpec() {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val spacing = WaexTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(spacing.pageMargin)
    ) {
        Text(text = "Swiss Precision Typography Scaling", style = typography.headlineMd, color = colors.onSurface)
        Spacer(modifier = Modifier.height(spacing.stackMd))

        Text(text = "displayLg (32sp, SemiBold)", style = typography.displayLg, color = colors.onSurface)
        Spacer(modifier = Modifier.height(spacing.stackSm))
        Text(text = "displayLgMobile (28sp, SemiBold)", style = typography.displayLgMobile, color = colors.onSurface)
        Spacer(modifier = Modifier.height(spacing.stackSm))
        Text(text = "headlineMd (20sp, SemiBold)", style = typography.headlineMd, color = colors.onSurface)
        Spacer(modifier = Modifier.height(spacing.stackSm))
        Text(text = "bodyLg (16sp, Normal)", style = typography.bodyLg, color = colors.onSurface)
        Spacer(modifier = Modifier.height(spacing.stackSm))
        Text(text = "bodyMd (14sp, Normal)", style = typography.bodyMd, color = colors.onSurface)
        Spacer(modifier = Modifier.height(spacing.stackSm))
        Text(text = "labelSm (12sp, Medium)", style = typography.labelSm, color = colors.onSurface)

        Spacer(modifier = Modifier.height(spacing.stackLg))
        HorizontalDivider(color = colors.outlineVariant)
        Spacer(modifier = Modifier.height(spacing.stackLg))

        Text(text = "Color Palette Tonal Swatches", style = typography.headlineMd, color = colors.onSurface)
        Spacer(modifier = Modifier.height(spacing.stackMd))

        val swatches = listOf(
            "Primary" to colors.primary,
            "Primary Container" to colors.primaryContainer,
            "Background" to colors.background,
            "Surface Container Low" to colors.surfaceContainerLow,
            "Surface Container" to colors.surfaceContainer,
            "Surface Container High" to colors.surfaceContainerHigh,
            "Surface Container Highest" to colors.surfaceContainerHighest,
            "Outline" to colors.outline,
            "Outline Variant" to colors.outlineVariant,
            "Error" to colors.error
        )

        swatches.forEach { (name, color) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(color, shape = WaexTheme.radius.defaultShape)
                        .border(1.dp, colors.outlineVariant, shape = WaexTheme.radius.defaultShape)
                )
                Spacer(modifier = Modifier.width(spacing.gutter))
                Column {
                    Text(text = name, style = typography.bodyLg.copy(fontWeight = FontWeight.SemiBold), color = colors.onSurface)
                    Text(
                        text = "#" + Integer.toHexString(color.value.toInt()).substring(2).uppercase(),
                        style = typography.bodyMd,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Preview(name = "Light Mode - Spec View", showBackground = true)
@Composable
fun LightSpecPreview() {
    WaexTheme(darkTheme = false) {
        TypographyAndColorsSpec()
    }
}

@Preview(name = "Dark Mode - Spec View", showBackground = true)
@Composable
fun DarkSpecPreview() {
    WaexTheme(darkTheme = true) {
        TypographyAndColorsSpec()
    }
}
