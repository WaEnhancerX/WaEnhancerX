package com.waenhancer.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.ui.draw.rotate
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.config.HookingModePolicy
import com.waenhancer.hooking.HookingEngine
import com.waenhancer.ui.components.WaexProChip
import com.waenhancer.ui.components.StitchSwitch
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.components.WaexLinearProgressIndicator
import com.waenhancer.ui.navigation.LocalIsPro
import com.waenhancer.ui.navigation.LocalOnActivatePro
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import org.json.JSONObject

@Composable
fun HookingModeHelpScreen() {
    val nav = LocalWaexNavController.current
    val prefs = LocalWaexPreferenceManager.current
    val isPro = LocalIsPro.current
    val onActivatePro = LocalOnActivatePro.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    val currentMode = remember(prefs) {
        prefs.getString(HookingModePolicy.KEY, HookingModePolicy.BALANCED)
    }
    var activeMode by remember { mutableStateOf(HookingModePolicy.normalize(currentMode, isPro)) }
    var customInstallations by remember {
        mutableStateOf(readCustomInstallations(prefs.getString(HookingModePolicy.CUSTOMIZATIONS_KEY, "{}")))
    }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = "How Hooking Mode works",
                onBackClick = nav::popBack
            )
        },
        containerColor = colors.background
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.pageMargin, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Hero Banner
            Surface(
                shape = radius.bentoCardShape,
                color = colors.primary.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.22f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(colors.primary.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                        text = "Choose when hooks load",
                            style = typography.titleMd,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                        Text(
                            text = "Choose how much loads at launch and what waits until it is needed.",
                            style = typography.bodySm,
                            color = colors.onSurfaceVariant,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            // Always Active Privacy Banner
            Surface(
                shape = radius.cardShape,
                color = colors.surfaceContainerLow,
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(radius.smShape)
                            .background(colors.successContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = colors.onSuccessContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Background features always load",
                            style = typography.bodySm,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                        Text(
                            text = "Privacy, anti-revoke, status, and call features start immediately in every mode so background events are not missed.",
                            style = typography.bodySm,
                            color = colors.onSurfaceVariant,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            // Section Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Profiles",
                    style = typography.labelSm,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary,
                    letterSpacing = 0.sp
                )
            }

            // 1. Lightweight (Pro)
            HookingProfileCard(
                modeKey = HookingModePolicy.LIGHTWEIGHT,
                title = "Lightweight",
                badgeText = "Pro · Lowest memory",
                isRecommended = false,
                isPro = true,
                isProUnlocked = isPro,
                isSelected = activeMode == HookingModePolicy.LIGHTWEIGHT,
                icon = Icons.Rounded.Bolt,
                ramUsage = "<80 MB",
                ramFraction = 0.12f,
                summary = "Zero UI hooks at startup. Visual tweaks load only when their target screens open.",
                pros = listOf(
                    "Lowest background memory use (<80 MB)",
                    "Shortest WhatsApp launch path",
                    "Useful on memory-constrained devices"
                ),
                tradeoff = "First navigation to customized screens may exhibit a brief initial setup pause.",
                onSelect = {
                    if (isPro) {
                        activeMode = HookingModePolicy.LIGHTWEIGHT
                        prefs.putString(HookingModePolicy.KEY, HookingModePolicy.LIGHTWEIGHT)
                    } else {
                        onActivatePro()
                    }
                }
            )

            // 2. Balanced (Recommended)
            HookingProfileCard(
                modeKey = HookingModePolicy.BALANCED,
                title = "Balanced",
                badgeText = "Recommended",
                isRecommended = true,
                isPro = false,
                isProUnlocked = true,
                isSelected = activeMode == HookingModePolicy.BALANCED,
                icon = Icons.Rounded.Speed,
                ramUsage = "~180 MB",
                ramFraction = 0.36f,
                summary = "Loads main Home UI hooks at startup; all other screens load lazily on-demand.",
                pros = listOf(
                    "Home UI and floating bar are ready immediately",
                    "Lower launch overhead than full preloading",
                    "A practical balance of memory and responsiveness"
                ),
                tradeoff = "Minor brief delay on the very first visit to rarely opened sub-screens.",
                onSelect = {
                    activeMode = HookingModePolicy.BALANCED
                    prefs.putString(HookingModePolicy.KEY, HookingModePolicy.BALANCED)
                }
            )

            // 3. Maximum Compatibility
            HookingProfileCard(
                modeKey = HookingModePolicy.COMPATIBILITY,
                title = "Maximum Compatibility",
                badgeText = "Deep Injection",
                isRecommended = false,
                isPro = false,
                isProUnlocked = true,
                isSelected = activeMode == HookingModePolicy.COMPATIBILITY,
                icon = Icons.Rounded.Memory,
                ramUsage = "~500 MB+",
                ramFraction = 0.85f,
                summary = "Pre-loads all visual and system hooks immediately during WhatsApp startup.",
                pros = listOf(
                    "Best compatibility across modified WhatsApp builds",
                    "UI elements and overlays are ready before the first screen",
                    "Avoids on-demand injection delays"
                ),
                tradeoff = "Noticeably higher memory footprint and slower WhatsApp initial cold launch.",
                onSelect = {
                    activeMode = HookingModePolicy.COMPATIBILITY
                    prefs.putString(HookingModePolicy.KEY, HookingModePolicy.COMPATIBILITY)
                }
            )

            HookingProfileCard(
                modeKey = HookingModePolicy.CUSTOMIZED,
                title = "Customized",
                badgeText = "Pro · Per-feature control",
                isRecommended = false,
                isPro = true,
                isProUnlocked = isPro,
                isSelected = activeMode == HookingModePolicy.CUSTOMIZED,
                icon = Icons.Rounded.Tune,
                ramUsage = "Varies",
                ramFraction = 0.36f,
                summary = "Choose Startup or Lazy loading independently for every feature.",
                pros = listOf(
                    "Starts with the Balanced defaults",
                    "Choose launch-time or on-demand loading per feature",
                    "Your choices remain when switching profiles"
                ),
                tradeoff = "Lazy background-sensitive hooks can miss events until their target screen is opened. Restart WhatsApp after changes.",
                onSelect = {
                    if (isPro) {
                        activeMode = HookingModePolicy.CUSTOMIZED
                        prefs.putString(HookingModePolicy.KEY, HookingModePolicy.CUSTOMIZED)
                    } else {
                        onActivatePro()
                    }
                }
            )

            if (activeMode == HookingModePolicy.CUSTOMIZED) {
                Text(
                    text = "Customize features",
                    style = typography.labelSm,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary,
                    letterSpacing = 0.sp
                )
                Text(
                    text = "Startup installs the hook when WhatsApp launches. Lazy installs it when the relevant screen is first opened.",
                    style = typography.bodySm,
                    color = colors.onSurfaceVariant
                )
                Surface(
                    shape = radius.cardShape,
                    color = colors.surfaceContainerLow,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        CUSTOMIZABLE_HOOKS.forEachIndexed { index, className ->
                            val balancedLazy = HookingEngine.effectiveInstallation(
                                HookingEngine.policyFor(className), HookingModePolicy.BALANCED
                            ) != HookingEngine.Installation.STARTUP
                            val lazy = when (customInstallations[className]) {
                                "lazy" -> true
                                "startup" -> false
                                else -> balancedLazy
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = hookDisplayName(className),
                                        style = typography.bodyMd,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = if (lazy) "Lazy (on demand)" else "On startup",
                                        style = typography.labelSm,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                StitchSwitch(
                                    checked = !lazy,
                                    onCheckedChange = { startup ->
                                        customInstallations = customInstallations.toMutableMap().apply {
                                            put(className, if (startup) "startup" else "lazy")
                                        }
                                        prefs.putString(
                                            HookingModePolicy.CUSTOMIZATIONS_KEY,
                                            JSONObject(customInstallations).toString()
                                        )
                                    }
                                )
                            }
                            if (index != CUSTOMIZABLE_HOOKS.lastIndex) {
                                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.45f))
                            }
                        }
                    }
                }
            }

            // Quick Comparison Matrix Table
            Surface(
                shape = radius.cardShape,
                color = colors.surfaceContainerLow,
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "At a glance",
                        style = typography.titleMd,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )

                    // Single Header row with column titles
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(radius.smShape)
                            .background(colors.surfaceContainerHighest.copy(alpha = 0.5f))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Feature",
                            style = typography.labelSm,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.weight(1.3f)
                        )
                        Text(
                            text = "Light",
                            style = typography.labelSm,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "Balanced",
                            style = typography.labelSm,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "Max",
                            style = typography.labelSm,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    ComparisonTableRow(
                        label = "Startup Speed",
                        light = "Fastest",
                        balanced = "Fast",
                        max = "Moderate"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "RAM Footprint",
                        light = "<80 MB",
                        balanced = "~180 MB",
                        max = "~500 MB+"
                    )

                    // 1. Privacy & Protection Hooks (Startup across all modes)
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Anti-Revoke / Anti-Delete",
                        light = "Startup",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Anti-View Once",
                        light = "Startup",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Hide Blue / Read Receipts",
                        light = "Startup",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Blue Ticks on Reply",
                        light = "Startup",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Freeze Last Seen / Ghost",
                        light = "Startup",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Typing / Recording Privacy",
                        light = "Startup",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Call Privacy & Blocking",
                        light = "Startup",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Anti-Disappearing Messages",
                        light = "Startup",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Bootloader Spoofing & Integrity",
                        light = "Startup",
                        balanced = "Startup",
                        max = "Startup"
                    )

                    // 2. Home Screen & Layout Hooks
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Floating Bottom Bar",
                        light = "Lazy",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Home Header Quick Actions",
                        light = "Lazy",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Separate Groups Tab",
                        light = "Lazy",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Instagram Stories Layout",
                        light = "Lazy",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Locked Chats Enhancer",
                        light = "Lazy",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Hidden Chats Vault",
                        light = "Lazy",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Chat Filters Display",
                        light = "Lazy",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Hide Home Tabs",
                        light = "Lazy",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Channel Recommendations",
                        light = "Lazy",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "New Settings Toolbar Style",
                        light = "Lazy",
                        balanced = "Lazy",
                        max = "Startup"
                    )

                    // 3. Conversation & Chat Hooks
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Sticker Confirmation",
                        light = "Lazy",
                        balanced = "Lazy",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Jump to First Message",
                        light = "Lazy",
                        balanced = "Lazy",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Double Tap Reaction",
                        light = "Lazy",
                        balanced = "Lazy",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Chat Bubble Colors",
                        light = "Startup",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Anti-Edit Messages",
                        light = "Startup",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Preserve Delete For Me",
                        light = "Startup",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Pinned & Forward Limits",
                        light = "Startup",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Message Device Source Tag",
                        light = "Startup",
                        balanced = "Startup",
                        max = "Startup"
                    )

                    // 4. Media & Downloader Hooks
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Status Downloader",
                        light = "Lazy",
                        balanced = "Lazy",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Copy Status Text",
                        light = "Lazy",
                        balanced = "Lazy",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Status Style Variants",
                        light = "Lazy",
                        balanced = "Lazy",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Media Preview Button",
                        light = "Lazy",
                        balanced = "Lazy",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Profile Photo Downloader",
                        light = "Lazy",
                        balanced = "Lazy",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Download View Once",
                        light = "Lazy",
                        balanced = "Lazy",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Call Recording (Audio/Video)",
                        light = "Startup",
                        balanced = "Startup",
                        max = "Startup"
                    )

                    // 5. Automation & Customization Hooks
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Tasker Intents Integration",
                        light = "Startup",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Presence Online Toasts",
                        light = "Startup",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Online Dot / Status Text",
                        light = "Startup",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Custom CSS Theme",
                        light = "Startup",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Change Default DPI",
                        light = "Startup",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Custom Timestamp",
                        light = "Lazy",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Filter Views by ID",
                        light = "Startup",
                        balanced = "Startup",
                        max = "Startup"
                    )
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    ComparisonTableRow(
                        label = "Proximity Sensor Control",
                        light = "Lazy",
                        balanced = "Startup",
                        max = "Startup"
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HookingProfileCard(
    modeKey: String,
    title: String,
    badgeText: String,
    isRecommended: Boolean,
    isPro: Boolean = false,
    isProUnlocked: Boolean = true,
    isSelected: Boolean,
    icon: ImageVector,
    ramUsage: String,
    ramFraction: Float,
    summary: String,
    pros: List<String>,
    tradeoff: String,
    onSelect: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var isExpanded by remember { mutableStateOf(false) }

    val targetBorder = if (isSelected) colors.primary else colors.outlineVariant
    val borderColor by animateColorAsState(targetBorder, label = "borderColor")
    val animatedProgress by animateFloatAsState(targetValue = ramFraction, label = "ramProgress")
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "chevronRotation"
    )

    val locked = isPro && !isProUnlocked

    Surface(
        shape = radius.bentoCardShape,
        color = when {
            isSelected -> colors.primary.copy(alpha = 0.05f)
            locked -> colors.surfaceContainerLow.copy(alpha = 0.6f)
            else -> colors.surfaceContainerLow
        },
        border = BorderStroke(if (isSelected) 1.8.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clip(radius.bentoCardShape)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Main clickable header: taps select the mode or expand
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSelect)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) colors.primary.copy(alpha = 0.15f)
                            else colors.surfaceDim
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected) colors.primary else colors.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = title,
                            style = typography.titleMd,
                            fontWeight = FontWeight.Bold,
                            color = if (locked) colors.onSurface.copy(alpha = 0.55f) else colors.onSurface,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isPro) WaexProChip(isUnlocked = isProUnlocked)
                    }

                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Badge
                        Box(
                            modifier = Modifier
                                .clip(radius.smShape)
                                .background(
                                    if (isRecommended) colors.primary.copy(alpha = 0.14f)
                                    else colors.surfaceContainerHighest
                                )
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = badgeText,
                                style = typography.labelSm,
                                fontWeight = FontWeight.Bold,
                                color = if (isRecommended) colors.primary else colors.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }

                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .clip(radius.smShape)
                                    .background(colors.successContainer)
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "ACTIVE",
                                    style = typography.labelSm,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSuccessContainer,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                // Radio Button indicator
                Icon(
                    imageVector = if (isSelected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = if (isSelected) "Selected" else "Unselected",
                    tint = if (isSelected) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(24.dp)
                )
            }

            // Summary description always visible
            Text(
                text = summary,
                style = typography.bodySm,
                color = colors.onSurfaceVariant,
                lineHeight = 18.sp
            )

            // RAM Usage Compact Bar (Always visible overview)
            Surface(
                shape = radius.defaultShape,
                color = colors.surfaceContainerHighest.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Memory,
                                contentDescription = null,
                                tint = colors.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Estimated RAM Usage",
                                style = typography.labelSm,
                                color = colors.onSurfaceVariant
                            )
                        }
                        Text(
                            text = ramUsage,
                            style = typography.labelSm,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) colors.primary else colors.onSurface
                        )
                    }

                    WaexLinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp),
                        color = when {
                            ramFraction <= 0.35f -> colors.success
                            ramFraction <= 0.60f -> colors.primary
                            else -> colors.warning
                        },
                        trackColor = colors.outlineVariant
                    )
                }
            }

            // Expandable details (pros, benefits, tradeoffs)
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + androidx.compose.animation.expandVertically(),
                exit = fadeOut() + androidx.compose.animation.shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Pros List
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        pros.forEach { pro ->
                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = colors.success,
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .size(14.dp)
                                )
                                Text(
                                    text = pro,
                                    style = typography.bodySm,
                                    color = colors.onSurface,
                                    modifier = Modifier.weight(1f),
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }

                    // Trade-off / Note
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .clip(radius.defaultShape)
                            .background(colors.surfaceDim)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Note:",
                            style = typography.labelSm,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurfaceVariant
                        )
                        Text(
                            text = tradeoff,
                            style = typography.bodySm,
                            color = colors.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Expand / Collapse Action button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(radius.defaultShape)
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isExpanded) "Less details" else "More details",
                    style = typography.labelSm,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primary
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = colors.primary,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(chevronRotation)
                )
            }
        }
    }
}

