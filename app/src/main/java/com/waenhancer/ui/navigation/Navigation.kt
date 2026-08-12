package com.waenhancer.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
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
import com.waenhancer.ui.screens.privacy.PerContactPrivacyListScreen
import com.waenhancer.ui.screens.pro.ProUpgradePaywallScreen

import com.waenhancer.ui.screens.settings.SystemHealthScreen
import com.waenhancer.ui.screens.settings.ChangelogScreen
import com.waenhancer.ui.screens.settings.AboutScreen
import com.waenhancer.ui.screens.settings.UpdateSettingsScreen
import com.waenhancer.ui.screens.settings.SupportedVersionsScreen

class WaexNavController(initialScreen: Screen = Screen.MainDashboard) {

    private val backstack = mutableStateListOf<Screen>(initialScreen)
    
    var currentScreen by mutableStateOf<Screen>(initialScreen)
        private set

    var isLastTransitionForward by mutableStateOf(true)
        private set

    val canPop: Boolean
        get() = backstack.size > 1

    var targetPageIndex by mutableStateOf(-1)
    var targetSubTabId by mutableStateOf<String?>(null)
    var scrollToTargetKey by mutableStateOf<String?>(null)
    var highlightTargetKey by mutableStateOf<String?>(null)

    fun navigateToPreference(tabIndex: Int, subTabId: String?, preferenceKey: String) {
        navigateTo(Screen.MainDashboard, clearStack = true)
        targetPageIndex = tabIndex
        targetSubTabId = subTabId
        scrollToTargetKey = preferenceKey
        highlightTargetKey = preferenceKey
    }

    fun navigateTo(screen: Screen, clearStack: Boolean = false) {
        isLastTransitionForward = !(screen.isRootScreen || clearStack)
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
            isLastTransitionForward = false
            backstack.removeAt(backstack.size - 1)
            currentScreen = backstack.last()
            return true
        }
        return false
    }
}

val LocalWaexNavController = staticCompositionLocalOf { WaexNavController() }

val LocalWaexPreferenceManager = staticCompositionLocalOf<com.waenhancer.api.contracts.WaexPreferenceManager> {
    error("No WaexPreferenceManager provided")
}
val LocalWaexPreferenceRepository = staticCompositionLocalOf<com.waenhancer.api.contracts.WaexPreferenceRepository> {
    error("No WaexPreferenceRepository provided")
}
val LocalWaexLicenseManager = staticCompositionLocalOf<com.waenhancer.api.contracts.WaexLicenseManager> {
    error("No WaexLicenseManager provided")
}


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
    STYLES("styles", "Styles", WaexIcons.Palette, Screen.StylesSettings)
}

