package com.waenhancer.ui.navigation

import com.waenhancer.utils.UiText

import com.waenhancer.licensing.LicenseManager
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
import androidx.compose.runtime.collectAsState
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
import com.waenhancer.ui.components.LicenseActivationModal
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.screens.audio.AudioTranscriptionScreen
import com.waenhancer.ui.screens.automation.AutomationTaskerScreen
import com.waenhancer.ui.screens.conversation.ConversationEnhancementsScreen
import com.waenhancer.ui.screens.dashboard.MainDashboardScreen
import com.waenhancer.ui.screens.dashboard.InjectedHooksScreen
import com.waenhancer.ui.screens.license.LicenseActivationScreen
import com.waenhancer.ui.screens.license.ManageDevicesScreen
import com.waenhancer.ui.screens.media.MediaStatusHubScreen
import com.waenhancer.ui.screens.privacy.GlobalPrivacySettingsScreen
import com.waenhancer.ui.screens.privacy.PerContactPrivacyListScreen

import com.waenhancer.ui.screens.settings.SystemHealthScreen
import com.waenhancer.ui.screens.settings.ChangelogScreen
import com.waenhancer.ui.screens.settings.ReleaseDetailsScreen
import com.waenhancer.ui.screens.settings.AboutScreen
import com.waenhancer.ui.screens.settings.StargazersScreen
import com.waenhancer.ui.screens.settings.ModuleSettingsScreen
import com.waenhancer.ui.screens.settings.UpdateSettingsScreen
import com.waenhancer.ui.screens.settings.SupportedVersionsScreen
import com.waenhancer.ui.screens.settings.BootloaderSpooferScreen
import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.waenhancer.app.R
import com.waenhancer.core.preferences.WaexPreferenceManager
import com.waenhancer.notices.NoticeItem
import com.waenhancer.notices.NoticeRepository
import com.waenhancer.ui.components.NoticeBottomSheet
import com.waenhancer.ui.components.UpdateAvailableBottomSheet
import com.waenhancer.ui.components.UpdateDownloadSheet
import com.waenhancer.ui.screens.automation.TaskerGuideScreen
import com.waenhancer.ui.screens.automation.TaskerHistoryScreen
import com.waenhancer.ui.screens.privacy.DeletedMessagesVaultScreen
import com.waenhancer.ui.screens.search.SearchScreen
import com.waenhancer.ui.screens.settings.CallRecordingSettingsScreen
import com.waenhancer.ui.screens.settings.FilterItemsScreen
import com.waenhancer.ui.screens.settings.FloatingBottomBarCustomizerScreen
import com.waenhancer.ui.screens.settings.StylesSettingsScreen
import com.waenhancer.update.UpdateCheckCoordinator
import com.waenhancer.utils.ContactNameResolver

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
        if (preferenceKey == "show_hook_toast" || preferenceKey == "show_restart_sheet") {
            navigateTo(Screen.SystemHealth)
            return
        }
        if (preferenceKey == "bootloader_spoofer") {
            navigateTo(Screen.BootloaderSpoofer)
            return
        }
        if (preferenceKey == "supported_versions" || preferenceKey == "bypass_version_check") {
            navigateTo(Screen.SupportedVersions)
            return
        }
        if (preferenceKey == "license_verification" || preferenceKey == "pro_features_unlock") {
            navigateTo(Screen.LicenseActivation)
            return
        }
        if (preferenceKey == "filter_items" || preferenceKey == "custom_filters") {
            navigateTo(Screen.FilterItems)
            return
        }
        val rootScreen = when (tabIndex) {
            0 -> Screen.MainDashboard
            1 -> Screen.GlobalPrivacySettings
            2 -> Screen.MediaStatusHub
            3 -> Screen.AutomationTasker
            4 -> Screen.StylesSettings
            else -> Screen.MainDashboard
        }
        targetPageIndex = tabIndex
        targetSubTabId = subTabId
        scrollToTargetKey = preferenceKey
        highlightTargetKey = preferenceKey
        navigateTo(rootScreen, clearStack = true)
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

val LocalIsPro = staticCompositionLocalOf { false }

val LocalOnActivatePro = staticCompositionLocalOf<() -> Unit> { {} }

val LocalWaexPreferenceManager = staticCompositionLocalOf<WaexPreferenceManager> {
    error("No WaexPreferenceManager provided")
}


