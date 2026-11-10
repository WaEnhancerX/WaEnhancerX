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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.waenhancer.app.BuildConfig
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.waenhancer.utils.WhatsAppPackageDetector
import com.waenhancer.utils.LsposedScopeReader
import com.waenhancer.utils.TargetAppManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.waenhancer.ui.screens.settings.HookingModeHelpScreen
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
import com.waenhancer.licensing.customprops.CustomAbPropEditorScreen
import com.waenhancer.licensing.customprops.CustomAbPropsScreen
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
import org.json.JSONObject

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
        if (preferenceKey == "app_interface_size") {
            navigateTo(Screen.ModuleSettings)
            return
        }
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
        if (preferenceKey == "custom_ab_props") {
            navigateTo(Screen.CustomAbProps)
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
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    // PagerState is saveable, so Android can restore the previously selected tab after an
    // Activity recreation. The in-memory controller starts at Home, though. Do not let the
    // restored pager page overwrite that controller before it has performed its first sync.
    var pagerNavigationReady by remember { mutableStateOf(false) }

    var lastBackPressTime by remember { mutableStateOf(0L) }

    BackHandler(enabled = true) {
        if (drawerState.isOpen) {
            coroutineScope.launch { drawerState.close() }
        } else if (activeModal != null) {
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
                Toast.makeText(context, UiText.get(R.string.waex_ui_press_back_again_to_exit, "Press back again to exit"), Toast.LENGTH_SHORT).show()
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
        Screen.MainDashboard -> "WAEX"
        Screen.SystemHealth -> stringResource(R.string.title_system_health)
        Screen.BootloaderSpoofer -> stringResource(R.string.title_bootloader_spoofer)
        Screen.GlobalPrivacySettings -> stringResource(R.string.nav_controls)
        Screen.MediaStatusHub -> stringResource(R.string.nav_media)
        Screen.AutomationTasker -> stringResource(R.string.nav_automation)
        Screen.StylesSettings -> stringResource(R.string.nav_styles)
        Screen.PerContactPrivacyList -> stringResource(R.string.title_per_contact_privacy)
        else -> "WAEX"
    }

    // Config Mode & Target App Selection
    val configMode = preferenceManager.getString("module_config_mode", "Universal")
    val isPerTargetApp = configMode == "Per Target App"

    val targetPackages = listOf("com.whatsapp", "com.whatsapp.w4b")
    val fallbackNames = mapOf(
        "com.whatsapp" to stringResource(R.string.target_whatsapp),
        "com.whatsapp.w4b" to "WA Business"
    )

    data class TargetAppInfo(
        val packageName: String,
        val label: String,
        val icon: androidx.compose.ui.graphics.ImageBitmap?
    )

    LaunchedEffect(Unit) {
        TargetAppManager.init(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                TargetAppManager.refresh(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val scopedPackages by TargetAppManager.scopedPackages.collectAsState()

    val visibleTargetApps = remember(scopedPackages) {
        val pm = context.packageManager
        scopedPackages.map { pkg ->
            // Priority 1: Exact launcher activity label (the title appearing on the user's home screen)
            val launcherLabel = runCatching {
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    val resolveInfo = pm.resolveActivity(launchIntent, 0)
                    resolveInfo?.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
                } else null
            }.getOrNull()

            // Priority 2: General application label from ApplicationInfo
            val appLabel = runCatching {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(appInfo).toString().takeIf { it.isNotBlank() }
            }.getOrNull()

            // Priority 3: Built-in localized fallback name
            val label = launcherLabel ?: appLabel ?: fallbackNames[pkg].orEmpty()

            val icon = runCatching {
                pm.getApplicationIcon(pkg).toBitmap(64, 64).asImageBitmap()
            }.getOrNull()

            TargetAppInfo(packageName = pkg, label = label, icon = icon)
        }
    }

    var selectedPackage by remember { mutableStateOf(targetPackages.first()) }
    // Update synchronously before any preference-backed screen is composed.
    preferenceManager.setTargetPackage(selectedPackage)
    LaunchedEffect(visibleTargetApps) {
        if (visibleTargetApps.isNotEmpty() && visibleTargetApps.none { it.packageName == selectedPackage }) {
            selectedPackage = visibleTargetApps.first().packageName
        }
    }
    var showTargetMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = isRoot,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = colors.surface,
                    drawerContentColor = colors.onSurface,
                    modifier = Modifier.width(260.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Header
                        Surface(
                            color = colors.surfaceContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .statusBarsPadding()
                                    .padding(horizontal = 18.dp, vertical = 18.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Image(
                                        painter = painterResource(R.drawable.ic_app_logo),
                                        contentDescription = "WaEnhancerX Logo",
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                    )
                                    Column {
                                        Text(
                                            text = "WaEnhancerX",
                                            style = typography.titleMd,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "v${BuildConfig.VERSION_NAME}",
                                                style = typography.bodySm,
                                                color = colors.onSurfaceVariant
                                            )

                                            // License Plan Chip inside Drawer Header
                                            val chipBg = when (licenseState) {
                                                "pro" -> colors.primaryContainer
                                                "trial" -> colors.warningContainer
                                                else -> colors.surfaceDim
                                            }
                                            val chipText = when (licenseState) {
                                                "pro" -> colors.primary
                                                "trial" -> colors.onWarningContainer
                                                else -> colors.onSurfaceVariant
                                            }
                                            val chipLabel = when (licenseState) {
                                                "pro" -> UiText.get(R.string.pro_feature_tag, "Pro")
                                                "trial" -> UiText.get(R.string.waex_ui_trial, "Trial")
                                                else -> UiText.get(R.string.waex_ui_free, "Free")
                                            }

                                            Box(
                                                modifier = Modifier
                                                    .clip(CircleShape)
                                                    .background(chipBg)
                                                    .border(1.dp, chipText.copy(alpha = 0.2f), CircleShape)
                                                    .clickable {
                                                        coroutineScope.launch { drawerState.close() }
                                                        navController.navigateTo(Screen.LicenseActivation)
                                                    }
                                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = chipLabel,
                                                    style = typography.labelSm,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = chipText,
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Navigation Items moved from 3-dot menu
                        NavigationDrawerItem(
                            label = { Text(text = stringResource(R.string.stargazers_title), style = typography.bodyMd) },
                            icon = { Icon(WaexIcons.Premium, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp)) },
                            selected = currentScreen == Screen.Stargazers,
                            onClick = {
                                coroutineScope.launch { drawerState.close() }
                                navController.navigateTo(Screen.Stargazers)
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )

                        NavigationDrawerItem(
                            label = { Text(text = stringResource(R.string.module_settings_title), style = typography.bodyMd) },
                            icon = { Icon(WaexIcons.Settings, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp)) },
                            selected = currentScreen == Screen.ModuleSettings,
                            onClick = {
                                coroutineScope.launch { drawerState.close() }
                                navController.navigateTo(Screen.ModuleSettings)
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )

                        NavigationDrawerItem(
                            label = { Text(text = stringResource(R.string.title_supported_versions), style = typography.bodyMd) },
                            icon = { Icon(WaexIcons.Layers, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp)) },
                            selected = currentScreen == Screen.SupportedVersions,
                            onClick = {
                                coroutineScope.launch { drawerState.close() }
                                navController.navigateTo(Screen.SupportedVersions)
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )

                        NavigationDrawerItem(
                            label = { Text(text = stringResource(R.string.title_bootloader_spoofer), style = typography.bodyMd) },
                            icon = { Icon(WaexIcons.Security, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp)) },
                            selected = currentScreen == Screen.BootloaderSpoofer,
                            onClick = {
                                coroutineScope.launch { drawerState.close() }
                                navController.navigateTo(Screen.BootloaderSpoofer)
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                            color = colors.outlineVariant.copy(alpha = 0.5f)
                        )

                        NavigationDrawerItem(
                            label = { Text(text = stringResource(R.string.about_title), style = typography.bodyMd) },
                            icon = { Icon(WaexIcons.Info, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp)) },
                            selected = currentScreen == Screen.About,
                            onClick = {
                                coroutineScope.launch { drawerState.close() }
                                navController.navigateTo(Screen.About)
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                    }
                }
            }
        ) {
        Scaffold(
            topBar = {
                if (showParentTopBar) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.background,
                        tonalElevation = 2.dp
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
                            } else {
                                IconButton(
                                    onClick = {
                                        coroutineScope.launch { drawerState.open() }
                                    },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .align(Alignment.CenterVertically)
                                ) {
                                    Icon(
                                        imageVector = WaexIcons.Menu,
                                        contentDescription = stringResource(R.string.navigation_drawer_open),
                                        tint = colors.onBackground,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }

                            // Screen Title
                            Text(
                                text = title,
                                style = typography.headlineMd,
                                fontWeight = FontWeight.Bold,
                                color = colors.onBackground,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )

                            Spacer(modifier = Modifier.weight(1f))

                            // Target App Indicator / Dropdown Switcher based on Config Mode
                            if (isRoot) {
                                if (!isPerTargetApp) {
                                    // Universal Mode: simply says "Universal", non-dropdown
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = colors.surfaceContainer,
                                        modifier = Modifier.align(Alignment.CenterVertically)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.config_mode_universal),
                                            style = typography.labelSm,
                                            fontWeight = FontWeight.Medium,
                                            color = colors.primary,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                } else {
                                    // Per Target App Mode: Dropdown of target apps only (scoped only)
                                    val currentTarget = visibleTargetApps.find { it.packageName == selectedPackage }
                                        ?: visibleTargetApps.firstOrNull()

                                    if (visibleTargetApps.isEmpty() || currentTarget == null) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = colors.surfaceContainer,
                                            modifier = Modifier.align(Alignment.CenterVertically)
                                        ) {
                                            Text(
                                                text = "No Scoped App",
                                                style = typography.labelSm,
                                                color = colors.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                            )
                                        }
                                    } else {
                                        val currentLabel = currentTarget.label
                                        val currentIcon = currentTarget.icon

                                        Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                                            Row(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(colors.surfaceContainer)
                                                    .clickable { showTargetMenu = true }
                                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                if (currentIcon != null) {
                                                    Image(
                                                        bitmap = currentIcon,
                                                        contentDescription = null,
                                                        modifier = Modifier
                                                            .size(18.dp)
                                                            .clip(RoundedCornerShape(4.dp))
                                                    )
                                                }
                                                Text(
                                                    text = currentLabel,
                                                    style = typography.labelSm,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = colors.primary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Icon(
                                                    imageVector = WaexIcons.ArrowDropDown,
                                                    contentDescription = null,
                                                    tint = colors.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }

                                            DropdownMenu(
                                                expanded = showTargetMenu,
                                                onDismissRequest = { showTargetMenu = false },
                                                modifier = Modifier.background(colors.surface)
                                            ) {
                                                visibleTargetApps.forEach { target ->
                                                    val isSelected = selectedPackage == target.packageName
                                                    DropdownMenuItem(
                                                        leadingIcon = {
                                                            if (target.icon != null) {
                                                                Image(
                                                                    bitmap = target.icon,
                                                                    contentDescription = null,
                                                                    modifier = Modifier
                                                                        .size(24.dp)
                                                                        .clip(RoundedCornerShape(6.dp))
                                                                )
                                                            } else {
                                                                Icon(
                                                                    imageVector = WaexIcons.Layers,
                                                                    contentDescription = null,
                                                                    tint = if (isSelected) colors.primary else colors.onSurfaceVariant,
                                                                    modifier = Modifier.size(24.dp)
                                                                )
                                                            }
                                                        },
                                                        text = {
                                                            Column {
                                                                Text(
                                                                    text = target.label,
                                                                    style = typography.bodyMd,
                                                                    color = if (isSelected) colors.primary else colors.onSurface,
                                                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                                                )
                                                                Text(
                                                                    text = target.packageName,
                                                                    style = typography.labelSm,
                                                                    color = colors.onSurfaceVariant,
                                                                    fontSize = 11.sp
                                                                )
                                                            }
                                                        },
                                                        trailingIcon = {
                                                            if (isSelected) {
                                                                Icon(
                                                                    imageVector = WaexIcons.Check,
                                                                    contentDescription = null,
                                                                    tint = colors.primary,
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                            }
                                                        },
                                                        onClick = {
                                                            selectedPackage = target.packageName
                                                            showTargetMenu = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))
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
                        }
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
                            .height(66.dp)
                    ) {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val totalWidth = maxWidth
                            val columnWidth = totalWidth / 5
                            // Match the indicator to the 40.dp icon slot rather than the
                            // icon glyph. This keeps the selected icon visually centered
                            // inside the thumb across icons with different intrinsic bounds.
                            val pillWidth = 48.dp

                            val activeIndex = pagerState.currentPage.coerceIn(0, BottomTab.entries.lastIndex)

                            val targetOffset = (columnWidth * activeIndex) + (columnWidth - pillWidth) / 2
                            val animatedOffset by animateDpAsState(
                                targetValue = targetOffset,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium
                                ),
                                label = "tab_highlighter_offset"
                            )

                            Box(
                                modifier = Modifier
                                    .padding(top = 10.dp)
                                    .offset(x = animatedOffset)
                                    .size(width = pillWidth, height = 28.dp)
                                    .clip(CircleShape)
                                    .background(colors.primaryContainer)
                            )

                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                BottomTab.entries.forEachIndexed { tabIndex, tab ->
                                    val active = tabIndex == activeIndex

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
                                            modifier = Modifier.size(width = 40.dp, height = 24.dp),
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
                                            fontSize = 10.sp,
                                            lineHeight = 10.sp,
                                            textAlign = TextAlign.Center,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 2.dp)
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
                        bottom = 0.dp
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
                                    targetEpoch = preferenceManager.targetEpoch,
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
                                        val idx = contactPrivacyList.indexOfFirst { it.id == updated.id }
                                        if (idx >= 0) contactPrivacyList[idx] = updated
                                        try {
                                            val json = JSONObject().apply {
                                                put("name", updated.name)
                                                put("HideSeen", updated.hideSeen)
                                                put("HideTyping", updated.hideTyping)
                                                put("HideRecording", updated.hideRecording)
                                                put("AntiRevoke", updated.antiRevoke)
                                                put("FreezeLastSeen", updated.freezeLastSeen)
                                                put("BlockCall", updated.freezeLastSeen)
                                                put("scope", updated.scope)
                                            }
                                            val jsonStr = json.toString()
                                            preferenceManager.putString("${updated.id}_privacy", jsonStr)
                                            preferenceManager.putString("${updated.jid}_privacy", jsonStr)
                                            preferenceManager.putString("per_contact_rules_${updated.jid}", jsonStr)
                                        } catch (_: Throwable) {}
                                    }
                                )
                            }
                        }
                    } else {
                        WaexAppNavigation(
                            currentScreen = screenToShow,
                            targetEpoch = preferenceManager.targetEpoch,
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
                                val idx = contactPrivacyList.indexOfFirst { it.id == updated.id }
                                if (idx >= 0) contactPrivacyList[idx] = updated
                                try {
                                    val json = JSONObject().apply {
                                        put("name", updated.name)
                                        put("HideSeen", updated.hideSeen)
                                        put("HideTyping", updated.hideTyping)
                                        put("HideRecording", updated.hideRecording)
                                        put("AntiRevoke", updated.antiRevoke)
                                        put("FreezeLastSeen", updated.freezeLastSeen)
                                        put("BlockCall", updated.freezeLastSeen)
                                        put("scope", updated.scope)
                                    }
                                    val jsonStr = json.toString()
                                    preferenceManager.putString("${updated.id}_privacy", jsonStr)
                                    preferenceManager.putString("${updated.jid}_privacy", jsonStr)
                                    preferenceManager.putString("per_contact_rules_${updated.jid}", jsonStr)
                                } catch (_: Throwable) {}
                            }
                        )
                    }
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
    targetEpoch: Int,
    licenseState: String,
    onOpenModal: (String) -> Unit,
    onActivatePro: () -> Unit,
    contactPrivacyList: List<ContactPrivacy> = emptyList(),
    onClearContact: (ContactPrivacy) -> Unit = {},
    onClearAllContacts: () -> Unit = {},
    onUpdateContact: (ContactPrivacy) -> Unit = {}
) {
    // Keep this composable subscribed to target changes without keying/recreating
    // the screen tree (scroll and tab state must remain shared).
    targetEpoch
    val navController = LocalWaexNavController.current
    val preferenceManager = LocalWaexPreferenceManager.current
    when (currentScreen) {
        Screen.MainDashboard -> MainDashboardScreen(
            targetEpoch = targetEpoch,
            licenseState = licenseState,
            onOpenModal = onOpenModal
        )
        Screen.DashboardBento -> MainDashboardScreen(
            targetEpoch = targetEpoch,
            licenseState = licenseState,
            onOpenModal = onOpenModal
        )
        Screen.SystemHealth -> SystemHealthScreen()
        Screen.BootloaderSpoofer -> BootloaderSpooferScreen()
        Screen.GlobalPrivacySettings -> GlobalPrivacySettingsScreen(
            onOpenModal = onOpenModal,
            targetEpoch = targetEpoch
        )
        Screen.ConversationEnhancements -> ConversationEnhancementsScreen(targetEpoch = targetEpoch)
        Screen.MediaStatusHub -> MediaStatusHubScreen(targetEpoch = targetEpoch)
        Screen.AutomationTasker -> AutomationTaskerScreen(targetEpoch = targetEpoch)
        Screen.AudioTranscription -> AudioTranscriptionScreen()
        Screen.LicenseActivation -> LicenseActivationScreen()
        Screen.Search -> SearchScreen()
        Screen.StylesSettings -> StylesSettingsScreen(targetEpoch = targetEpoch)
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
        Screen.HookingModeHelp -> HookingModeHelpScreen()
        Screen.UpdateSettings -> UpdateSettingsScreen()
        Screen.SupportedVersions -> SupportedVersionsScreen()
        Screen.DeletedMessages -> DeletedMessagesVaultScreen()
        Screen.CallRecordingSettings -> CallRecordingSettingsScreen()
        Screen.TaskerGuide -> TaskerGuideScreen()
        Screen.TaskerHistory -> TaskerHistoryScreen()
        Screen.ManageDevices -> ManageDevicesScreen()
        Screen.FloatingBottomBarCustomizer -> FloatingBottomBarCustomizerScreen()
        Screen.FilterItems -> FilterItemsScreen()
        Screen.CustomAbProps -> CustomAbPropsScreen(
            prefs = preferenceManager,
            onBack = navController::popBack,
            onEdit = { index -> navController.navigateTo(Screen.CustomAbPropEditor(index)) }
        )
        is Screen.CustomAbPropEditor -> CustomAbPropEditorScreen(
            index = currentScreen.index,
            prefs = preferenceManager,
            onBack = navController::popBack
        )
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
                val json = JSONObject(value)
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
                        freezeLastSeen = json.optBoolean("FreezeLastSeen", false) || json.optBoolean("BlockCall", false),
                        scope = json.optString("scope", "always")
                    )
                )
            } catch (_: Throwable) {}
        }
    }
    return list
}
