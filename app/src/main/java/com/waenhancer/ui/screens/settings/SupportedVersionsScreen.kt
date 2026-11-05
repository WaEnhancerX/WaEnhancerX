package com.waenhancer.ui.screens.settings

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.waenhancer.ui.components.WaexModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.components.StitchSwitch
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import com.waenhancer.utils.UniversalVersionValidator
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextAlign
import com.waenhancer.config.PreferenceStores
import com.waenhancer.utils.WhatsAppPackageDetector

/**
 * Universal Supported Versions Screen.
 * Dynamically displays all installed WhatsApp targets and allows 1-tap app selection
 * to add universal wildcard version rules without manual typing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportedVersionsScreen() {
    val context = LocalContext.current
    val navController = LocalWaexNavController.current
    val prefManager = LocalWaexPreferenceManager.current
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val spacing = WaexTheme.spacing

    val prefs = remember(context) { PreferenceStores.publicStore(context) }

    var isCustomizeEnabled by remember(prefManager.targetEpoch) {
        mutableStateOf(prefManager.getBoolean("customize_supported_versions", false))
    }

    var isBypassEnabled by remember(prefManager.targetEpoch) {
        mutableStateOf(prefManager.getBoolean("bypass_version_check", false))
    }

    val customVersionsList = remember {
        mutableStateListOf<String>().apply {
            addAll(UniversalVersionValidator.getCustomVersions(prefs))
        }
    }

    fun refreshCustomList() {
        customVersionsList.clear()
        customVersionsList.addAll(UniversalVersionValidator.getCustomVersions(prefs))
    }

    // Dynamic list of detected WhatsApp packages & clones
    val detectedApps = remember(customVersionsList.size, isCustomizeEnabled, isBypassEnabled) {
        WhatsAppPackageDetector.detectWhatsAppApps(context)
    }

    val decisions = detectedApps.associate {
        it.packageName to UniversalVersionValidator.evaluate(context, it.versionName, prefs)
    }
    val unsupportedApps = detectedApps.filter { app ->
        app.isInstalled && decisions[app.packageName] == UniversalVersionValidator.Decision.UNSUPPORTED
    }

    var showSelectAppDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = UiText.get(R.string.title_supported_versions, "Supported Versions"),
                onBackClick = { navController.popBack() }
            )
        },
        containerColor = colors.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(
                horizontal = spacing.pageMargin,
                vertical = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Installed App Status Banner
            item {
                Text(
                    text = UiText.get(R.string.waex_ui_target_applications_compatibility, "TARGET APPLICATIONS COMPATIBILITY"),
                    style = typography.labelSm,
                    color = colors.primary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    detectedApps.forEach { app ->
                        val cleanVer = app.versionName.removePrefix("v")
                        val decision = decisions.getValue(app.packageName)
                        AppVersionStatusCard(
                            appName = app.appName,
                            packageName = app.packageName,
                            versionName = if (app.isInstalled) cleanVer else null,
                            decision = decision,
                            onAddCurrentClick = {
                                if (app.isInstalled) {
                                    val wildcardRule = UniversalVersionValidator.toWildcard(cleanVer)
                                    UniversalVersionValidator.addCustomVersion(prefs, wildcardRule)
                                    prefManager.putBoolean("customize_supported_versions", true)
                                    isCustomizeEnabled = true
                                    refreshCustomList()
                                    Toast.makeText(
                                        context,
                                        UiText.get(R.string.waex_ui_added_universal_rule_1_s, "Added universal rule %1\$s", wildcardRule),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                    }
                }
            }

            // 2. Global Version Controls
            item {
                Text(
                    text = UiText.get(R.string.waex_ui_settings_enforcement, "SETTINGS & ENFORCEMENT"),
                    style = typography.labelSm,
                    color = colors.primary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    shape = radius.mdShape,
                    color = colors.surfaceDim,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        // Customize switch
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = !isCustomizeEnabled
                                    isCustomizeEnabled = next
                                    prefManager.putBoolean("customize_supported_versions", next)
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text = UiText.get(R.string.waex_ui_enable_custom_versions, "Enable Custom Versions"),
                                    style = typography.bodyMd,
                                    color = colors.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = UiText.get(R.string.waex_ui_allow_user_defined_versions_and_wildca, "Allow user-defined versions and wildcard rules to load hooks"),
                                    style = typography.labelSm,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            StitchSwitch(
                                checked = isCustomizeEnabled,
                                onCheckedChange = { checked ->
                                    isCustomizeEnabled = checked
                                    prefManager.putBoolean("customize_supported_versions", checked)
                                }
                            )
                        }

                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))

                        // Bypass switch
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = !isBypassEnabled
                                    isBypassEnabled = next
                                    prefManager.putBoolean("bypass_version_check", next)
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text = UiText.get(R.string.waex_ui_bypass_version_verification, "Bypass Version Verification"),
                                    style = typography.bodyMd,
                                    color = if (isBypassEnabled) colors.error else colors.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = UiText.get(R.string.waex_ui_force_waex_hooks_to_run_on_any_version, "Force WAEX hooks to run on any version without checking (Advanced)"),
                                    style = typography.labelSm,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            StitchSwitch(
                                checked = isBypassEnabled,
                                onCheckedChange = { checked ->
                                    isBypassEnabled = checked
                                    prefManager.putBoolean("bypass_version_check", checked)
                                }
                            )
                        }
                    }
                }
            }

            // 3. User-Defined Custom Versions
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = UiText.get(R.string.waex_ui_user_defined_versions_1_s, "USER-DEFINED VERSIONS (%1\$s)", customVersionsList.size),
                        style = typography.labelSm,
                        color = colors.primary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Button(
                        onClick = {
                            if (unsupportedApps.isEmpty()) {
                                Toast.makeText(
                                    context,
                                    UiText.get(R.string.waex_l10n_no_rejected_versions, "No rejected version is available to add. Check each target's rules status; this is not a feature compatibility test."),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                showSelectAppDialog = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primary,
                            contentColor = colors.surface
                        ),
                        shape = radius.smShape,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = UiText.get(R.string.action_add, "Add"),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = UiText.get(R.string.waex_ui_add_rule, "Add Rule"),
                            style = typography.labelSm,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (customVersionsList.isEmpty()) {
                item {
                    Surface(
                        shape = radius.mdShape,
                        color = colors.surfaceDim.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, colors.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.padding(20.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = UiText.get(R.string.waex_ui_no_custom_version_rules_added_yet_tap, "No custom version rules added yet.\nTap \"Add Rule\" to select an unsupported app and automatically register its version branch."),
                                style = typography.labelSm,
                                color = colors.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(customVersionsList) { customVer ->
                    VersionRuleItem(
                        version = customVer,
                        badge = UiText.get(R.string.waex_ui_user_defined, "User Defined"),
                        badgeColor = colors.primary,
                        onDeleteClick = {
                            UniversalVersionValidator.removeCustomVersion(prefs, customVer)
                            refreshCustomList()
                        }
                    )
                }
            }

            // 4. Universal Predefined System Versions
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = UiText.get(R.string.waex_ui_universal_built_in_versions, "UNIVERSAL BUILT-IN VERSIONS"),
                    style = typography.labelSm,
                    color = colors.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            items(UniversalVersionValidator.getBuiltinSupportedVersions(context)) { sysVer ->
                VersionRuleItem(
                    version = sysVer,
                    badge = UiText.get(R.string.waex_ui_universal_base, "Universal Base"),
                    badgeColor = colors.secondary,
                    onDeleteClick = null
                )
            }
        }
    }

    // App Selection Sheet for Adding Version Rule
    if (showSelectAppDialog) {
        WaexModalBottomSheet(
            onDismissRequest = { showSelectAppDialog = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.surface,
            shape = radius.bottomSheetShape
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = UiText.get(R.string.waex_ui_select_unsupported_app, "Select Unsupported App"),
                    style = typography.headlineMd,
                    color = colors.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = UiText.get(R.string.waex_ui_select_an_installed_app_to_automatical, "Select an installed app to automatically generate and register its universal version rule:"),
                    style = typography.labelSm,
                    color = colors.onSurfaceVariant
                )

                unsupportedApps.forEach { app ->
                        val cleanVer = app.versionName.removePrefix("v")
                        val wildcard = UniversalVersionValidator.toWildcard(cleanVer)
                        Surface(
                            shape = radius.smShape,
                            color = colors.surfaceDim,
                            border = BorderStroke(1.dp, colors.outlineVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    UniversalVersionValidator.addCustomVersion(prefs, wildcard)
                                    prefManager.putBoolean("customize_supported_versions", true)
                                    isCustomizeEnabled = true
                                    refreshCustomList()
                                    showSelectAppDialog = false
                                    Toast.makeText(
                                        context,
                                        UiText.get(R.string.waex_ui_added_universal_rule_1_s, "Added universal rule %1\$s", wildcard),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = app.appName,
                                        style = typography.bodyMd,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = "${app.packageName} • v$cleanVer",
                                        style = typography.labelSm,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                Surface(
                                    shape = radius.smShape,
                                    color = colors.primary.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "+ $wildcard",
                                        style = typography.labelSm,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.primary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                }

                OutlinedButton(
                    onClick = { showSelectAppDialog = false },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = radius.buttonShape
                ) {
                    Text(UiText.get(R.string.action_cancel, "Cancel"))
                }
            }
        }
    }
}

@Composable
private fun AppVersionStatusCard(
    appName: String,
    packageName: String,
    versionName: String?,
    decision: UniversalVersionValidator.Decision,
    onAddCurrentClick: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val isInstalled = versionName != null
    val isSupported = decision == UniversalVersionValidator.Decision.BUILTIN_RULE

    Surface(
        shape = radius.mdShape,
        color = colors.surfaceDim,
        border = BorderStroke(
            1.dp,
            if (!isInstalled) colors.outlineVariant
            else if (isSupported) colors.success.copy(alpha = 0.55f)
            else colors.warning.copy(alpha = 0.6f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (packageName == "com.whatsapp") Color(0xFF25D366).copy(alpha = 0.15f)
                                else Color(0xFF3B82F6).copy(alpha = 0.15f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = appName,
                            tint = if (packageName == "com.whatsapp") Color(0xFF25D366) else Color(0xFF3B82F6),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = appName,
                            style = typography.bodyMd,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                        Text(
                            text = if (isInstalled) UiText.get(R.string.waex_ui_installed_v_1_s, "Installed: v%1\$s", versionName) else UiText.get(R.string.waex_ui_not_installed_on_this_device, "Not installed on this device"),
                            style = typography.labelSm,
                            color = colors.onSurfaceVariant
                        )
                    }
                }

                // Status Badge
                Surface(
                    shape = radius.smShape,
                    color = when {
                        !isInstalled -> colors.outlineVariant.copy(alpha = 0.3f)
                        isSupported -> colors.successContainer
                        else -> colors.warningContainer
                    }
                ) {
                    Text(
                        text = when {
                            !isInstalled -> UiText.get(R.string.waex_ui_unavailable, "Unavailable")
                            decision == UniversalVersionValidator.Decision.BUILTIN_RULE -> UiText.get(R.string.waex_l10n_builtin_match, "Supported")
                            decision == UniversalVersionValidator.Decision.CUSTOM_RULE -> UiText.get(R.string.waex_l10n_custom_unverified, "Custom")
                            decision == UniversalVersionValidator.Decision.EXPLICIT_BYPASS -> UiText.get(R.string.waex_l10n_bypass_unverified, "Bypass active (unverified)")
                            decision == UniversalVersionValidator.Decision.UNSUPPORTED -> UiText.get(R.string.waex_l10n_rejected_rules, "Version rejected by rules")
                            else -> UiText.get(R.string.waex_l10n_version_check_unavailable, "Version check unavailable")
                        },
                        style = typography.labelSm,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            !isInstalled -> colors.onSurfaceVariant
                            isSupported -> colors.onSuccessContainer
                            else -> colors.onWarningContainer
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Quick 1-tap add button if unsupported
            if (isInstalled && decision == UniversalVersionValidator.Decision.UNSUPPORTED) {
                val wildcard = UniversalVersionValidator.toWildcard(versionName)
                Button(
                    onClick = onAddCurrentClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.warning,
                        contentColor = colors.onWarning
                    ),
                    shape = radius.smShape,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = UiText.get(R.string.waex_ui_add_universal_rule_1_s, "Add Universal Rule (%1\$s)", wildcard),
                        style = typography.labelSm,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionRuleItem(
    version: String,
    badge: String,
    badgeColor: Color,
    onDeleteClick: (() -> Unit)?
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    Surface(
        shape = radius.smShape,
        color = colors.surfaceDim,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = version,
                    style = typography.bodyMd,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
                Spacer(modifier = Modifier.width(10.dp))
                Surface(
                    shape = radius.smShape,
                    color = badgeColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = badge,
                        style = typography.labelSm,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (onDeleteClick != null) {
                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = UiText.get(R.string.action_delete, "Delete"),
                        tint = colors.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