enum class BottomTab(
    val id: String,
    val labelRes: Int,
    val icon: ImageVector,
    val screen: Screen
) {
    HOME("home", R.string.nav_home, WaexIcons.GridView, Screen.MainDashboard),
    PRIVACY("privacy", R.string.nav_controls, WaexIcons.Security, Screen.GlobalPrivacySettings),
    MEDIA("media", R.string.nav_media, WaexIcons.Image, Screen.MediaStatusHub),
    AUTOMATION("automation", R.string.nav_automation, WaexIcons.AutoAwesome, Screen.AutomationTasker),
    STYLES("styles", R.string.nav_styles, WaexIcons.Palette, Screen.StylesSettings)
}

@Composable
fun MainContainerScreen() {
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    val context = LocalContext.current
    val preferenceManager = LocalWaexPreferenceManager.current
    var licenseState by remember { mutableStateOf(if ("ACTIVE".equals(LicenseManager.getProStatus(context), ignoreCase = true)) "pro" else "free") }
    val isPro = licenseState == "pro"
    var activeModal by remember { mutableStateOf<String?>(null) } // "license" | "file-spoofer" | "message-bomber" | "status-splitter" | null
    var activeNotice by remember { mutableStateOf<NoticeItem?>(null) }

    LaunchedEffect(Unit) {
        val notice = NoticeRepository.getActiveNotice(context, preferenceManager)
        if (notice != null) {
            activeNotice = notice
            NoticeRepository.markNoticeShown(context, notice)
        }
    }

    CompositionLocalProvider(
        LocalIsPro provides isPro,
        LocalOnActivatePro provides { navController.navigateTo(Screen.LicenseActivation) }
    ) {

    val contactPrivacyList = remember(preferenceManager) {
        mutableStateListOf<ContactPrivacy>().apply {
            addAll(loadContactPrivacyList(context, preferenceManager))
        }
    }

    LaunchedEffect(navController.currentScreen) {
        if (navController.currentScreen == Screen.PerContactPrivacyList) {
            contactPrivacyList.clear()
            contactPrivacyList.addAll(loadContactPrivacyList(context, preferenceManager))
        }
    }

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
    // PagerState is saveable, so Android can restore the previously selected tab after an
    // Activity recreation. The in-memory controller starts at Home, though. Do not let the
    // restored pager page overwrite that controller before it has performed its first sync.
    var pagerNavigationReady by remember { mutableStateOf(false) }

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
                (context as? Activity)?.finish()
            } else {
                lastBackPressTime = currentTime
                Toast.makeText(context, UiText.get(R.string.waex_ui_press_back_again_to_exit_c151b888, "Press back again to exit"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(navController.targetPageIndex) {
        val target = navController.targetPageIndex
        if (target in 0..4) {
            pagerState.scrollToPage(target)
            navController.targetPageIndex = -1
        }
    }

    // Sync from navController to pager first. This establishes a single source of truth after
    // recreation (including a locale change) before observing restored pager state.
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
        pagerNavigationReady = true
    }

    // Sync from pager scroll to navController only when scroll has settled, and only after the
    // initial navigation-to-pager sync above has resolved restored state.
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (pagerNavigationReady && !pagerState.isScrollInProgress) {
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

    val title = when (currentScreen) {
        Screen.MainDashboard -> "WaEnhancerX"
        Screen.SystemHealth -> stringResource(R.string.title_system_health)
        Screen.BootloaderSpoofer -> stringResource(R.string.title_bootloader_spoofer)
        Screen.GlobalPrivacySettings -> stringResource(R.string.nav_controls)
        Screen.MediaStatusHub -> stringResource(R.string.nav_media)
        Screen.AutomationTasker -> stringResource(R.string.nav_automation)
        Screen.StylesSettings -> stringResource(R.string.nav_styles)
        Screen.PerContactPrivacyList -> stringResource(R.string.title_per_contact_privacy)
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
                                        contentDescription = UiText.get(R.string.action_back, "Back"),
                                        tint = colors.onBackground,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                            }

                            // Title + License Chip grouped together
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .align(Alignment.CenterVertically),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = title,
                                    style = typography.headlineMd,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onBackground
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                // License Chip directly attached to title
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
                                    "pro" -> UiText.get(R.string.pro_feature_tag, "Pro")
                                    "trial" -> UiText.get(R.string.waex_ui_trial_98a66e97, "Trial")
                                    else -> UiText.get(R.string.waex_ui_free_f411a1fb, "Free")
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(chipBg)
                                        .border(1.dp, chipText.copy(alpha = 0.2f), CircleShape)
                                        .clickable { navController.navigateTo(Screen.LicenseActivation) }
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = chipLabel,
                                        style = typography.labelSm,
                                        fontWeight = FontWeight.Medium,
                                        color = chipText,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            IconButton(
                                onClick = { navController.navigateTo(Screen.Search) },
                                modifier = Modifier
                                    .size(32.dp)
                                    .align(Alignment.CenterVertically)
                            ) {
                                Icon(
                                    imageVector = WaexIcons.Search,
                                    contentDescription = UiText.get(R.string.action_search, "Search"),
                                    tint = colors.onBackground,
                                    modifier = Modifier.size(20.dp)
                                )
                            }


                            Spacer(modifier = Modifier.width(8.dp))

                            var showMoreMenu by remember { mutableStateOf(false) }
                            Box(
                                modifier = Modifier.align(Alignment.CenterVertically)
                            ) {
                                IconButton(
                                    onClick = { showMoreMenu = true },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = WaexIcons.MoreVert,
                                        contentDescription = UiText.get(R.string.waex_ui_more_options_6285ce5d, "More Options"),
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
                                                    imageVector = WaexIcons.Premium,
                                                    contentDescription = null,
                                                    tint = colors.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(text = stringResource(R.string.stargazers_title), style = typography.bodyMd, color = colors.onSurface)
                                            }
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            navController.navigateTo(Screen.Stargazers)
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
                                                Text(text = stringResource(R.string.module_settings_title), style = typography.bodyMd, color = colors.onSurface)
                                            }
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            navController.navigateTo(Screen.ModuleSettings)
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
                                                Text(text = stringResource(R.string.title_supported_versions), style = typography.bodyMd, color = colors.onSurface)
                                            }
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            navController.navigateTo(Screen.SupportedVersions)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Icon(WaexIcons.Security, contentDescription = null,
                                                    tint = colors.primary, modifier = Modifier.size(16.dp))
                                                Text(text = stringResource(R.string.title_bootloader_spoofer), style = typography.bodyMd, color = colors.onSurface)
                                            }
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            navController.navigateTo(Screen.BootloaderSpoofer)
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
                                                Text(text = stringResource(R.string.about_title), style = typography.bodyMd, color = colors.onSurface)
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
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 6.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.outlineVariant),
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                    ) {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val totalWidth = maxWidth
                            val columnWidth = totalWidth / 5
                            val pillWidth = 36.dp

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
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium
                                ),
                                label = "tab_highlighter_offset"
                            )

                            // Sliding highlighter pill
                            Box(
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .offset(x = animatedOffset)
                                    .size(width = pillWidth, height = 20.dp)
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
                                                contentDescription = stringResource(tab.labelRes),
                                                tint = contentColor,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = stringResource(tab.labelRes),
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
                                initialOffsetX = { it / 10 },
                                animationSpec = tween(durationMillis = 240)
                            ) + fadeIn(
                                animationSpec = tween(durationMillis = 180)
                            ) togetherWith (slideOutHorizontally(
                                targetOffsetX = { -it / 10 },
                                animationSpec = tween(durationMillis = 240)
                            ) + fadeOut(
                                animationSpec = tween(durationMillis = 120)
                            ))
                        } else {
                            slideInHorizontally(
                                initialOffsetX = { -it / 10 },
                                animationSpec = tween(durationMillis = 240)
                            ) + fadeIn(
                                animationSpec = tween(durationMillis = 180)
                            ) togetherWith (slideOutHorizontally(
                                targetOffsetX = { it / 10 },
                                animationSpec = tween(durationMillis = 240)
                            ) + fadeOut(
                                animationSpec = tween(durationMillis = 120)
                            ))
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
                                        navController.navigateTo(Screen.LicenseActivation)
                                    },
                                    contactPrivacyList = contactPrivacyList,
                                    onClearContact = { c ->
                                        contactPrivacyList.removeIf { it.id == c.id }
                                        preferenceManager.remove("${c.id}_privacy")
                                        preferenceManager.remove("${c.jid}_privacy")
                                        preferenceManager.remove("per_contact_rules_${c.jid}")
                                    },
                                    onClearAllContacts = {
                                        contactPrivacyList.forEach { c ->
                                            preferenceManager.remove("${c.id}_privacy")
                                            preferenceManager.remove("${c.jid}_privacy")
                                            preferenceManager.remove("per_contact_rules_${c.jid}")
                                        }
                                        contactPrivacyList.clear()
                                    },
                                    onUpdateContact = { updated ->
                                        try {
                                            saveContactPrivacy(preferenceManager, updated)
                                            val idx = contactPrivacyList.indexOfFirst { it.id == updated.id }
                                            if (idx >= 0) contactPrivacyList[idx] = updated
                                        } catch (_: Throwable) {}
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
                                navController.navigateTo(Screen.LicenseActivation)
                            },
                            contactPrivacyList = contactPrivacyList,
                            onClearContact = { c ->
                                contactPrivacyList.removeIf { it.id == c.id }
                                preferenceManager.remove("${c.id}_privacy")
                                preferenceManager.remove("${c.jid}_privacy")
                                preferenceManager.remove("per_contact_rules_${c.jid}")
                            },
                            onClearAllContacts = {
                                contactPrivacyList.forEach { c ->
                                    preferenceManager.remove("${c.id}_privacy")
                                    preferenceManager.remove("${c.jid}_privacy")
                                    preferenceManager.remove("per_contact_rules_${c.jid}")
                                }
                                contactPrivacyList.clear()
                            },
                            onUpdateContact = { updated ->
                                try {
                                    saveContactPrivacy(preferenceManager, updated)
                                    val idx = contactPrivacyList.indexOfFirst { it.id == updated.id }
                                    if (idx >= 0) contactPrivacyList[idx] = updated
                                } catch (_: Throwable) {}
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
                    border = BorderStroke(1.dp, colors.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(top = 12.dp, bottom = 24.dp)
                    ) {
                        when (activeModal) {
                            "license" -> LicenseActivationModal(
                                onDismiss = { activeModal = null },
                                onActivated = {
                                    licenseState = if ("ACTIVE".equals(LicenseManager.getProStatus(context), ignoreCase = true)) "pro" else "free"
                                    activeModal = null
                                }
                            )
                        }
                    }
                }
            }
        }

        // Material 3 Remote Notice Bottom Sheet
        activeNotice?.let { notice ->
            NoticeBottomSheet(
                notice = notice,
                onDismiss = { activeNotice = null }
            )
        }

        // Available Update Bottom Sheet
        val availableUpdate by UpdateCheckCoordinator.availableRelease.collectAsState()
        var downloadingUrl by remember { mutableStateOf<String?>(null) }
        var downloadingVersion by remember { mutableStateOf<String?>(null) }
        var downloadingSha256 by remember { mutableStateOf<String?>(null) }
        var downloadingSize by remember { mutableStateOf(0L) }

        availableUpdate?.let { update ->
            UpdateAvailableBottomSheet(
                release = update,
                onUpdateNow = {
                    val asset = update.defaultAsset
                    if (asset != null) {
                        downloadingUrl = asset.downloadUrl
                        downloadingVersion = update.version
                        downloadingSha256 = asset.sha256
                        downloadingSize = asset.size
                    } else {
                        navController.navigateTo(Screen.ReleaseDetails(update.tagName))
                    }
                    UpdateCheckCoordinator.dismissInAppUpdate()
                },
                onViewDetails = {
                    UpdateCheckCoordinator.dismissInAppUpdate()
                    navController.navigateTo(Screen.ReleaseDetails(update.tagName))
                },
                onDismiss = {
                    UpdateCheckCoordinator.dismissInAppUpdate()
                }
            )
        }

        downloadingUrl?.let { url ->
            val version = downloadingVersion ?: "latest"
            val useRoot = preferenceManager.getBoolean("root_auto_install", false)
            UpdateDownloadSheet(
                url = url,
                version = version,
                expectedSha256 = downloadingSha256,
                expectedSize = downloadingSize,
                useRoot = useRoot,
                onDismiss = {
                    downloadingUrl = null
                    downloadingVersion = null
                    downloadingSha256 = null
                    downloadingSize = 0L
                }
            )
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
        Screen.BootloaderSpoofer -> BootloaderSpooferScreen()
        Screen.GlobalPrivacySettings -> GlobalPrivacySettingsScreen(
            onOpenModal = onOpenModal
        )
        Screen.ConversationEnhancements -> ConversationEnhancementsScreen()
        Screen.MediaStatusHub -> MediaStatusHubScreen()
        Screen.AutomationTasker -> AutomationTaskerScreen()
        Screen.AudioTranscription -> AudioTranscriptionScreen()
        Screen.LicenseActivation -> LicenseActivationScreen()
        Screen.Search -> SearchScreen()
        Screen.StylesSettings -> StylesSettingsScreen()
        Screen.PerContactPrivacyList -> PerContactPrivacyListScreen(
            contacts = contactPrivacyList,
            onClearContact = onClearContact,
            onClearAll = onClearAllContacts,
            onUpdateContact = onUpdateContact
        )
        Screen.Changelog -> ChangelogScreen()
        is Screen.ReleaseDetails -> ReleaseDetailsScreen(currentScreen.tagName)
        Screen.About -> AboutScreen()
        Screen.Stargazers -> StargazersScreen()
        Screen.ModuleSettings -> ModuleSettingsScreen()
        Screen.UpdateSettings -> UpdateSettingsScreen()
        Screen.SupportedVersions -> SupportedVersionsScreen()
        Screen.DeletedMessages -> DeletedMessagesVaultScreen()
        Screen.CallRecordingSettings -> CallRecordingSettingsScreen()
        Screen.TaskerGuide -> TaskerGuideScreen()
        Screen.TaskerHistory -> TaskerHistoryScreen()
        Screen.ManageDevices -> ManageDevicesScreen()
        Screen.FloatingBottomBarCustomizer -> FloatingBottomBarCustomizerScreen()
        Screen.FilterItems -> FilterItemsScreen()
        is Screen.InjectedHooks -> InjectedHooksScreen(packageName = currentScreen.packageName, appName = currentScreen.appName)
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

/** Update only the controls owned by the manager's per-contact editor. */
private fun saveContactPrivacy(preferenceManager: WaexPreferenceManager, updated: ContactPrivacy) {
    // Match CustomPrivacyHook's existing first-non-null precedence. Read at save
    // time so a call/status rule changed after opening this editor is preserved.
    // A malformed authoritative document must fail before any preference write.
    val currentRules = preferenceManager.getString("${updated.id}_privacy", null)
        ?: preferenceManager.getString("${updated.jid}_privacy", null)
        ?: preferenceManager.getString("per_contact_rules_${updated.jid}", null)
    val json = if (currentRules == null) org.json.JSONObject() else org.json.JSONObject(currentRules)
    if (!json.has("name")) json.put("name", updated.name)
    json.put("HideSeen", updated.hideSeen)
    json.put("HideTyping", updated.hideTyping)
    json.put("HideRecording", updated.hideRecording)
    json.put("AntiRevoke", updated.antiRevoke)
    // This legacy field is independent of BlockCall. The public hooks currently
    // consume only the global freeze-last-seen preferences.
    json.put("FreezeLastSeen", updated.freezeLastSeen)
    json.put("scope", updated.scope)
    val jsonStr = json.toString()
    preferenceManager.putString("${updated.id}_privacy", jsonStr)
    preferenceManager.putString("${updated.jid}_privacy", jsonStr)
    preferenceManager.putString("per_contact_rules_${updated.jid}", jsonStr)
}

fun loadContactPrivacyList(context: Context, preferenceManager: WaexPreferenceManager): List<ContactPrivacy> {
    val list = mutableListOf<ContactPrivacy>()
    val all = preferenceManager.all ?: return list
    val seen = mutableSetOf<String>()

    for ((key, value) in all) {
        if (key.endsWith("_privacy") && value is String) {
            val raw = key.removeSuffix("_privacy")
            val number = raw.substringBefore("@").substringBefore(":")
            if (number.isEmpty() || number == "custom" || number == "call" || number == "global" || !seen.add(number)) continue

            try {
                val json = org.json.JSONObject(value)
                val isGroup = raw.contains("@g.us") || number.contains("-")
                val isLid = number.length >= 14 && !isGroup
                val jid = if (raw.contains("@")) raw else if (isGroup) "$number@g.us" else if (isLid) "$number@lid" else "$number@s.whatsapp.net"
                val rawStoredName = json.optString("name", "")
                val phoneHint = json.optString("phone", "")

                val resolvedName = ContactNameResolver.resolveName(context, jid, rawStoredName, phoneHint)

                list.add(
                    ContactPrivacy(
                        id = number,
                        name = resolvedName,
                        jid = jid,
                        hideSeen = json.optBoolean("HideSeen", false),
                        hideTyping = json.optBoolean("HideTyping", false),
                        hideRecording = json.optBoolean("HideRecording", false),
                        antiRevoke = json.optBoolean("AntiRevoke", false),
                        freezeLastSeen = json.optBoolean("FreezeLastSeen", false),
                        scope = json.optString("scope", "always")
                    )
                )
            } catch (_: Throwable) {}
        }
    }
    return list
}
