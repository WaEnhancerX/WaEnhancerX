package com.waenhancer.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.components.FileSizeSpooferModal
import com.waenhancer.ui.components.LicenseActivationModal
import com.waenhancer.ui.components.MessageBomberModal
import com.waenhancer.ui.components.StatusVideoSplitterModal
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.screens.audio.AudioTranscriptionScreen
import com.waenhancer.ui.screens.automation.AutomationTaskerScreen
import com.waenhancer.ui.screens.conversation.ConversationEnhancementsScreen
import com.waenhancer.ui.screens.conversation.MessageBomberProScreen
import com.waenhancer.ui.screens.dashboard.MainDashboardScreen
import com.waenhancer.ui.screens.license.LicenseActivationScreen
import com.waenhancer.ui.screens.media.FileSizeSpooferProScreen
import com.waenhancer.ui.screens.media.MediaStatusHubScreen
import com.waenhancer.ui.screens.media.StatusVideoSplitterProScreen
import com.waenhancer.ui.screens.privacy.GlobalPrivacySettingsScreen
import com.waenhancer.ui.screens.privacy.PerContactPrivacyModal
import com.waenhancer.ui.screens.pro.ProUpgradePaywallScreen
import com.waenhancer.ui.screens.settings.SystemHealthScreen

class WaexNavController(initialScreen: Screen = Screen.MainDashboard) {
    private val backstack = mutableStateListOf<Screen>(initialScreen)
    
    var currentScreen by mutableStateOf<Screen>(initialScreen)
        private set

    fun navigateTo(screen: Screen, clearStack: Boolean = false) {
        if (clearStack) {
            backstack.clear()
            backstack.add(Screen.MainDashboard)
            if (screen != Screen.MainDashboard) {
                backstack.add(screen)
            }
        } else {
            backstack.add(screen)
        }
        currentScreen = screen
    }

    fun popBack(): Boolean {
        if (backstack.size > 1) {
            backstack.removeAt(backstack.size - 1)
            currentScreen = backstack.last()
            return true
        }
        return false
    }
}

val LocalWaexNavController = staticCompositionLocalOf { WaexNavController() }

enum class BottomTab(
    val id: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val screen: Screen
) {
    HOME("home", "Home", WaexIcons.GridView, Screen.MainDashboard),
    PRIVACY("privacy", "Privacy", WaexIcons.Security, Screen.GlobalPrivacySettings),
    MEDIA("media", "Media", WaexIcons.Image, Screen.MediaStatusHub),
    AUTOMATION("automation", "Automation", WaexIcons.AutoAwesome, Screen.AutomationTasker),
    PRO("pro", "Pro", WaexIcons.Premium, Screen.ProUpgradePaywall)
}