@Composable
private fun ComparisonTableRow(
    label: String,
    light: String,
    balanced: String,
    max: String
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = typography.bodySm,
            fontWeight = FontWeight.Medium,
            color = colors.onSurface,
            modifier = Modifier.weight(1.3f)
        )
        Text(
            text = light,
            style = typography.bodySm,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = balanced,
            style = typography.bodySm,
            fontWeight = FontWeight.Bold,
            color = colors.primary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = max,
            style = typography.bodySm,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun readCustomInstallations(raw: String?): Map<String, String> = try {
    val json = JSONObject(raw ?: "{}")
    buildMap {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.optString(key)
            if (value == "startup" || value == "lazy") put(key, value)
        }
    }
} catch (_: Throwable) {
    emptyMap()
}

private fun hookDisplayName(className: String): String = className
    .removeSuffix("Hook")
    .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")

private val CUSTOMIZABLE_HOOKS = listOf(
    "AntiViewOnceHook", "TypingPrivacyHook", "FreezeLastSeenHook", "AlwaysOnlineHook",
    "HideReceiptsHook", "BlueOnReplyHook", "CallPrivacyHook", "AntiRevokeHook",
    "HideForwardedTagHook", "CustomPrivacyHook", "AutoNextStatusHook",
    "AntiDisappearingMessagesHook", "LockedChatsEnhancerHook", "HideArchivedChatsHook",
    "DndModeHook", "HideChatsVaultHook", "AntiEditMessagesHook", "StickerConfirmHook",
    "JumpFirstMessageHook", "PreserveDeleteForMeHook", "CopyStatusTextHook",
    "ChatLimitsBypassHook", "DoubleTapReactionHook", "MessageDeviceSourceHook",
    "MinorFixesHook", "HomeScreenHeaderActionsHook", "ChatListCustomizationsHook",
    "DownloadViewOnceHook", "StatusDownloadHook", "MediaQualityBypassHook",
    "MediaPreviewHook", "ProximitySensorHook", "CallRecordingHook",
    "ProfilePhotoDownloadHook", "PresenceToastsHook", "TaskerIntegrationHook",
    "ChatBubbleColorsHook", "ChannelRecommendationsFilterHook", "ChatFilterDisplayHook",
    "CustomDpiHook", "CustomTimeHook", "CssThemeHook", "HideHomeTabsHook",
    "StatusStyleHook", "PropsOverrideHook", "NewSettingsStyleHook",
    "OnlinePresenceIndicatorsHook", "SeparateGroupsHook", "FloatingBottomBarHook",
    "FilterItemsHook"
)