@Composable
fun MainContainerScreen() {
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    val licenseManager = LocalWaexLicenseManager.current
    var licenseState by remember { mutableStateOf(if (licenseManager.isProActivated()) "pro" else "free") }
    var activeModal by remember { mutableStateOf<String?>(null) } // "license" | "file-spoofer" | "message-bomber" | "status-splitter" | null

    val contactPrivacyList = remember {
        mutableStateListOf(
            ContactPrivacy("1", "Alex Johnson", "+1 555-0192@s.whatsapp.net", hideSeen = true, hideTyping = true, scope = "always"),
            ContactPrivacy("2", "Fatima Al-Rashid", "+971 50-0001@s.whatsapp.net", hideTyping = true, antiRevoke = true, scope = "scheduled"),
            ContactPrivacy("3", "James Okafor", "+234 80-2020@s.whatsapp.net", freezeLastSeen = true, hideRecording = true, scope = "temporary")
        )
    }

    val themeMode = com.waenhancer.ui.designsystem.LocalThemeMode.current
    val updateThemeMode = com.waenhancer.ui.designsystem.LocalThemeModeUpdater.current
    var showThemeMenu by remember { mutableStateOf(false) }

    val currentScreen = navController.currentScreen
    val isRoot = when (currentScreen) {
        Screen.MainDashboard,
        Screen.GlobalPrivacySettings,
        Screen.MediaStatusHub,
        Screen.AutomationTasker,
        Screen.StylesSettings -> true
        else -> false
    }
    val showParentTopBar = isRoot



    val pagerState = rememberPagerState { 5 }
    val coroutineScope = rememberCoroutineScope()

    val context = androidx.compose.ui.platform.LocalContext.current
    var lastBackPressTime by remember { mutableStateOf(0L) }

    BackHandler(enabled = true) {
        if (activeModal != null) {
            activeModal = null
        } else if (!isRoot) {
            navController.popBack()
        } else if (pagerState.currentPage != 0) {
            coroutineScope.launch {
                pagerState.scrollToPage(0)
            }
        } else {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackPressTime < 2000) {
                (context as? android.app.Activity)?.finish()
            } else {
                lastBackPressTime = currentTime
                android.widget.Toast.makeText(context, "Press back again to exit", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(navController.targetPageIndex) {
        val target = navController.targetPageIndex
        if (target in 0..4) {
            pagerState.animateScrollToPage(target)
            navController.targetPageIndex = -1
        }
    }

    // Sync from pager scroll to navController (only when scroll has settled to avoid feedback loops)
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) {
            val targetScreen = when (pagerState.currentPage) {
                0 -> Screen.MainDashboard
                1 -> Screen.GlobalPrivacySettings
                2 -> Screen.MediaStatusHub
                3 -> Screen.AutomationTasker
                4 -> Screen.StylesSettings
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
            Screen.StylesSettings -> 4
            else -> -1
        }
        if (targetPage != -1 && pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
    }

    val title = when (currentScreen) {
        Screen.MainDashboard -> "WaEnhancerX"
        Screen.SystemHealth -> "System Health"
        Screen.ProUpgradePaywall -> "Pro Upgrade"
        Screen.GlobalPrivacySettings -> "Privacy"
        Screen.MediaStatusHub -> "Media & Status"
        Screen.AutomationTasker -> "Automation"
        Screen.StylesSettings -> "Styles & Customization"
        Screen.PerContactPrivacyList -> "Per Contact Rules"
        else -> "WaEnhancerX"
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                if (showParentTopBar) {
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
                                .height(56.dp)
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
                                else -> "Free"
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
                                onClick = { navController.navigateTo(Screen.Search) },
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

                            Spacer(modifier = Modifier.width(4.dp))

                            var showMoreMenu by remember { mutableStateOf(false) }
                            Box(
                                modifier = Modifier.align(Alignment.CenterVertically)
                            ) {
                                IconButton(
                                    onClick = { showMoreMenu = true },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = WaexIcons.Settings,
                                        contentDescription = "More Options",
                                        tint = colors.onBackground,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false },
                                    modifier = Modifier.background(colors.surface)
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = WaexIcons.SystemUpdate,
                                                    contentDescription = null,
                                                    tint = colors.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(text = "Changelog & Releases", style = typography.bodyMd, color = colors.onSurface)
                                            }
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            navController.navigateTo(Screen.Changelog)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = WaexIcons.Settings,
                                                    contentDescription = null,
                                                    tint = colors.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(text = "Update Settings", style = typography.bodyMd, color = colors.onSurface)
                                            }
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            navController.navigateTo(Screen.UpdateSettings)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = WaexIcons.Layers,
                                                    contentDescription = null,
                                                    tint = colors.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(text = "Supported Versions", style = typography.bodyMd, color = colors.onSurface)
                                            }
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            navController.navigateTo(Screen.SupportedVersions)
                                        }
                                    )
                                    HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.5f))
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = WaexIcons.Info,
                                                    contentDescription = null,
                                                    tint = colors.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(text = "About & Credits", style = typography.bodyMd, color = colors.onSurface)
                                            }
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            navController.navigateTo(Screen.About)
                                        }
                                    )
                                }
                            }

                        }
                        HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)
                    }
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
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val totalWidth = maxWidth
                            val columnWidth = totalWidth / 5
                            val pillWidth = 40.dp

                            val activeIndex = when (currentScreen) {
                                Screen.MainDashboard -> 0
                                Screen.GlobalPrivacySettings -> 1
                                Screen.MediaStatusHub -> 2
                                Screen.AutomationTasker -> 3
                                Screen.StylesSettings -> 4
                                else -> 0
                            }

                            val targetOffset = (columnWidth * activeIndex) + (columnWidth - pillWidth) / 2
                            val animatedOffset by animateDpAsState(
                                targetValue = targetOffset,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessLow
                                ),
                                label = "tab_highlighter_offset"
                            )

                            // Sliding highlighter pill
                            Box(
                                modifier = Modifier
                                    .padding(top = 12.dp)
                                    .offset(x = animatedOffset)
                                    .size(width = pillWidth, height = 24.dp)
                                    .clip(CircleShape)
                                    .background(colors.primaryContainer)
                            )

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
                                        Screen.StylesSettings -> tab == BottomTab.STYLES
                                        else -> tab == BottomTab.HOME
                                    }

                                    val contentColor by animateColorAsState(
                                        targetValue = if (active) colors.primary else colors.onSurfaceVariant,
                                        animationSpec = tween(durationMillis = 200),
                                        label = "tab_content_color"
                                    )

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
                                                    BottomTab.STYLES -> 4
                                                }
                                                coroutineScope.launch {
                                                    pagerState.animateScrollToPage(pageIndex)
                                                }
                                            }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(width = 40.dp, height = 24.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = tab.icon,
                                                contentDescription = tab.label,
                                                tint = contentColor,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = tab.label,
                                            style = typography.labelSm,
                                            fontWeight = FontWeight.Medium,
                                            color = contentColor,
                                            fontSize = 10.sp
                                        )
                                    }
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
                        top = if (isRoot) paddingValues.calculateTopPadding() else 0.dp,
                        bottom = if (isRoot) 0.dp else 0.dp
                    )
            ) {

                val isForward = navController.isLastTransitionForward
                AnimatedContent(
                    targetState = if (currentScreen.isRootScreen) Screen.MainDashboard else currentScreen,
                    transitionSpec = {
                        if (isForward) {
                            slideInHorizontally(
                                initialOffsetX = { it },
                                animationSpec = tween(durationMillis = 300)
                            ) togetherWith slideOutHorizontally(
                                targetOffsetX = { -it },
                                animationSpec = tween(durationMillis = 300)
                            )
                        } else {
                            slideInHorizontally(
                                initialOffsetX = { -it },
                                animationSpec = tween(durationMillis = 300)
                            ) togetherWith slideOutHorizontally(
                                targetOffsetX = { it },
                                animationSpec = tween(durationMillis = 300)
                            )
                        }
                    },
                    label = "screen_transition",
                    modifier = Modifier.fillMaxSize()
                ) { targetNavigationState ->
                    val screenToShow = if (targetNavigationState == Screen.MainDashboard) currentScreen else targetNavigationState
                    
                    if (screenToShow.isRootScreen) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize()
                        ) { pageIndex ->
                            val pageScreen = when (pageIndex) {
                                0 -> Screen.MainDashboard
                                1 -> Screen.GlobalPrivacySettings
                                2 -> Screen.MediaStatusHub
                                3 -> Screen.AutomationTasker
                                4 -> Screen.StylesSettings
                                else -> Screen.MainDashboard
                            }
                            Box(modifier = Modifier.fillMaxSize()) {
                                WaexAppNavigation(
                                    currentScreen = pageScreen,
                                    licenseState = licenseState,
                                    onOpenModal = { activeModal = it },
                                    onActivatePro = {
                                        licenseManager.activateLicense("DUMMY-KEY-PRO")
                                        licenseState = if (licenseManager.isProActivated()) "pro" else "free"
                                    },
                                    contactPrivacyList = contactPrivacyList,
                                    onClearContact = { c -> contactPrivacyList.removeIf { it.id == c.id } },
                                    onClearAllContacts = { contactPrivacyList.clear() },
                                    onUpdateContact = { updated ->
                                        val idx = contactPrivacyList.indexOfFirst { it.id == updated.id }
                                        if (idx >= 0) contactPrivacyList[idx] = updated
                                    }
                                )
                            }
                        }
                    } else {
                        WaexAppNavigation(
                            currentScreen = screenToShow,
                            licenseState = licenseState,
                            onOpenModal = { activeModal = it },
                            onActivatePro = {
                                licenseManager.activateLicense("DUMMY-KEY-PRO")
                                licenseState = if (licenseManager.isProActivated()) "pro" else "free"
                            },
                            contactPrivacyList = contactPrivacyList,
                            onClearContact = { c -> contactPrivacyList.removeIf { it.id == c.id } },
                            onClearAllContacts = { contactPrivacyList.clear() },
                            onUpdateContact = { updated ->
                                val idx = contactPrivacyList.indexOfFirst { it.id == updated.id }
                                if (idx >= 0) contactPrivacyList[idx] = updated
                            }
                        )
                    }
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
                                    licenseManager.activateLicense("DUMMY-KEY-PRO")
                                    licenseState = if (licenseManager.isProActivated()) "pro" else "free"
                                    activeModal = null
                                }
                            )
                            "file-spoofer" -> FileSizeSpooferModal(onDismiss = { activeModal = null })
                            "message-bomber" -> MessageBomberModal(onDismiss = { activeModal = null })
                            "status-splitter" -> StatusVideoSplitterModal(onDismiss = { activeModal = null })
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
    onActivatePro: () -> Unit,
    contactPrivacyList: List<ContactPrivacy> = emptyList(),
    onClearContact: (ContactPrivacy) -> Unit = {},
    onClearAllContacts: () -> Unit = {},
    onUpdateContact: (ContactPrivacy) -> Unit = {}
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
        Screen.Search -> com.waenhancer.ui.screens.search.SearchScreen()
        Screen.StylesSettings -> com.waenhancer.ui.screens.settings.StylesSettingsScreen()
        Screen.PerContactPrivacyList -> PerContactPrivacyListScreen(
            contacts = contactPrivacyList,
            onClearContact = onClearContact,
            onClearAll = onClearAllContacts,
            onUpdateContact = onUpdateContact
        )
        Screen.Changelog -> ChangelogScreen()
        Screen.About -> AboutScreen()
        Screen.UpdateSettings -> UpdateSettingsScreen()
        Screen.SupportedVersions -> SupportedVersionsScreen()
        Screen.DeletedMessages -> com.waenhancer.ui.screens.privacy.DeletedMessagesVaultScreen()
        Screen.CallRecordingSettings -> com.waenhancer.ui.screens.settings.CallRecordingSettingsScreen()
        Screen.TaskerGuide -> com.waenhancer.ui.screens.automation.TaskerGuideScreen()
        Screen.TaskerHistory -> com.waenhancer.ui.screens.automation.TaskerHistoryScreen()
    }
}



data class ContactPrivacy(
    val id: String,
    val name: String,
    val jid: String,
    val hideSeen: Boolean = false,
    val hideTyping: Boolean = false,
    val hideRecording: Boolean = false,
    val antiRevoke: Boolean = false,
    val freezeLastSeen: Boolean = false,
    val scope: String = "always", // "always" | "scheduled" | "temporary"
    val startHour: Int = 9,
    val endHour: Int = 18
)