@Composable
fun MainContainerScreen() {
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var licenseState by remember { mutableStateOf("free") } // "free" | "pro" | "trial"
    var activeModal by remember { mutableStateOf<String?>(null) } // "license" | "file-spoofer" | "message-bomber" | "status-splitter" | "per-contact" | null

    val themeMode = com.waenhancer.ui.designsystem.LocalThemeMode.current
    val updateThemeMode = com.waenhancer.ui.designsystem.LocalThemeModeUpdater.current
    var showThemeMenu by remember { mutableStateOf(false) }

    val currentScreen = navController.currentScreen
    val isRoot = when (currentScreen) {
        Screen.MainDashboard,
        Screen.GlobalPrivacySettings,
        Screen.MediaStatusHub,
        Screen.AutomationTasker,
        Screen.ProUpgradePaywall -> true
        else -> false
    }

    val pagerState = rememberPagerState { 5 }
    val coroutineScope = rememberCoroutineScope()

    // Sync from pager scroll to navController (only when scroll has settled to avoid feedback loops)
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) {
            val targetScreen = when (pagerState.currentPage) {
                0 -> Screen.MainDashboard
                1 -> Screen.GlobalPrivacySettings
                2 -> Screen.MediaStatusHub
                3 -> Screen.AutomationTasker
                4 -> Screen.ProUpgradePaywall
                else -> Screen.MainDashboard
            }
            if (navController.currentScreen != targetScreen && isRoot) {
                navController.navigateTo(targetScreen, clearStack = true)
            }
        }
    }

    // Sync from navController to pager programmatically
    LaunchedEffect(currentScreen) {
        val targetPage = when (currentScreen) {
            Screen.MainDashboard -> 0
            Screen.GlobalPrivacySettings -> 1
            Screen.MediaStatusHub -> 2
            Screen.AutomationTasker -> 3
            Screen.ProUpgradePaywall -> 4
            else -> -1
        }
        if (targetPage != -1 && pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    val title = when (currentScreen) {
        Screen.MainDashboard -> "WaEnhancerX"
        Screen.SystemHealth -> "System Health"
        Screen.ProUpgradePaywall -> "Pro Upgrade"
        Screen.GlobalPrivacySettings -> "Privacy"
        Screen.MediaStatusHub -> "Media & Status"
        Screen.AutomationTasker -> "Automation"
        else -> "WaEnhancerX"
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.surface
                ) {
                    Column(
                        modifier = Modifier.statusBarsPadding()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                                .padding(horizontal = spacing.pageMargin),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!isRoot) {
                                IconButton(
                                    onClick = { navController.popBack() },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .align(Alignment.CenterVertically)
                                ) {
                                    Icon(
                                        imageVector = WaexIcons.Back,
                                        contentDescription = "Back",
                                        tint = colors.onBackground,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                            }

                            Text(
                                text = title,
                                style = typography.headlineMd,
                                fontWeight = FontWeight.Bold,
                                color = colors.onBackground,
                                modifier = Modifier
                                    .weight(1f)
                                    .align(Alignment.CenterVertically)
                            )

                            // Quick Pro Tools in top bar on Pro screen when Pro is Active
                            if (currentScreen == Screen.ProUpgradePaywall && licenseState == "pro") {
                                IconButton(
                                    onClick = { activeModal = "file-spoofer" },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .align(Alignment.CenterVertically)
                                ) {
                                    Icon(
                                        imageVector = WaexIcons.Lock,
                                        contentDescription = "File Spoofer",
                                        tint = colors.onBackground,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = { activeModal = "message-bomber" },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .align(Alignment.CenterVertically)
                                ) {
                                    Icon(
                                        imageVector = WaexIcons.Mic,
                                        contentDescription = "Message Bomber",
                                        tint = colors.onBackground,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = { activeModal = "status-splitter" },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .align(Alignment.CenterVertically)
                                ) {
                                    Icon(
                                        imageVector = WaexIcons.SystemUpdate,
                                        contentDescription = "Status Splitter",
                                        tint = colors.onBackground,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }

                            // License Chip
                            val chipBg = when (licenseState) {
                                "pro" -> colors.primaryContainer
                                "trial" -> Color(0xFFFFF8E1)
                                else -> colors.surfaceDim
                            }
                            val chipText = when (licenseState) {
                                "pro" -> colors.primary
                                "trial" -> Color(0xFFFFB300)
                                else -> colors.onSurfaceVariant
                            }
                            val chipLabel = when (licenseState) {
                                "pro" -> "Pro Active"
                                "trial" -> "Trial Active"
                                else -> "Free Plan"
                            }

                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(chipBg)
                                    .border(1.dp, chipText.copy(alpha = 0.2f), CircleShape)
                                    .clickable { activeModal = "license" }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                    .align(Alignment.CenterVertically),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = chipLabel,
                                    style = typography.labelSm,
                                    fontWeight = FontWeight.Medium,
                                    color = chipText
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            IconButton(
                                onClick = { /* Search action to be implemented later */ },
                                modifier = Modifier
                                    .size(32.dp)
                                    .align(Alignment.CenterVertically)
                            ) {
                                Icon(
                                    imageVector = WaexIcons.Search,
                                    contentDescription = "Search",
                                    tint = colors.onBackground,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Box(
                                modifier = Modifier.align(Alignment.CenterVertically)
                            ) {
                                val themeIcon = when (themeMode) {
                                    "Light" -> WaexIcons.LightMode
                                    "Dark" -> WaexIcons.DarkMode
                                    else -> WaexIcons.AutoMode
                                }
                                IconButton(
                                    onClick = { showThemeMenu = true },
                                    modifier = Modifier
                                        .size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = themeIcon,
                                        contentDescription = "Theme Mode",
                                        tint = colors.onBackground,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                DropdownMenu(
                                    expanded = showThemeMenu,
                                    onDismissRequest = { showThemeMenu = false },
                                    modifier = Modifier.background(colors.surface)
                                ) {
                                    val modes = listOf("Light", "Dark", "System")
                                    modes.forEach { mode ->
                                        val active = themeMode == mode
                                        DropdownMenuItem(
                                            text = {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    val modeIcon = when (mode) {
                                                        "Light" -> WaexIcons.LightMode
                                                        "Dark" -> WaexIcons.DarkMode
                                                        else -> WaexIcons.AutoMode
                                                    }
                                                    Icon(
                                                        imageVector = modeIcon,
                                                        contentDescription = null,
                                                        tint = if (active) colors.primary else colors.onSurfaceVariant,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Text(
                                                        text = mode,
                                                        style = typography.bodyMd,
                                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (active) colors.primary else colors.onSurface
                                                    )
                                                    if (active) {
                                                        Spacer(modifier = Modifier.weight(1f))
                                                        Icon(
                                                            imageVector = WaexIcons.Check,
                                                            contentDescription = "Active",
                                                            tint = colors.primary,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                updateThemeMode(mode)
                                                showThemeMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)
                    }
                }
            },
            bottomBar = {
                if (isRoot) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Transparent)
                            .navigationBarsPadding()
                            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp, top = 8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = colors.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                            shadowElevation = 8.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                BottomTab.values().forEach { tab ->
                                    val active = when (currentScreen) {
                                        Screen.MainDashboard -> tab == BottomTab.HOME
                                        Screen.GlobalPrivacySettings -> tab == BottomTab.PRIVACY
                                        Screen.MediaStatusHub -> tab == BottomTab.MEDIA
                                        Screen.AutomationTasker -> tab == BottomTab.AUTOMATION
                                        Screen.ProUpgradePaywall -> tab == BottomTab.PRO
                                        else -> tab == BottomTab.HOME
                                    }

                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .weight(1f)
                                            .clickable {
                                                val pageIndex = when (tab) {
                                                    BottomTab.HOME -> 0
                                                    BottomTab.PRIVACY -> 1
                                                    BottomTab.MEDIA -> 2
                                                    BottomTab.AUTOMATION -> 3
                                                    BottomTab.PRO -> 4
                                                }
                                                coroutineScope.launch {
                                                    pagerState.animateScrollToPage(pageIndex)
                                                }
                                            }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(width = 40.dp, height = 24.dp)
                                                .clip(CircleShape)
                                                .background(if (active) colors.primaryContainer else Color.Transparent),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = tab.icon,
                                                contentDescription = tab.label,
                                                tint = if (active) colors.primary else colors.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = tab.label,
                                            style = typography.labelSm,
                                            fontWeight = FontWeight.Medium,
                                            color = if (active) colors.primary else colors.onSurfaceVariant,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            containerColor = colors.background
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = paddingValues.calculateTopPadding(),
                        bottom = if (isRoot) 0.dp else paddingValues.calculateBottomPadding()
                    )
            ) {
                if (isRoot) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { pageIndex ->
                        val pageScreen = when (pageIndex) {
                            0 -> Screen.MainDashboard
                            1 -> Screen.GlobalPrivacySettings
                            2 -> Screen.MediaStatusHub
                            3 -> Screen.AutomationTasker
                            4 -> Screen.ProUpgradePaywall
                            else -> Screen.MainDashboard
                        }
                        Box(modifier = Modifier.fillMaxSize()) {
                            WaexAppNavigation(
                                currentScreen = pageScreen,
                                licenseState = licenseState,
                                onOpenModal = { activeModal = it },
                                onActivatePro = { licenseState = "pro" }
                            )
                        }
                    }
                } else {
                    WaexAppNavigation(
                        currentScreen = currentScreen,
                        licenseState = licenseState,
                        onOpenModal = { activeModal = it },
                        onActivatePro = { licenseState = "pro" }
                    )
                }
            }
        }

        // Animated Overlay Modals
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Animated overlay background
            AnimatedVisibility(
                visible = activeModal != null,
                enter = fadeIn(animationSpec = tween(durationMillis = 300)),
                exit = fadeOut(animationSpec = tween(durationMillis = 250)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable { activeModal = null }
                )
            }

            // Animated bottom sheet content
            AnimatedVisibility(
                visible = activeModal != null,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(durationMillis = 350)
                ) + fadeIn(animationSpec = tween(durationMillis = 300)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(durationMillis = 300)
                ) + fadeOut(animationSpec = tween(durationMillis = 250)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = false) { }
                        .clip(radius.bottomSheetShape),
                    color = colors.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(top = 12.dp, bottom = 24.dp)
                    ) {
                        when (activeModal) {
                            "license" -> LicenseActivationModal(
                                onDismiss = { activeModal = null },
                                onActivated = {
                                    licenseState = "pro"
                                    activeModal = null
                                }
                            )
                            "file-spoofer" -> FileSizeSpooferModal(onDismiss = { activeModal = null })
                            "message-bomber" -> MessageBomberModal(onDismiss = { activeModal = null })
                            "status-splitter" -> StatusVideoSplitterModal(onDismiss = { activeModal = null })
                            "per-contact" -> PerContactPrivacyModal(onDismiss = { activeModal = null })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WaexAppNavigation(
    currentScreen: Screen,
    licenseState: String,
    onOpenModal: (String) -> Unit,
    onActivatePro: () -> Unit
) {
    when (currentScreen) {
        Screen.MainDashboard -> MainDashboardScreen(
            licenseState = licenseState,
            onOpenModal = onOpenModal
        )
        Screen.DashboardBento -> MainDashboardScreen(
            licenseState = licenseState,
            onOpenModal = onOpenModal
        )
        Screen.SystemHealth -> SystemHealthScreen()
        Screen.GlobalPrivacySettings -> GlobalPrivacySettingsScreen(
            onOpenModal = onOpenModal
        )
        Screen.ConversationEnhancements -> ConversationEnhancementsScreen()
        Screen.MediaStatusHub -> MediaStatusHubScreen()
        Screen.AutomationTasker -> AutomationTaskerScreen()
        Screen.AudioTranscription -> AudioTranscriptionScreen()
        Screen.LicenseActivation -> LicenseActivationScreen()
        Screen.ProUpgradePaywall -> ProUpgradePaywallScreen(
            onOpenModal = onOpenModal,
            onActivatePro = onActivatePro
        )
        Screen.MessageBomberPro -> MessageBomberProScreen()
        Screen.FileSizeSpooferPro -> FileSizeSpooferProScreen()
        Screen.StatusVideoSplitterPro -> StatusVideoSplitterProScreen()
    }
}
