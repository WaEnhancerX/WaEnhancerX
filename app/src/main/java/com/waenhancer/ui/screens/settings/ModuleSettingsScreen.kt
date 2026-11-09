package com.waenhancer.ui.screens.settings

import android.app.Activity
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.app.BuildConfig
import com.waenhancer.app.R
import com.waenhancer.config.HookingModePolicy
import com.waenhancer.config.PreferenceSchema
import com.waenhancer.ui.components.BannerType
import com.waenhancer.ui.components.StitchSwitch
import com.waenhancer.ui.components.WaexInfoBanner
import com.waenhancer.ui.components.WaexModalBottomSheet
import com.waenhancer.ui.components.WaexProChip
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.InterfaceSize
import com.waenhancer.ui.designsystem.LocalInterfaceSize
import com.waenhancer.ui.designsystem.LocalInterfaceSizeUpdater
import com.waenhancer.ui.designsystem.LocalThemeMode
import com.waenhancer.ui.designsystem.LocalThemeModeUpdater
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalIsPro
import com.waenhancer.ui.navigation.LocalOnActivatePro
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import com.waenhancer.ui.navigation.Screen
import com.waenhancer.utils.LocaleHelper
import com.waenhancer.utils.UiText
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleSettingsScreen() {
    val navController = LocalWaexNavController.current
    val preferences = LocalWaexPreferenceManager.current
    val context = LocalContext.current
    val themeMode = LocalThemeMode.current
    val updateThemeMode = LocalThemeModeUpdater.current
    val interfaceSize = LocalInterfaceSize.current
    val updateInterfaceSize = LocalInterfaceSizeUpdater.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val isPro = LocalIsPro.current
    val onActivatePro = LocalOnActivatePro.current

    var showThemeSheet by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }
    var showHookingModeSheet by remember { mutableStateOf(false) }
    var showConfigModeSheet by remember { mutableStateOf(false) }

    var configMode by remember {
        mutableStateOf(preferences.getString("module_config_mode", "Universal"))
    }
    var hookingMode by remember {
        mutableStateOf(
            HookingModePolicy.normalize(
                preferences.getString(HookingModePolicy.KEY, HookingModePolicy.BALANCED),
                isPro
            )
        )
    }

    var currentLangCode by remember {
        mutableStateOf(LocaleHelper.getSelectedLanguageCode(context))
    }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = stringResource(R.string.module_settings_title),
                onBackClick = navController::popBack
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
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ─── HERO HEADER BANNER ──────────────────────────────────────────
            HeroEngineCard(
                version = BuildConfig.VERSION_NAME
            )

            // ─── SECTION 1: HOOKING & CORE ENGINE ────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSectionHeader(title = "Hooking and engine")

                SettingsGroupCard {
                    SettingsRowItem(
                        icon = WaexIcons.AutoMode,
                        iconTint = colors.onSurfaceVariant,
                        iconBackground = colors.primary.copy(alpha = 0.12f),
                        title = "Hooking mode",
                        subtitle = "Controls when safe visual hooks are loaded into WhatsApp",
                        badge = hookingModeLabel(hookingMode),
                        trailingHelpAction = {
                            navController.navigateTo(Screen.HookingModeHelp)
                        },
                        showDivider = true,
                        onClick = { showHookingModeSheet = true }
                    )

                    SettingsRowItem(
                        icon = WaexIcons.Layers,
                        iconTint = colors.onSurfaceVariant,
                        iconBackground = colors.primary.copy(alpha = 0.12f),
                        title = stringResource(R.string.config_mode_title),
                        subtitle = stringResource(R.string.config_mode_desc),
                        badge = configMode,
                        showDivider = false,
                        onClick = { showConfigModeSheet = true }
                    )
                }
            }

            // ─── SECTION 2: APPEARANCE & DISPLAY ─────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSectionHeader(title = "Appearance and display")

                SettingsGroupCard {
                    SettingsRowItem(
                        icon = themeIcon(themeMode),
                        iconTint = colors.onSurfaceVariant,
                        iconBackground = colors.surfaceContainerHighest,
                        title = stringResource(R.string.app_theme_title),
                        subtitle = stringResource(R.string.app_theme_desc),
                        value = themeLabel(themeMode),
                        showDivider = true,
                        onClick = { showThemeSheet = true }
                    )

                    InlineInterfaceSizeRow(
                        selectedSize = interfaceSize,
                        onSizeSelected = updateInterfaceSize
                    )
                }
            }

            // ─── SECTION 3: SYSTEM & UPDATES ─────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSectionHeader(title = "System and updates")

                SettingsGroupCard {
                    SettingsRowItem(
                        icon = WaexIcons.SystemUpdate,
                        iconTint = colors.onSurfaceVariant,
                        iconBackground = colors.surfaceContainerHighest,
                        title = stringResource(R.string.update_settings_title),
                        subtitle = stringResource(R.string.update_settings_desc),
                        showDivider = true,
                        onClick = { navController.navigateTo(Screen.UpdateSettings) }
                    )

                    SettingsRowItem(
                        icon = Icons.Rounded.Language,
                        iconTint = colors.onSurfaceVariant,
                        iconBackground = colors.surfaceContainerHighest,
                        title = stringResource(R.string.app_language_title),
                        subtitle = stringResource(R.string.app_language_desc),
                        value = LocaleHelper.getLanguageDisplayName(currentLangCode),
                        showDivider = false,
                        onClick = { showLanguageSheet = true }
                    )
                }
            }

            // ─── SECTION 4: DEVELOPER OPTIONS (Debug only) ───────────────────
            if (BuildConfig.DEBUG) {
                var forceTargetDebuggable by remember {
                    mutableStateOf(preferences.getBoolean(PreferenceSchema.KEY_FORCE_TARGET_DEBUGGABLE, false))
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSectionHeader(title = "Developer options")

                    SettingsGroupCard {
                        SettingsSwitchRow(
                            icon = Icons.Rounded.BugReport,
                            iconTint = colors.onSurfaceVariant,
                            iconBackground = colors.surfaceContainerHighest,
                            title = "Make target apps debuggable",
                            subtitle = "Forces JDWP debugger and debug flags on target WhatsApp applications",
                            checked = forceTargetDebuggable,
                            onCheckedChange = { isChecked ->
                                forceTargetDebuggable = isChecked
                                preferences.putBoolean(PreferenceSchema.KEY_FORCE_TARGET_DEBUGGABLE, isChecked)
                            }
                        )
                    }

                    AnimatedVisibility(
                        visible = forceTargetDebuggable,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        WaexInfoBanner(
                            title = "Scope and restart required",
                            message = "Ensure Wa Enhancer X has 'System Framework' (android) enabled under its scope in LSPosed. Force-stop and restart WhatsApp for debugging to take effect.",
                            bannerType = BannerType.INFO
                        )
                    }

                    WaexInfoBanner(
                        title = "Developer notice",
                        message = "This option allows external debuggers to attach to WhatsApp. Only enable if you need active memory analysis.",
                        bannerType = BannerType.WARNING
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }

    // ─── THEME MODAL BOTTOM SHEET ────────────────────────────────────────────
    if (showThemeSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val modes = listOf("System", "Light", "Dark") +
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf("Monet") else emptyList()

        WaexModalBottomSheet(
            onDismissRequest = { showThemeSheet = false },
            sheetState = sheetState,
            containerColor = colors.surface,
            shape = WaexTheme.radius.bottomSheetShape,
            dragHandle = { BottomSheetHandle() }
        ) {
            val sheetDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides sheetDensity) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.choose_app_theme),
                        style = typography.titleLg,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                    )
                    modes.forEach { mode ->
                        val selected = mode == themeMode
                        SelectionOptionCard(
                            title = themeLabel(mode),
                            icon = themeIcon(mode),
                            selected = selected,
                            onClick = {
                                updateThemeMode(mode)
                                showThemeSheet = false
                            }
                        )
                    }
                }
            }
        }
    }

    // ─── HOOKING MODE MODAL BOTTOM SHEET ─────────────────────────────────────
    if (showHookingModeSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        WaexModalBottomSheet(
            onDismissRequest = { showHookingModeSheet = false },
            sheetState = sheetState,
            containerColor = colors.surface,
            shape = WaexTheme.radius.bottomSheetShape,
            dragHandle = { BottomSheetHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Hooking mode",
                    style = typography.titleLg,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = "Core privacy, anti-revoke, and call features always load at startup to prevent missing background events.",
                    style = typography.bodySm,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                hookingModeOptions().forEach { option ->
                    val isProMode = option.first == HookingModePolicy.LIGHTWEIGHT ||
                        option.first == HookingModePolicy.CUSTOMIZED
                    val locked = isProMode && !isPro
                    val selected = option.first == hookingMode

                    DetailedOptionCard(
                        title = option.second,
                        description = option.third,
                        selected = selected,
                        isProBadge = isProMode,
                        isProUnlocked = isPro,
                        onClick = {
                            if (locked) {
                                showHookingModeSheet = false
                                onActivatePro()
                            } else {
                                hookingMode = option.first
                                preferences.putString(HookingModePolicy.KEY, option.first)
                                showHookingModeSheet = false
                                if (option.first == HookingModePolicy.CUSTOMIZED) {
                                    navController.navigateTo(Screen.HookingModeHelp)
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    // ─── CONFIG MODE MODAL BOTTOM SHEET ──────────────────────────────────────
    if (showConfigModeSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val configModes = listOf(
            Triple(
                "Universal",
                stringResource(R.string.config_mode_universal),
                stringResource(R.string.config_mode_universal_desc)
            ),
            Triple(
                "Per Target App",
                stringResource(R.string.config_mode_per_target_app),
                stringResource(R.string.config_mode_per_target_app_desc)
            )
        )

        WaexModalBottomSheet(
            onDismissRequest = { showConfigModeSheet = false },
            sheetState = sheetState,
            containerColor = colors.surface,
            shape = WaexTheme.radius.bottomSheetShape,
            dragHandle = { BottomSheetHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.config_mode_title),
                    style = typography.titleLg,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )
                configModes.forEach { mode ->
                    val selected = mode.first == configMode
                    DetailedOptionCard(
                        title = mode.second,
                        description = mode.third,
                        selected = selected,
                        onClick = {
                            configMode = mode.first
                            preferences.putString("module_config_mode", mode.first)
                            showConfigModeSheet = false
                        }
                    )
                }
            }
        }
    }

    // ─── LANGUAGE MODAL BOTTOM SHEET ─────────────────────────────────────────
    if (showLanguageSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        WaexModalBottomSheet(
            onDismissRequest = { showLanguageSheet = false },
            sheetState = sheetState,
            containerColor = colors.surface,
            shape = WaexTheme.radius.bottomSheetShape,
            dragHandle = { BottomSheetHandle() }
        ) {
            val sheetDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides sheetDensity) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.choose_app_language),
                            style = typography.titleLg,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                        )
                    }

                    items(LocaleHelper.SUPPORTED_LANGUAGES, key = { it.code }) { lang ->
                        val selected = lang.code == currentLangCode
                        Surface(
                            shape = WaexTheme.radius.mdShape,
                            color = if (selected) colors.primary.copy(alpha = 0.10f) else colors.surfaceContainerLow,
                            border = BorderStroke(
                                1.dp,
                                if (selected) colors.primary.copy(alpha = 0.75f) else colors.outlineVariant
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentLangCode = lang.code
                                    LocaleHelper.applyLanguage(context, lang.code)
                                    showLanguageSheet = false
                                    (context as? Activity)?.recreate()
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = LocaleHelper.getLanguageDisplayName(lang.code),
                                        style = typography.bodyMd,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (selected) colors.primary else colors.onSurface
                                    )
                                    if (LocaleHelper.getLanguageExplanation(lang.code) != LocaleHelper.getLanguageDisplayName(lang.code)) {
                                        Text(
                                            text = LocaleHelper.getLanguageExplanation(lang.code),
                                            style = typography.bodySm,
                                            color = colors.onSurfaceVariant
                                        )
                                    }
                                }
                                if (selected) {
                                    Icon(
                                        imageVector = WaexIcons.Check,
                                        contentDescription = null,
                                        tint = colors.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── COMPONENT BUILDERS ──────────────────────────────────────────────────────

@Composable
private fun HeroEngineCard(
    version: String
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography

    Surface(
        shape = WaexTheme.radius.cardShape,
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_app_logo),
                    contentDescription = "WA Enhancer X",
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                )

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "WA Enhancer X",
                        style = typography.titleMd,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    Text(
                        text = "Styles, Hooking & Config Mode",
                        style = typography.bodySm,
                        color = colors.onSurfaceVariant
                    )
                }

                Surface(
                    shape = WaexTheme.radius.buttonShape,
                    color = colors.primary.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = "v$version",
                        style = typography.labelSm,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography

    Text(
        text = title,
        style = typography.labelSm,
        fontWeight = FontWeight.Bold,
        color = colors.primary,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
    )
}

@Composable
private fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = WaexTheme.colors

    Surface(
        shape = WaexTheme.radius.cardShape,
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun SettingsRowItem(
    icon: ImageVector,
    iconTint: Color,
    iconBackground: Color,
    title: String,
    subtitle: String,
    badge: String? = null,
    value: String? = null,
    trailingHelpAction: (() -> Unit)? = null,
    showDivider: Boolean = true,
    onClick: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(iconBackground, WaexTheme.radius.mdShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = typography.bodyMd,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    if (badge != null) {
                        Surface(
                            shape = WaexTheme.radius.smShape,
                            color = colors.primary.copy(alpha = 0.12f),
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(
                                text = badge,
                                style = typography.labelSm,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.primary,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = typography.bodySm,
                    color = colors.onSurfaceVariant
                )
            }

            if (trailingHelpAction != null) {
                IconButton(
                    onClick = trailingHelpAction,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = WaexIcons.Help,
                        contentDescription = "Help",
                        tint = colors.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (value != null) {
                Text(
                    text = value,
                    style = typography.labelSm,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primary,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }

            Icon(
                imageVector = WaexIcons.ChevronRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(16.dp)
            )
        }

        if (showDivider) {
            HorizontalDivider(
                color = colors.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.7.dp,
                modifier = Modifier.padding(start = 68.dp, end = 16.dp)
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    iconTint: Color,
    iconBackground: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(iconBackground, WaexTheme.radius.mdShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = typography.bodyMd,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = typography.bodySm,
                color = colors.onSurfaceVariant
            )
        }

        Spacer(Modifier.width(10.dp))

        StitchSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun InlineInterfaceSizeRow(
    selectedSize: InterfaceSize,
    onSizeSelected: (InterfaceSize) -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val sizes = InterfaceSize.entries
    var sliderPosition by remember(selectedSize) { mutableStateOf(selectedSize.ordinal.toFloat()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(Color(0xFF3B82F6).copy(alpha = 0.12f), WaexTheme.radius.mdShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = WaexIcons.Smartphone,
                    contentDescription = null,
                    tint = Color(0xFF3B82F6),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.interface_size_title),
                    style = typography.bodyMd,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.interface_size_desc),
                    style = typography.bodySm,
                    color = colors.onSurfaceVariant
                )
            }

            Surface(
                shape = WaexTheme.radius.smShape,
                color = colors.primary.copy(alpha = 0.12f),
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(
                    text = interfaceSizeLabel(selectedSize),
                    style = typography.labelSm,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Slider(
            value = sliderPosition,
            onValueChange = { sliderPosition = it },
            onValueChangeFinished = {
                val newSize = sizes[sliderPosition.roundToInt().coerceIn(sizes.indices)]
                sliderPosition = newSize.ordinal.toFloat()
                if (newSize != selectedSize) onSizeSelected(newSize)
            },
            valueRange = 0f..(sizes.lastIndex.toFloat()),
            steps = sizes.size - 2,
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary,
                inactiveTrackColor = colors.outlineVariant
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            sizes.forEach { size ->
                val isSelected = size == selectedSize
                Text(
                    text = interfaceSizeLabel(size),
                    style = typography.labelSm,
                    color = if (isSelected) colors.primary else colors.onSurfaceVariant,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                    textAlign = when (size) {
                        InterfaceSize.COMPACT -> TextAlign.Start
                        InterfaceSize.LARGE -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun BottomSheetHandle() {
    val colors = WaexTheme.colors
    Box(
        Modifier
            .padding(top = 10.dp, bottom = 6.dp)
            .size(width = 36.dp, height = 4.dp)
            .background(colors.outlineVariant, RoundedCornerShape(2.dp))
    )
}

@Composable
private fun SelectionOptionCard(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography

    Surface(
        shape = WaexTheme.radius.cardShape,
        color = if (selected) colors.primary.copy(alpha = 0.12f) else colors.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            if (selected) colors.primary else colors.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = title,
                style = typography.bodyLg,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) colors.primary else colors.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(
                    imageVector = WaexIcons.Check,
                    contentDescription = UiText.get(R.string.waex_ui_selected, "Selected"),
                    tint = colors.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun DetailedOptionCard(
    title: String,
    description: String,
    selected: Boolean,
    isProBadge: Boolean = false,
    isProUnlocked: Boolean = false,
    onClick: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography

    Surface(
        shape = WaexTheme.radius.cardShape,
        color = if (selected) colors.primary.copy(alpha = 0.12f) else colors.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            if (selected) colors.primary else colors.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    style = typography.bodyLg,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (isProBadge) {
                    WaexProChip(isUnlocked = isProUnlocked)
                }
                if (selected) {
                    Icon(
                        imageVector = WaexIcons.Check,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Text(
                text = description,
                style = typography.bodySm,
                color = colors.onSurfaceVariant
            )
        }
    }
}

// ─── HELPERS ────────────────────────────────────────────────────────────────

private fun hookingModeOptions(): List<Triple<String, String, String>> = listOf(
    Triple(
        HookingModePolicy.LIGHTWEIGHT,
        "Lightweight",
        "Ultra-Low RAM Usage (<80 MB) · Pro only · Hooks load lazily per screen."
    ),
    Triple(
        HookingModePolicy.BALANCED,
        "Balanced (recommended)",
        "Moderate RAM Usage (~180 MB) · Home UI at startup, other hooks load lazily."
    ),
    Triple(
        HookingModePolicy.COMPATIBILITY,
        "Maximum compatibility",
        "High RAM Usage (~500 MB+) · All hooks pre-loaded at startup."
    ),
    Triple(
        HookingModePolicy.CUSTOMIZED,
        "Customized",
        "Pro only · Choose Startup or Lazy loading for each feature. Defaults match Balanced."
    )
)

private fun hookingModeLabel(mode: String): String = when (mode) {
    HookingModePolicy.COMPATIBILITY -> "Max"
    HookingModePolicy.LIGHTWEIGHT -> "Lightweight"
    HookingModePolicy.CUSTOMIZED -> "Customized"
    else -> "Balanced"
}

private fun themeIcon(mode: String) = when (mode) {
    "Light" -> WaexIcons.LightMode
    "Dark" -> WaexIcons.DarkMode
    "Monet" -> WaexIcons.Palette
    else -> WaexIcons.AutoMode
}

@Composable
private fun themeLabel(mode: String) = when (mode) {
    "System" -> stringResource(R.string.theme_follow_system)
    "Light" -> stringResource(R.string.theme_light)
    "Dark" -> stringResource(R.string.theme_dark)
    "Monet" -> stringResource(R.string.theme_monet)
    else -> mode
}

@Composable
private fun interfaceSizeLabel(size: InterfaceSize) = when (size) {
    InterfaceSize.COMPACT -> stringResource(R.string.interface_size_compact)
    InterfaceSize.STANDARD -> stringResource(R.string.interface_size_standard)
    InterfaceSize.COMFORTABLE -> stringResource(R.string.interface_size_comfortable)
    InterfaceSize.LARGE -> stringResource(R.string.interface_size_large)
}
