package com.waenhancer.ui.screens.dashboard

import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme

import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.Screen
import com.waenhancer.utils.UniversalVersionValidator
import com.waenhancer.xposed.utils.AppRestartHelper
import com.waenhancer.xposed.utils.ModuleStatus

@Composable
fun MainDashboardScreen(
    licenseState: String,
    onOpenModal: (String) -> Unit
) {
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val context = LocalContext.current
    val isModuleActive = remember { ModuleStatus.isModuleActive() }



    var refreshTrigger by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    // Re-check target apps on resume and whenever target app sends active broadcast
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                refreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: android.content.Intent?) {
                refreshTrigger++
            }
        }
        val filter = android.content.IntentFilter("com.waenhancer.ACTION_TARGET_APP_ACTIVE")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Throwable) {}
        }
    }

    // Dynamic WhatsApp Packages & Clones Detector
    val detectedApps = remember(refreshTrigger) {
        com.waenhancer.utils.WhatsAppPackageDetector.detectWhatsAppApps(context)
    }

    val prefs = remember(context) { com.waenhancer.config.PreferenceStores.publicStore(context) }
    val hasUnsupportedActiveApp = detectedApps.any {
        it.isInstalled && it.isHooked && !UniversalVersionValidator.isSupported(context, it.versionName.removePrefix("v"), prefs)
    }
    val moduleVersion = remember(context) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }

    var showCustomizationSoon by remember { mutableStateOf(false) }

    // Pulsing green dot animation for active status indicator
    val infiniteTransition = rememberInfiniteTransition(label = "green_dot")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.pageMargin, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Module Status & Core Target Card
            Surface(
                shape = radius.cardShape,
                color = colors.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (hasUnsupportedActiveApp) Color(0xFFEF4444).copy(alpha = 0.5f) else colors.outline
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Header row: Status Dot + Title + Changelog pill
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        !isModuleActive -> colors.error
                                        hasUnsupportedActiveApp -> Color(0xFFEF4444)
                                        else -> colors.primary
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = when {
                                !isModuleActive -> "Module Inactive • v$moduleVersion"
                                hasUnsupportedActiveApp -> "Module Active • v$moduleVersion • Unsupported Target"
                                else -> "Module Active • v$moduleVersion"
                            },
                            style = typography.bodyLg,
                            fontWeight = FontWeight.SemiBold,
                            color = if (hasUnsupportedActiveApp) Color(0xFFEF4444) else colors.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .clip(radius.smShape)
                                .background(colors.surfaceDim)
                                .border(1.dp, colors.outlineVariant, radius.smShape)
                                .clickable { navController.navigateTo(Screen.Changelog) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (moduleVersion.contains("-beta-")) "Beta" else "Stable",
                                style = typography.labelSm,
                                color = colors.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)
                    Spacer(modifier = Modifier.height(14.dp))

                    // Target Apps: Vertical List of Cards with App Icon, Title, Package Name, True Scope Status Dot, and Restart Action
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        detectedApps.forEach { app ->
                            val cleanVer = app.versionName.removePrefix("v")
                            val isVersionSupported = UniversalVersionValidator.isSupported(context, cleanVer, prefs)
                            val appIcon = remember(app.packageName) {
                                com.waenhancer.utils.WhatsAppPackageDetector.getAppIcon(context, app.packageName)
                            }

                            val (statusDotColor, statusText, statusTextColor) = when {
                                !app.isInstalled -> Triple(
                                    colors.onSurfaceVariant.copy(alpha = 0.4f),
                                    "Not Installed",
                                    colors.onSurfaceVariant
                                )
                                app.isHooked && isVersionSupported -> Triple(
                                    colors.primary,
                                    "Injected & Active",
                                    colors.primary
                                )
                                app.isHooked && !isVersionSupported -> Triple(
                                    Color(0xFFEF4444),
                                    "Injected • Unsupported Version (Paused)",
                                    Color(0xFFEF4444)
                                )
                                !app.isHooked && !isVersionSupported -> Triple(
                                    Color(0xFFF59E0B),
                                    "Scope Pending • Unsupported Version",
                                    Color(0xFFF59E0B)
                                )
                                else -> Triple(
                                    Color(0xFFF59E0B),
                                    "Scope Pending / Not Injected",
                                    Color(0xFFF59E0B)
                                )
                            }

                            Surface(
                                shape = radius.defaultShape,
                                color = colors.surfaceDim,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (app.isInstalled && !isVersionSupported) Color(0xFFEF4444).copy(alpha = 0.4f) else colors.outlineVariant
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (app.isInstalled && !isVersionSupported) {
                                            Modifier.clickable { navController.navigateTo(Screen.SupportedVersions) }
                                        } else {
                                            Modifier
                                        }
                                    )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // App Icon Container
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(colors.surface),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (appIcon != null) {
                                            androidx.compose.foundation.Image(
                                                bitmap = appIcon,
                                                contentDescription = app.appName,
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .clip(CircleShape)
                                            )
                                        } else {
                                            Icon(
                                                imageVector = WaexIcons.Security,
                                                contentDescription = app.appName,
                                                tint = colors.primary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    // App Info & Status Column
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = app.appName,
                                            style = typography.bodyMd,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.onSurface,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )

                                        Text(
                                            text = "${app.packageName} • ${app.versionName}",
                                            style = typography.labelSm,
                                            color = colors.onSurfaceVariant.copy(alpha = 0.85f),
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )

                                        Spacer(modifier = Modifier.height(4.dp))

                                        // Status Dot & Label
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .clip(CircleShape)
                                                    .background(statusDotColor)
                                            )
                                            Text(
                                                text = statusText,
                                                style = typography.labelSm,
                                                color = statusTextColor,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }

                                    // Restart Action Button
                                    if (app.isInstalled) {
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(colors.surface)
                                                .border(1.dp, colors.outlineVariant, CircleShape)
                                                .clickable {
                                                    com.waenhancer.xposed.utils.AppRestartHelper.restartPackage(
                                                        context,
                                                        app.packageName,
                                                        app.appName
                                                    )
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = WaexIcons.Refresh,
                                                contentDescription = "Restart App",
                                                tint = colors.onSurfaceVariant,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }



            // System Information List Card
            Surface(
                shape = radius.cardShape,
                color = colors.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "System Diagnostics",
                        style = typography.bodyMd,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val specs = listOf(
                        "Device" to "${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL}",
                        "Android" to "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
                        "Xposed Framework" to "LSPosed / DexKit v2.0"
                    )

                    specs.forEachIndexed { index, (label, value) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = label,
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = value,
                                style = typography.bodyMd,
                                fontWeight = FontWeight.Medium,
                                color = colors.onSurface
                            )
                        }
                        if (index < specs.lastIndex) {
                            HorizontalDivider(
                                thickness = 1.dp,
                                color = colors.outlineVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(vertical = 10.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { navController.navigateTo(Screen.SupportedVersions) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Supported Versions Catalog",
                            style = typography.bodyMd,
                            color = colors.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = WaexIcons.ChevronRight,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Quick Backup & Maintenance Row
            Surface(
                shape = radius.cardShape,
                color = colors.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(radius.defaultShape)
                            .background(colors.surfaceDim)
                            .border(1.dp, colors.outlineVariant, radius.defaultShape)
                            .clickable { /* Backup Action */ }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = WaexIcons.Folder, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Backup", style = typography.bodyMd, fontWeight = FontWeight.Medium, color = colors.onSurface)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(radius.defaultShape)
                            .background(colors.surfaceDim)
                            .border(1.dp, colors.outlineVariant, radius.defaultShape)
                            .clickable { /* Restore Action */ }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = WaexIcons.Refresh, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Restore", style = typography.bodyMd, fontWeight = FontWeight.Medium, color = colors.onSurface)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }


        // Customization Coming Soon Modal Overlay
        if (showCustomizationSoon) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { showCustomizationSoon = false },
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = radius.lgShape,
                    color = colors.surface,
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .clickable(enabled = false) { }
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(colors.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = WaexIcons.Palette,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Text(
                            text = "Customization",
                            style = typography.headlineMd,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                        Text(
                            text = "Customization settings coming soon",
                            style = typography.bodyMd,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = { showCustomizationSoon = false },
                            shape = radius.mdShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primary,
                                contentColor = colors.onPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Text(text = "OK", style = typography.bodyMd, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StandardNavCard(

    title: String,
    desc: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    Surface(
        shape = radius.cardShape,
        color = colors.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline),
        modifier = modifier.clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(radius.defaultShape)
                    .background(colors.surfaceDim),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Text(
                text = title,
                style = typography.bodyMd,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )

            Text(
                text = desc,
                style = typography.labelSm,
                color = colors.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
