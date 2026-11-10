package com.waenhancer.ui.screens.dashboard

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.components.WaexSectionHeader
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.Screen
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.vector.ImageVector

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardBentoScreen() {
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    // Pulsing green dot animation for module activity indicator
    val infiniteTransition = rememberInfiniteTransition(label = "green_dot_bento")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha_bento"
    )

    Scaffold(
        topBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colors.surface
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .padding(horizontal = spacing.pageMargin),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = WaexIcons.ShieldHeart,
                            contentDescription = UiText.get(R.string.waex_ui_shield_heart, "Shield Heart"),
                            tint = colors.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(spacing.stackSm))
                        Text(
                            text = "WaEnhancerX",
                            style = typography.headlineMd,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .clip(radius.fullShape)
                                .background(colors.surfaceContainerHigh)
                                .border(1.dp, colors.outlineVariant, radius.fullShape)
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = UiText.get(R.string.waex_ui_free_plan, "Free Plan"),
                                style = typography.labelSm,
                                color = colors.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(spacing.stackSm))
                        IconButton(onClick = { navController.navigateTo(Screen.GlobalPrivacySettings) }) {
                            Icon(
                                imageVector = WaexIcons.Settings,
                                contentDescription = UiText.get(R.string.catalog_tab_settings, "Settings"),
                                tint = colors.secondary
                            )
                        }
                    }
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = colors.outlineVariant
                    )
                }
            }
        },
        bottomBar = {
            // Mobile Navigation Bar (strictly portrait mobile viewport)
            Column {
                HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .background(colors.surface)
                        .padding(horizontal = spacing.pageMargin),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { navController.popBack() }
                    ) {
                        Icon(
                            imageVector = WaexIcons.GridView,
                            contentDescription = UiText.get(R.string.waex_ui_dashboard, "Dashboard"),
                            tint = colors.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = UiText.get(R.string.waex_ui_dashboard, "Dashboard"),
                            style = typography.labelSm,
                            color = colors.secondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { }
                    ) {
                        Icon(
                            imageVector = WaexIcons.Extension,
                            contentDescription = UiText.get(R.string.waex_ui_modules, "Modules"),
                            tint = colors.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = UiText.get(R.string.waex_ui_modules, "Modules"),
                            style = typography.labelSm,
                            color = colors.primary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { navController.navigateTo(Screen.LicenseActivation) }
                    ) {
                        Icon(
                            imageVector = WaexIcons.ContactSupport,
                            contentDescription = UiText.get(R.string.support, "Support"),
                            tint = colors.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = UiText.get(R.string.support, "Support"),
                            style = typography.labelSm,
                            color = colors.secondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
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
            // Hero Status Card (Mobile View Stacks Vertically)
            Surface(
                shape = radius.bentoCardShape,
                color = colors.surface,
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(colors.success, CircleShape)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(alphaAnim)
                                    .background(colors.success, CircleShape)
                                    .border(2.dp, Color.White, CircleShape)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = UiText.get(R.string.module_active, "Module Active"),
                            style = typography.headlineMd,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    // Checks List (4 items matching New Bento Layout)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = WaexIcons.Success,
                                contentDescription = null,
                                tint = colors.success,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = UiText.get(R.string.waex_ui_xposed_framework_connected, "Xposed Framework Connected"),
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = WaexIcons.Success,
                                contentDescription = null,
                                tint = colors.success,
                                modifier = Modifier.size(18.dp)
                                )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = UiText.get(R.string.waex_ui_whatsapp_hooked, "WhatsApp Hooked"),
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = WaexIcons.Success,
                                contentDescription = null,
                                tint = colors.success,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = UiText.get(R.string.waex_ui_engine_running, "Engine Running"),
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = WaexIcons.Success,
                                contentDescription = null,
                                tint = colors.success,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = UiText.get(R.string.waex_ui_compatibility_verified, "Compatibility Verified"),
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Metrics Stacking Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Success block
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(radius.cardShape)
                                .background(colors.background)
                                .border(1.dp, colors.outlineVariant, radius.cardShape)
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "98.6%",
                                style = typography.displayLgMobile,
                                color = colors.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = UiText.get(R.string.waex_ui_hook_success, "HOOK SUCCESS"),
                                style = typography.labelSm,
                                color = colors.secondary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Counts block
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(radius.cardShape)
                                    .background(colors.background)
                                    .border(1.dp, colors.outlineVariant, radius.cardShape)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = UiText.get(R.string.waex_ui_hooks, "Hooks"),
                                    style = typography.bodyMd,
                                    color = colors.onSurfaceVariant
                                )
                                Text(
                                    text = "1,024",
                                    style = typography.bodyMd,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSurface
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(radius.cardShape)
                                    .background(colors.background)
                                    .border(1.dp, colors.outlineVariant, radius.cardShape)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = UiText.get(R.string.waex_ui_features, "Features"),
                                    style = typography.bodyMd,
                                    color = colors.onSurfaceVariant
                                )
                                Text(
                                    text = "42",
                                    style = typography.bodyMd,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSurface
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacing.stackLg))

            // Bento Grid (Vertical stacks for mobile view)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Privacy vertical card
                Surface(
                    shape = radius.cardShape,
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(radius.defaultShape)
                                    .background(colors.background)
                                    .border(1.dp, colors.outlineVariant, radius.defaultShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = WaexIcons.Security,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = UiText.get(R.string.nav_controls, "Controls"),
                                style = typography.bodyLg,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = UiText.get(R.string.waex_ui_advanced_protection_for_your_messaging, "Advanced protection for your messaging experience. Control visibility and message persistence."),
                            style = typography.bodyMd,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val list = listOf(UiText.get(R.string.waex_ui_ghost_mode, "Ghost Mode"), UiText.get(R.string.waex_ui_anti_revoke_label, "Anti Revoke"), UiText.get(R.string.waex_ui_hide_seen, "Hide Seen"), UiText.get(R.string.waex_ui_hide_view_status, "Hide View Status"))
                            list.forEach { tagText ->
                                Box(
                                    modifier = Modifier
                                        .clip(radius.fullShape)
                                        .background(colors.background)
                                        .border(1.dp, colors.outlineVariant, radius.fullShape)
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = tagText,
                                        style = typography.labelSm,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { navController.navigateTo(Screen.GlobalPrivacySettings) },
                            shape = radius.defaultShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primary,
                                contentColor = colors.onPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = UiText.get(R.string.waex_ui_manage_privacy_settings, "Manage Privacy Settings"),
                                style = typography.labelSm,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Media
                WaexBentoItemMobile(
                    title = UiText.get(R.string.catalog_tab_media, "Media"),
                    icon = WaexIcons.Image,
                    chips = listOf(UiText.get(R.string.waex_ui_hd_upload, "HD Upload"), UiText.get(R.string.waex_ui_status_download, "Status Download"), UiText.get(R.string.feat_anti_view_once_title, "Unlimited View Once"), UiText.get(R.string.feat_send_audio_as_voice_status_title, "Audio to Voice Status")),
                    onManageClick = { navController.navigateTo(Screen.MediaStatusHub) }
                )

                // Customization
                WaexBentoItemMobile(
                    title = UiText.get(R.string.customization_coming_soon_title, "Customization"),
                    icon = WaexIcons.Palette,
                    chips = listOf(UiText.get(R.string.waex_ui_pill_design, "Pill Design"), UiText.get(R.string.waex_ui_custom_status_layout, "Custom Status Layout"), UiText.get(R.string.waex_ui_menu_icons, "Menu Icons"), UiText.get(R.string.waex_ui_animated_emojis, "Animated Emojis")),
                    onManageClick = { navController.navigateTo(Screen.ConversationEnhancements) }
                )

                // Automation
                Surface(
                    shape = radius.cardShape,
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(radius.defaultShape)
                                    .background(colors.background)
                                    .border(1.dp, colors.outlineVariant, radius.defaultShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = WaexIcons.AutoAwesome,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = UiText.get(R.string.subtab_automation, "Automation"),
                                style = typography.bodyLg,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val list = listOf(UiText.get(R.string.waex_ui_always_typing, "Always Typing"), UiText.get(R.string.waex_ui_auto_reply, "Auto Reply"), UiText.get(R.string.waex_ui_status_forward, "Status Forward"), "Tasker")
                            list.forEach { tagText ->
                                Box(
                                    modifier = Modifier
                                        .clip(radius.fullShape)
                                        .background(colors.background)
                                        .border(1.dp, colors.outlineVariant, radius.fullShape)
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = tagText,
                                        style = typography.labelSm,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { navController.navigateTo(Screen.AutomationTasker) },
                            shape = radius.defaultShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.background,
                                contentColor = colors.onSurface
                            ),
                            border = BorderStroke(1.dp, colors.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = UiText.get(R.string.waex_ui_manage_automation, "Manage Automation"),
                                style = typography.labelSm,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacing.stackLg))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WaexBentoItemMobile(
    title: String,
    icon: ImageVector,
    chips: List<String>,
    onManageClick: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    Surface(
        shape = radius.cardShape,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(radius.defaultShape)
                        .background(colors.background)
                        .border(1.dp, colors.outlineVariant, radius.defaultShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = typography.bodyLg,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                chips.forEach { tagText ->
                    Box(
                        modifier = Modifier
                            .clip(radius.fullShape)
                            .background(colors.background)
                            .border(1.dp, colors.outlineVariant, radius.fullShape)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = tagText,
                            style = typography.labelSm,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onManageClick,
                shape = radius.defaultShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.background,
                    contentColor = colors.onSurface
                ),
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = UiText.get(R.string.waex_ui_manage_1_s, "Manage %1\$s", title),
                    style = typography.labelSm,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DashboardBentoScreenPreview() {
    WaexTheme {
        DashboardBentoScreen()
    }
}
