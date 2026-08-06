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

@Composable
fun SupportedVersionsScreen() {
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var selectedTab by remember { mutableStateOf("wpp") } // "wpp" | "business"

    val wppVersions = listOf(
        "2.24.25.xx (Wildcard Recommended)",
        "2.24.25.17 (Verified Stable)",
        "2.24.24.81 (Verified Stable)",
        "2.24.23.78 (Verified Stable)",
        "2.24.22.79 (Verified Stable)",
        "2.24.21.79 (Legacy)"
    )

    val businessVersions = listOf(
        "2.24.25.xx (Wildcard Recommended)",
        "2.24.25.12 (Verified Stable)",
        "2.24.24.78 (Verified Stable)",
        "2.24.23.75 (Verified Stable)",
        "2.24.22.75 (Legacy)"
    )

    val activeList = if (selectedTab == "wpp") wppVersions else businessVersions

    Scaffold(
        topBar = {
            WaexTopBar(
                title = "Supported Versions",
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
            // Target Selector Tabs
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    listOf("wpp" to "WhatsApp", "business" to "WhatsApp Business").forEach { (tabId, label) ->
                        val active = selectedTab == tabId
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(radius.defaultShape)
                                .background(if (active) colors.primary.copy(alpha = 0.12f) else colors.surfaceDim)
                                .border(
                                    1.dp,
                                    if (active) colors.primary else colors.outlineVariant,
                                    radius.defaultShape
                                )
                                .clickable { selectedTab = tabId }
                                .padding(vertical = 12.dp),
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

            // Info Card
            item {
                Surface(
                    shape = radius.bentoCardShape,
                    color = colors.primary.copy(alpha = 0.08f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.primary.copy(alpha = 0.2f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = WaexIcons.Info,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "DexKit signatures dynamically resolve hooks for all minor patches matching wildcard builds (e.g. 2.24.25.xx).",
                            style = typography.bodyMd,
                            color = colors.onSurface,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            // List of Supported Versions
            items(activeList) { versionString ->
                Surface(
                    shape = radius.bentoCardShape,
                    color = colors.surfaceDim,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF22C55E))
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = versionString,
                            style = typography.bodyLg,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = WaexIcons.Success,
                            contentDescription = "Supported",
                            tint = Color(0xFF22C55E),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
