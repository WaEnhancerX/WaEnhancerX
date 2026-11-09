package com.waenhancer.ui.screens.dashboard

import com.waenhancer.utils.UiText
import com.waenhancer.utils.InternalLabels

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.waenhancer.ui.components.WaexModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.waenhancer.ui.components.WaexCircularProgressIndicator
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.app.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.waenhancer.config.PreferenceStores
import com.waenhancer.ui.components.WaexConfirmationBottomSheet
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import com.waenhancer.ui.navigation.Screen
import com.waenhancer.utils.HmaDiagnostics
import com.waenhancer.utils.MagiskDenylistDiagnostics
import com.waenhancer.utils.HookedAppInfo
import com.waenhancer.utils.InjectedHookItem
import com.waenhancer.utils.SystemDiagnostics
import com.waenhancer.utils.SystemDiagnosticsReader
import com.waenhancer.utils.UniversalVersionValidator
import com.waenhancer.utils.WhatsAppPackageDetector
import com.waenhancer.utils.XposedRuntimeInspector
import com.waenhancer.utils.LsposedScopeReader
import com.waenhancer.utils.LsposedScopeSnapshot
import com.waenhancer.utils.TargetAppManager
import com.waenhancer.utils.TargetRuntimePresentation
import com.waenhancer.ui.components.rememberRuntimeObservationClock
import com.waenhancer.xposed.utils.AppRestartHelper
import com.waenhancer.xposed.utils.ModuleStatus
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Build

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboardScreen(
    licenseState: String,
    onOpenModal: (String) -> Unit,
    targetEpoch: Int = 0
) {
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val preferenceManager = LocalWaexPreferenceManager.current
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val isModuleActive = remember(refreshTrigger) { ModuleStatus.isModuleActive() }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            runCatching { preferenceManager.exportSettings(uri) }
                .onSuccess { Toast.makeText(context, UiText.get(R.string.waex_ui_1_s_settings_backed_up, "%1\$s settings backed up", it), Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, UiText.get(R.string.waex_ui_backup_failed_1_s, "Backup failed: %1\$s", it.message), Toast.LENGTH_LONG).show() }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { preferenceManager.importSettings(uri) }
                .onSuccess { Toast.makeText(context, UiText.get(R.string.waex_ui_1_s_settings_restored_restart_whatsapp, "%1\$s settings restored. Restart WhatsApp to apply.", it), Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(context, UiText.get(R.string.waex_ui_restore_failed_1_s, "Restore failed: %1\$s", it.message), Toast.LENGTH_LONG).show() }
        }
    }
    val startBackup = {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        runCatching { backupLauncher.launch("WAEX-settings-$stamp.waex") }
            .onFailure { Toast.makeText(context, UiText.get(R.string.waex_ui_could_not_open_file_picker_1_s, "Could not open file picker: %1\$s", it.message), Toast.LENGTH_LONG).show() }
        Unit
    }
    val startRestore = {
        runCatching { restoreLauncher.launch(arrayOf("application/octet-stream", "*/*")) }
            .onFailure { Toast.makeText(context, UiText.get(R.string.waex_ui_could_not_open_file_picker_1_s, "Could not open file picker: %1\$s", it.message), Toast.LENGTH_LONG).show() }
        Unit
    }

    var diagnosticsRefreshTrigger by remember { mutableIntStateOf(0) }
    val scopeSnapshot by TargetAppManager.scopeSnapshot.collectAsState()
    val runtimeSnapshot by TargetAppManager.runtimeSnapshot.collectAsState()
    val reportedApps by TargetAppManager.detectedApps.collectAsState()
    val refreshEpoch by TargetAppManager.refreshEpoch.collectAsState()

    var systemDiagnostics by remember {
        mutableStateOf(SystemDiagnosticsReader.getOrFastSnapshot(context, isModuleActive))
    }
    var hmaRefreshTrigger by remember { mutableIntStateOf(0) }
    var hmaStatus by remember {
        mutableStateOf<HmaDiagnostics.HmaReport?>(HmaDiagnostics.cached())
    }
    var denylistRefreshTrigger by remember { mutableIntStateOf(0) }
    var denylistStatus by remember {
        mutableStateOf<MagiskDenylistDiagnostics.DenylistReport?>(MagiskDenylistDiagnostics.cached())
    }

    // Target heartbeats arrive every ten seconds. They refresh target evidence, while the
    // root/framework/package inventory only needs a screen resume or an explicit refresh.
    LaunchedEffect(diagnosticsRefreshTrigger) {
        systemDiagnostics = withContext(Dispatchers.IO) { SystemDiagnosticsReader.read(context, isModuleActive) }
    }

    LaunchedEffect(hmaRefreshTrigger, scopeSnapshot, refreshEpoch) {
        val targets = reportedApps.filter { it.isInstalled }.map { it.packageName }
        val report = HmaDiagnostics.checkHmaStatus(
            context = context,
            targetPackages = targets,
            scopeSnapshot = scopeSnapshot,
            forceRefresh = hmaRefreshTrigger > 0
        )
        hmaStatus = report
    }

    LaunchedEffect(denylistRefreshTrigger, scopeSnapshot, refreshEpoch) {
        val targets = reportedApps.filter { it.isInstalled }.map { it.packageName }
        val report = MagiskDenylistDiagnostics.checkDenylistStatus(
            context = context,
            targetPackages = targets,
            scopeSnapshot = scopeSnapshot,
            forceRefresh = denylistRefreshTrigger > 0
        )
        denylistStatus = report
    }

    // Re-check target apps and diagnostics on resume
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTrigger++
                diagnosticsRefreshTrigger++
                TargetAppManager.refresh(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val observationTime = rememberRuntimeObservationClock()
    val detectedApps = reportedApps.map {
        TargetRuntimePresentation.asOf(it, observationTime.wall, observationTime.elapsed)
    }
    val prefs = remember(context) { PreferenceStores.publicStore(context) }
    val versionDecisions = remember(reportedApps, refreshTrigger, refreshEpoch) {
        reportedApps.associate { it.packageName to UniversalVersionValidator.evaluate(context, it.versionName, prefs) }
    }
    val targetSummary = TargetRuntimePresentation.summarize(detectedApps, versionDecisions)
    val hasTargetReview = targetSummary.healthy == false
    val moduleVersion = remember(context) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }

    var showCustomizationSoon by remember { mutableStateOf(false) }
    var showResetSettingsDialog by remember { mutableStateOf(false) }
    var showHmaDetailSheet by remember { mutableStateOf(false) }
    var showDenylistDetailSheet by remember { mutableStateOf(false) }
    var showTargetCompatibilitySheet by remember { mutableStateOf(false) }
    var isFixingHma by remember { mutableStateOf(false) }
    var isFixingDenylist by remember { mutableStateOf(false) }

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
                shape = radius.bentoCardShape,
                color = if (!isModuleActive || hasTargetReview) colors.errorContainer else colors.surfaceContainer,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(radius.mdShape)
                                .background(
                                    when {
                                        !isModuleActive || hasTargetReview -> colors.error
                                        else -> colors.primaryContainer
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = WaexIcons.ShieldHeart,
                                contentDescription = null,
                                tint = when {
                                    !isModuleActive || hasTargetReview -> colors.onError
                                    else -> colors.primary
                                },
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(
                                    if (isModuleActive) R.string.module_active else R.string.module_inactive
                                ),
                                style = typography.titleLg,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (!isModuleActive || hasTargetReview) colors.onErrorContainer else colors.onSurface
                            )
                            Text(
                                text = buildString {
                                    append("v")
                                    append(moduleVersion)
                                    if (hasTargetReview) {
                                        append(" • ")
                                        append(context.getString(R.string.diag_status_review_required))
                                    }
                                },
                                style = typography.bodySm,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (!isModuleActive || hasTargetReview) {
                                    colors.onErrorContainer
                                } else {
                                    colors.onSurfaceVariant
                                }
                            )
                        }


                        Box(
                            modifier = Modifier
                                .clip(radius.smShape)
                                .background(colors.surfaceContainerHighest)
                                .clickable { navController.navigateTo(Screen.Changelog) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.title_changelog),
                                style = typography.labelSm,
                                color = colors.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Target Apps: Vertical List of Cards with App Icon, Title, Package Name, Status Dot, Injected Hooks Badge, and Restart Action
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        detectedApps.forEach { app ->
                            val cleanVer = app.versionName.removePrefix("v")
                            val decision = versionDecisions[app.packageName] ?: UniversalVersionValidator.Decision.RULES_UNAVAILABLE
                            val isVersionSupported = decision.allowed
                            val appIcon = remember(app.packageName) {
                                WhatsAppPackageDetector.getAppIcon(context, app.packageName)
                            }

                            val status = TargetRuntimePresentation.card(app, isModuleActive, decision)
                            val statusDotColor = when (status.severity) {
                                TargetRuntimePresentation.Severity.OK -> colors.primary
                                TargetRuntimePresentation.Severity.REVIEW -> colors.error
                                TargetRuntimePresentation.Severity.UNKNOWN -> colors.onSurfaceVariant
                            }
                            val statusText = InternalLabels.text(status.text)
                            val statusTextColor = statusDotColor

                            Surface(
                                shape = radius.defaultShape,
                                color = colors.surfaceDim,
                                border = BorderStroke(
                                    1.dp,
                                    if (app.isInstalled && !isVersionSupported) colors.error.copy(alpha = 0.4f) else colors.outlineVariant
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
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
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
                                                Image(
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
                                                overflow = TextOverflow.Ellipsis
                                            )

                                            Text(
                                                text = "${app.packageName} • ${app.versionName}",
                                                style = typography.labelSm,
                                                color = colors.onSurfaceVariant.copy(alpha = 0.85f),
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )

                                            Spacer(modifier = Modifier.height(4.dp))

                                            // Status Dot & Label
                                            Row(
                                                modifier = Modifier.weight(1f),
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
                                                        AppRestartHelper.restartPackage(
                                                            context,
                                                            app.packageName,
                                                            app.appName
                                                        )
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = WaexIcons.Refresh,
                                                    contentDescription = UiText.get(R.string.waex_ui_restart_app, "Restart App"),
                                                    tint = colors.onSurfaceVariant,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                        }
                                    }

                                    // Realtime Injected Hooks Badge / Pill (if injected and active)
                                    if (app.isHooked && app.injectedHooks.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant.copy(alpha = 0.5f))
                                        Spacer(modifier = Modifier.height(8.dp))

                                        val activeCount = app.injectedHooks.count { it.status == "ACTIVE" }
                                        val totalCount = app.injectedHooks.size

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(radius.smShape)
                                                .background(colors.surface)
                                                .border(1.dp, colors.outlineVariant, radius.smShape)
                                                .clickable { navController.navigateTo(Screen.InjectedHooks(app.packageName, app.appName)) }
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = WaexIcons.Extension,
                                                    contentDescription = null,
                                                    tint = colors.primary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Text(
                                                    text = stringResource(
                                                        R.string.injected_hooks_active_count,
                                                        activeCount,
                                                        totalCount
                                                    ),
                                                    style = typography.labelSm,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = colors.onSurface,
                                                    fontSize = 11.sp,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                            Row(
                                                modifier = Modifier.padding(start = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.view_all),
                                                    style = typography.labelSm,
                                                    color = colors.primary,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                                Icon(
                                                    imageVector = WaexIcons.ChevronRight,
                                                    contentDescription = null,
                                                    tint = colors.primary,
                                                    modifier = Modifier.size(13.dp)
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

            DashboardDiagnosticsCard(
                isModuleActive = isModuleActive,
                diagnostics = systemDiagnostics,
                targetSummary = targetSummary,
                hmaReport = hmaStatus,
                denylistReport = denylistStatus,
                onRefresh = {
                    refreshTrigger++
                    diagnosticsRefreshTrigger++
                    hmaRefreshTrigger++
                    denylistRefreshTrigger++
                    TargetAppManager.refresh(context, force = true)
                },
                onViewDetails = { navController.navigateTo(Screen.SystemHealth) },
                onViewSupportedVersions = { navController.navigateTo(Screen.SupportedVersions) },
                onOpenHmaDetails = { showHmaDetailSheet = true },
                onOpenDenylistDetails = { showDenylistDetailSheet = true },
                onOpenTargetCompatibility = { showTargetCompatibilitySheet = true }
            )

            // Quick Backup & Maintenance Row
            Surface(
                shape = radius.cardShape,
                color = colors.surfaceContainer,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = startBackup,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = radius.buttonShape,
                            border = BorderStroke(1.dp, colors.outlineVariant),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = colors.surfaceDim,
                                contentColor = colors.onSurface
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = WaexIcons.Folder, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = stringResource(R.string.backup_btn), style = typography.bodyMd, fontWeight = FontWeight.Medium, color = colors.onSurface)
                            }
                        }

                        OutlinedButton(
                            onClick = startRestore,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = radius.buttonShape,
                            border = BorderStroke(1.dp, colors.outlineVariant),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = colors.surfaceDim,
                                contentColor = colors.onSurface
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = WaexIcons.Refresh, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = stringResource(R.string.restore_btn), style = typography.bodyMd, fontWeight = FontWeight.Medium, color = colors.onSurface)
                            }
                        }
                    }

                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.65f))

                    OutlinedButton(
                        onClick = { showResetSettingsDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = radius.buttonShape,
                        border = BorderStroke(1.dp, colors.error.copy(alpha = 0.55f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = colors.errorContainer,
                            contentColor = colors.onErrorContainer
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = WaexIcons.Clear,
                                contentDescription = null,
                                tint = colors.onErrorContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.reset_settings_btn),
                                style = typography.bodyMd,
                                fontWeight = FontWeight.Medium,
                                color = colors.onErrorContainer
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }

        // Reset Settings Confirmation Sheet
        if (showResetSettingsDialog) {
            WaexConfirmationBottomSheet(
                title = stringResource(R.string.reset_settings_confirm_title),
                message = stringResource(R.string.reset_settings_confirm_msg),
                confirmLabel = stringResource(R.string.reset_settings_btn),
                destructive = true,
                onDismiss = { showResetSettingsDialog = false },
                onConfirm = {
                    val savedTheme = preferenceManager.getString("app_theme_mode", "System")
                    val savedInterfaceSize = preferenceManager.getString("app_interface_size", "Compact")
                    val publicStore = PreferenceStores.publicStore(context)
                    publicStore.edit().clear().commit()
                    if (savedTheme != null) {
                        preferenceManager.putString("app_theme_mode", savedTheme)
                    }
                    if (savedInterfaceSize != null) {
                        preferenceManager.putString("app_interface_size", savedInterfaceSize)
                    }
                    preferenceManager.remove("dummy_notify")
                    refreshTrigger++
                    diagnosticsRefreshTrigger++
                    showResetSettingsDialog = false
                    Toast.makeText(context, UiText.get(R.string.waex_ui_all_settings_reset_to_default, "All settings reset to default"), Toast.LENGTH_SHORT).show()
                }
            )
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
                            text = stringResource(R.string.customization_coming_soon_title),
                            style = typography.headlineMd,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                        Text(
                            text = stringResource(R.string.customization_coming_soon_desc),
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
                            Text(text = stringResource(R.string.action_ok), style = typography.bodyMd, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // HMA Isolation Details Bottom Sheet
        if (showHmaDetailSheet && hmaStatus != null) {
            HmaDetailsBottomSheet(
                report = hmaStatus!!,
                visiblePackages = detectedApps.filter { it.isInstalled && it.inLsposedScope && it.isHooked }.map { it.packageName }.toSet(),
                isFixing = isFixingHma,
                onFixTarget = { targetPkg ->
                    isFixingHma = true
                    coroutineScope.launch {
                        val success = HmaDiagnostics.fixTargetIsolation(context, targetPkg)
                        isFixingHma = false
                        if (success) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.diag_hma_fix_success),
                                Toast.LENGTH_SHORT
                            ).show()
                            hmaRefreshTrigger++
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.diag_hma_fix_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                onDismiss = { showHmaDetailSheet = false }
            )
        }

        // Magisk DenyList Details Bottom Sheet
        if (showDenylistDetailSheet && denylistStatus != null) {
            DenylistDetailsBottomSheet(
                report = denylistStatus!!,
                visiblePackages = detectedApps.filter { it.isInstalled && it.inLsposedScope && it.isHooked }.map { it.packageName }.toSet(),
                isFixing = isFixingDenylist,
                onFixTarget = { targetPkg ->
                    isFixingDenylist = true
                    coroutineScope.launch {
                        val success = MagiskDenylistDiagnostics.fixPackage(context, targetPkg)
                        isFixingDenylist = false
                        if (success) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.diag_denylist_fix_success),
                                Toast.LENGTH_SHORT
                            ).show()
                            denylistRefreshTrigger++
                            refreshTrigger++
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.diag_denylist_fix_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                onFixAll = { blockedList ->
                    isFixingDenylist = true
                    coroutineScope.launch {
                        val success = MagiskDenylistDiagnostics.fixAllBlocked(context, blockedList)
                        isFixingDenylist = false
                        if (success) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.diag_denylist_fix_success),
                                Toast.LENGTH_SHORT
                            ).show()
                            denylistRefreshTrigger++
                            refreshTrigger++
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.diag_denylist_fix_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                onDismiss = { showDenylistDetailSheet = false }
            )
        }

        // Target Compatibility Details Bottom Sheet
        if (showTargetCompatibilitySheet) {
            TargetCompatibilityBottomSheet(
                apps = detectedApps,
                targetSummary = targetSummary,
                onDismiss = { showTargetCompatibilitySheet = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetCompatibilityBottomSheet(
    apps: List<HookedAppInfo>,
    targetSummary: TargetRuntimePresentation.Summary,
    onDismiss: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val installedTargets = remember(apps) { apps.filter { it.isInstalled } }
    val isYellow = targetSummary.healthy == null
    val isRed = targetSummary.healthy == false
    // True when isYellow but all apps are already hooked — means custom version rule, not "not yet opened"
    val isCustomRuleYellow = isYellow && installedTargets.isNotEmpty() && installedTargets.all { it.isHooked }

    WaexModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(colors.outlineVariant)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.diag_target_sheet_title),
                    style = typography.titleMd,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.diag_target_sheet_desc),
                    style = typography.bodySm,
                    color = colors.onSurfaceVariant
                )
            }

            // Status Banner explaining why it is yellow / unconfirmed
            val bannerColor = when {
                isRed -> colors.error
                isYellow -> colors.warning
                else -> colors.success
            }

            Surface(
                shape = radius.smShape,
                color = bannerColor.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, bannerColor.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = bannerColor.copy(alpha = 0.18f),
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = when {
                                    isRed -> WaexIcons.Clear
                                    isYellow -> WaexIcons.Help
                                    else -> WaexIcons.Check
                                },
                                contentDescription = null,
                                tint = bannerColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = InternalLabels.text(targetSummary.text),
                            style = typography.labelLg,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                        Text(
                            text = when {
                                isRed -> stringResource(R.string.diag_status_review_required)
                                isCustomRuleYellow -> stringResource(R.string.diag_target_sheet_custom_rule_warning)
                                isYellow -> stringResource(R.string.diag_target_sheet_partial_warning)
                                else -> stringResource(R.string.diag_target_sheet_all_ok)
                            },
                            style = typography.bodySm,
                            color = colors.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Target Apps list
            Text(
                text = stringResource(R.string.diag_target_sheet_section_targets),
                style = typography.labelSm,
                fontWeight = FontWeight.Bold,
                color = colors.onSurfaceVariant
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                installedTargets.forEach { app ->
                    val appIcon = remember(app.packageName) {
                        WhatsAppPackageDetector.getAppIcon(context, app.packageName)
                    }
                    val isAppReady = app.isHooked && app.runtimeStatus == "READY" && app.bridgeStatus == "PROVIDER"
                    val itemColor = if (isAppReady) colors.success else colors.warning

                    Surface(
                        shape = radius.smShape,
                        color = colors.surfaceDim,
                        border = BorderStroke(1.dp, if (!isAppReady) itemColor.copy(alpha = 0.35f) else colors.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // App Icon
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(colors.surface),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (appIcon != null) {
                                        Image(
                                            bitmap = appIcon,
                                            contentDescription = app.appName,
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = WaexIcons.Security,
                                            contentDescription = app.appName,
                                            tint = itemColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = app.appName,
                                            style = typography.labelLg,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (isAppReady) {
                                            Surface(
                                                shape = CircleShape,
                                                color = itemColor.copy(alpha = 0.12f),
                                                border = BorderStroke(0.5.dp, itemColor.copy(alpha = 0.35f))
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.diag_target_sheet_injected_badge),
                                                    style = typography.labelSm,
                                                    color = itemColor,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 10.sp,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(1.dp))
                                    Text(
                                        text = "${app.packageName} • v${app.versionName}",
                                        style = typography.labelSm,
                                        color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                // Status Icon Indicator
                                Surface(
                                    shape = CircleShape,
                                    color = itemColor.copy(alpha = 0.12f),
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (isAppReady) WaexIcons.Check else WaexIcons.Help,
                                            contentDescription = null,
                                            tint = itemColor,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }
                            }

                            // Sub-details: Injection details, Hooks count, and why unconfirmed
                            Spacer(modifier = Modifier.height(6.dp))
                            HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant.copy(alpha = 0.35f))
                            Spacer(modifier = Modifier.height(6.dp))

                            val detailStatusText = when {
                                isAppReady && app.injectedHooks.isNotEmpty() -> {
                                    val activeCount = app.injectedHooks.count { it.status == "ACTIVE" }
                                    UiText.get(R.string.waex_l10n_detail_active,
                                        "Injected & active (%1\$d/%2\$d hooks active)", activeCount, app.injectedHooks.size)
                                }
                                isAppReady -> InternalLabels.text("Injected • Initialization reported complete")
                                !app.isHooked -> InternalLabels.text(app.injectionEvidence.label)
                                app.runtimeStatus != "READY" -> UiText.get(R.string.waex_l10n_detail_init,
                                    "Injected • Initialization: %1\$s", InternalLabels.text(TargetRuntimePresentation.initialization(app)))
                                else -> UiText.get(R.string.waex_l10n_detail_bridge,
                                    "Injected • Bridge: %1\$s", InternalLabels.text(TargetRuntimePresentation.bridge(app)))
                            }

                            Text(
                                text = detailStatusText,
                                style = typography.bodySm,
                                color = if (isAppReady) colors.onSurfaceVariant else itemColor,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Button(
                onClick = onDismiss,
                shape = radius.buttonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
            ) {
                Text(
                    text = stringResource(R.string.action_ok),
                    style = typography.labelLg,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HmaDetailsBottomSheet(
    report: HmaDiagnostics.HmaReport,
    visiblePackages: Set<String>,
    onFixTarget: (String) -> Unit,
    isFixing: Boolean = false,
    onDismiss: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val activeTargets = remember(report, visiblePackages) { report.targetDetails.filter { it.isBlocking && it.packageName in visiblePackages } }
    val cleanTargets = remember(report, visiblePackages) { report.targetDetails.filter { !it.isBlocking && it.packageName in visiblePackages } }

    var targetToConfirm by remember { mutableStateOf<HmaDiagnostics.TargetIsolationDetail?>(null) }

    WaexModalBottomSheet(
        onDismissRequest = { if (!isFixing) onDismiss() },
        sheetState = sheetState,
        containerColor = colors.surface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(colors.outlineVariant)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.diag_hma_sheet_title),
                    style = typography.titleLg,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.diag_hma_sheet_desc),
                    style = typography.bodySm,
                    color = colors.onSurfaceVariant
                )
            }

            // Inline Confirmation Card if a target was selected for fix
            if (targetToConfirm != null) {
                val current = targetToConfirm!!
                Surface(
                    shape = radius.mdShape,
                    color = colors.primary.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.diag_hma_fix_title),
                            style = typography.titleMd,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                        Text(
                            text = stringResource(R.string.diag_hma_fix_message, current.appName),
                            style = typography.bodySm,
                            color = colors.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { targetToConfirm = null },
                                enabled = !isFixing,
                                shape = radius.smShape,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                            ) {
                                Text(
                                    text = stringResource(android.R.string.cancel),
                                    style = typography.labelSm
                                )
                            }
                            Button(
                                onClick = {
                                    val pkg = current.packageName
                                    targetToConfirm = null
                                    onFixTarget(pkg)
                                },
                                enabled = !isFixing,
                                shape = radius.smShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.primary,
                                    contentColor = colors.onPrimary
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                            ) {
                                if (isFixing) {
                                    WaexCircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = colors.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.diag_hma_btn_fix),
                                        style = typography.labelSm,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (activeTargets.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.diag_hma_active_targets),
                    style = typography.labelSm,
                    fontWeight = FontWeight.Bold,
                    color = colors.error
                )
                activeTargets.forEach { item ->
                    Surface(
                        shape = radius.smShape,
                        color = colors.error.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, colors.error.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = colors.errorContainer,
                                modifier = Modifier.size(26.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = WaexIcons.Clear,
                                        contentDescription = null,
                                        tint = colors.onErrorContainer,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.appName,
                                    style = typography.bodyMd,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onSurface
                                )
                                Text(
                                    text = item.packageName,
                                    style = typography.labelSm,
                                    color = colors.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = InternalLabels.hmaReason(item.reason),
                                    style = typography.labelSm,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.error
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { targetToConfirm = item },
                                enabled = !isFixing,
                                shape = radius.smShape,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.error,
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.diag_hma_btn_fix),
                                    style = typography.labelSm,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            if (cleanTargets.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.diag_hma_inactive_targets),
                    style = typography.labelSm,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurfaceVariant
                )
                cleanTargets.forEach { item ->
                    Surface(
                        shape = radius.smShape,
                        color = colors.surfaceDim.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = colors.successContainer,
                                modifier = Modifier.size(26.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = WaexIcons.Check,
                                        contentDescription = null,
                                        tint = colors.onSuccessContainer,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.appName,
                                    style = typography.bodyMd,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onSurface
                                )
                                Text(
                                    text = item.packageName,
                                    style = typography.labelSm,
                                    color = colors.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = InternalLabels.hmaReason(item.reason),
                                    style = typography.labelSm,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = onDismiss,
                shape = radius.buttonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
            ) {
                Text(
                    text = stringResource(R.string.action_ok),
                    style = typography.bodyMd,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DenylistDetailsBottomSheet(
    report: MagiskDenylistDiagnostics.DenylistReport,
    visiblePackages: Set<String>,
    onFixTarget: (String) -> Unit,
    onFixAll: (List<String>) -> Unit,
    isFixing: Boolean = false,
    onDismiss: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val blockedTargets = remember(report, visiblePackages) { report.targets.filter { it.isBlocked && it.packageName in visiblePackages } }
    val cleanTargets = remember(report, visiblePackages) { report.targets.filter { !it.isBlocked && it.packageName in visiblePackages } }

    var targetToConfirm by remember { mutableStateOf<MagiskDenylistDiagnostics.DenylistedTarget?>(null) }
    var confirmFixAll by remember { mutableStateOf(false) }

    WaexModalBottomSheet(
        onDismissRequest = { if (!isFixing) onDismiss() },
        sheetState = sheetState,
        containerColor = colors.surface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(colors.outlineVariant)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.diag_denylist_sheet_title),
                        style = typography.titleLg,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.diag_denylist_sheet_desc),
                        style = typography.bodySm,
                        color = colors.onSurfaceVariant
                    )
                }
                if (blockedTargets.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { confirmFixAll = true },
                        enabled = !isFixing,
                        shape = radius.smShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.error,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.diag_denylist_btn_fix_all),
                            style = typography.labelSm,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Inline Confirmation Card for Fix All
            if (confirmFixAll) {
                Surface(
                    shape = radius.mdShape,
                    color = colors.error.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, colors.error.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.diag_denylist_fix_title),
                            style = typography.titleMd,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                        Text(
                            text = stringResource(R.string.diag_denylist_fix_all_message),
                            style = typography.bodySm,
                            color = colors.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { confirmFixAll = false },
                                enabled = !isFixing,
                                shape = radius.smShape,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                            ) {
                                Text(
                                    text = stringResource(android.R.string.cancel),
                                    style = typography.labelSm
                                )
                            }
                            Button(
                                onClick = {
                                    confirmFixAll = false
                                    onFixAll(blockedTargets.map { it.packageName })
                                },
                                enabled = !isFixing,
                                shape = radius.smShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.error,
                                    contentColor = Color.White
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                            ) {
                                if (isFixing) {
                                    WaexCircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.diag_denylist_btn_fix_all),
                                        style = typography.labelSm,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Inline Confirmation Card if a single target was selected for fix
            if (targetToConfirm != null) {
                val current = targetToConfirm!!
                Surface(
                    shape = radius.mdShape,
                    color = colors.primary.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.diag_denylist_fix_title),
                            style = typography.titleMd,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                        Text(
                            text = stringResource(R.string.diag_denylist_fix_message, current.appName),
                            style = typography.bodySm,
                            color = colors.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { targetToConfirm = null },
                                enabled = !isFixing,
                                shape = radius.smShape,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                            ) {
                                Text(
                                    text = stringResource(android.R.string.cancel),
                                    style = typography.labelSm
                                )
                            }
                            Button(
                                onClick = {
                                    val pkg = current.packageName
                                    targetToConfirm = null
                                    onFixTarget(pkg)
                                },
                                enabled = !isFixing,
                                shape = radius.smShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.primary,
                                    contentColor = colors.onPrimary
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                            ) {
                                if (isFixing) {
                                    WaexCircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = colors.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.diag_denylist_btn_fix),
                                        style = typography.labelSm,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (blockedTargets.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.diag_denylist_active_targets),
                    style = typography.labelSm,
                    fontWeight = FontWeight.Bold,
                    color = colors.error
                )
                blockedTargets.forEach { item ->
                    Surface(
                        shape = radius.smShape,
                        color = colors.error.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, colors.error.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = colors.errorContainer,
                                modifier = Modifier.size(26.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = WaexIcons.Clear,
                                        contentDescription = null,
                                        tint = colors.onErrorContainer,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.appName,
                                    style = typography.bodyMd,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onSurface
                                )
                                Text(
                                    text = item.packageName,
                                    style = typography.labelSm,
                                    color = colors.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.diag_denylist_target_blocked),
                                    style = typography.labelSm,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.error
                                )
                                if (!item.processDetails.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = item.processDetails,
                                        style = typography.labelSm,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.error
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { targetToConfirm = item },
                                enabled = !isFixing,
                                shape = radius.smShape,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.error,
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.diag_denylist_btn_fix),
                                    style = typography.labelSm,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            if (cleanTargets.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.diag_denylist_inactive_targets),
                    style = typography.labelSm,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurfaceVariant
                )
                cleanTargets.forEach { item ->
                    Surface(
                        shape = radius.smShape,
                        color = colors.surfaceDim.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = colors.successContainer,
                                modifier = Modifier.size(26.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = WaexIcons.Check,
                                        contentDescription = null,
                                        tint = colors.onSuccessContainer,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.appName,
                                    style = typography.bodyMd,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onSurface
                                )
                                Text(
                                    text = item.packageName,
                                    style = typography.labelSm,
                                    color = colors.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = when (item.state) {
                                        MagiskDenylistDiagnostics.TargetState.BLOCKED ->
                                            stringResource(R.string.diag_denylist_target_blocked)
                                        MagiskDenylistDiagnostics.TargetState.NOT_DENYLISTED ->
                                            stringResource(R.string.diag_denylist_target_clean)
                                        MagiskDenylistDiagnostics.TargetState.DENYLIST_NOT_ENFORCED ->
                                            stringResource(R.string.diag_denylist_target_not_enforced)
                                        MagiskDenylistDiagnostics.TargetState.MAGISK_NOT_ACTIVE ->
                                            stringResource(R.string.diag_denylist_target_magisk_inactive)
                                    },
                                    style = typography.labelSm,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = onDismiss,
                shape = radius.buttonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
            ) {
                Text(
                    text = stringResource(R.string.action_ok),
                    style = typography.bodyMd,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun DashboardDiagnosticsCard(
    isModuleActive: Boolean,
    diagnostics: SystemDiagnostics?,
    targetSummary: TargetRuntimePresentation.Summary,
    hmaReport: HmaDiagnostics.HmaReport?,
    denylistReport: MagiskDenylistDiagnostics.DenylistReport? = null,
    onRefresh: () -> Unit,
    onViewDetails: () -> Unit,
    onViewSupportedVersions: () -> Unit,
    onOpenHmaDetails: () -> Unit,
    onOpenDenylistDetails: () -> Unit = {},
    onOpenTargetCompatibility: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    Surface(
        shape = radius.cardShape,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val isDiagnosticsLoading = (hmaReport?.state == HmaDiagnostics.HmaState.CHECKING) ||
                    (denylistReport?.state == MagiskDenylistDiagnostics.DenylistState.CHECKING)

            // Determine overall health:
            // Error (Red): module not active, framework not detected when checked, targetSummary is explicitly false, or HMA / DenyList blocking
            // Warning (Yellow): unconfirmed state, target review needed, etc.
            // Healthy (Green): everything healthy and ok
            val isCriticalError = !isModuleActive ||
                    (hmaReport?.state == HmaDiagnostics.HmaState.BLOCKING_TARGETS) ||
                    (denylistReport?.state == MagiskDenylistDiagnostics.DenylistState.BLOCKING_TARGETS) ||
                    (targetSummary.healthy == false)

            val isWarning = !isCriticalError && (targetSummary.healthy == null || (diagnostics != null && diagnostics.framework.name == "Not detected"))

            val healthColor = when {
                isCriticalError -> colors.error
                isWarning -> colors.warning
                else -> colors.success
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isDiagnosticsLoading) {
                        WaexCircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.primary
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(healthColor)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.system_diagnostics_title),
                        style = typography.titleMd,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    Text(
                        text = stringResource(R.string.live_health_overview),
                        style = typography.bodySm,
                        color = colors.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = WaexIcons.Refresh,
                        contentDescription = stringResource(R.string.system_diagnostics_title),
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            DashboardDiagnosticRow(
                label = stringResource(R.string.diag_label_module),
                value = if (isModuleActive) stringResource(R.string.diag_status_active) else stringResource(R.string.diag_status_inactive),
                healthy = isModuleActive,
                isLoading = false
            )
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.65f))
            val frameworkName = diagnostics?.framework?.name
                ?: if (isModuleActive) {
                    PreferenceStores.publicStore(LocalContext.current)
                        .getString("active_xposed_framework", null)
                        ?.takeIf { it.isNotBlank() } ?: "Xposed-compatible framework"
                } else {
                    stringResource(R.string.diag_framework_detecting)
                }
            DashboardDiagnosticRow(
                label = stringResource(R.string.diag_label_framework),
                value = InternalLabels.text(frameworkName),
                healthy = if (isModuleActive) true else diagnostics?.let { it.moduleActive && it.framework.name != "Not detected" },
                isLoading = !isModuleActive && diagnostics == null
            )
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.65f))
            DashboardDiagnosticRow(
                label = stringResource(R.string.diag_label_compatibility),
                value = InternalLabels.text(targetSummary.text),
                healthy = targetSummary.healthy,
                isLoading = false,
                onClick = onOpenTargetCompatibility
            )
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.65f))
            DashboardDiagnosticRow(
                label = stringResource(R.string.diag_label_hma),
                value = when (hmaReport?.state) {
                    HmaDiagnostics.HmaState.BLOCKING_TARGETS -> stringResource(R.string.diag_hma_blocked)
                    HmaDiagnostics.HmaState.CLEAN -> stringResource(R.string.diag_hma_clean)
                    HmaDiagnostics.HmaState.NOT_ACTIVE, HmaDiagnostics.HmaState.NOT_CONFIGURED -> stringResource(R.string.diag_hma_not_active)
                    HmaDiagnostics.HmaState.CHECKING, null -> stringResource(R.string.diag_hma_checking)
                },
                healthy = when (hmaReport?.state) {
                    HmaDiagnostics.HmaState.BLOCKING_TARGETS -> false
                    HmaDiagnostics.HmaState.CLEAN, HmaDiagnostics.HmaState.NOT_ACTIVE, HmaDiagnostics.HmaState.NOT_CONFIGURED -> true
                    HmaDiagnostics.HmaState.CHECKING, null -> null
                },
                isLoading = hmaReport == null || hmaReport.state == HmaDiagnostics.HmaState.CHECKING,
                onClick = if (hmaReport?.targetDetails?.isNotEmpty() == true) onOpenHmaDetails else null
            )
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.65f))
            DashboardDiagnosticRow(
                label = stringResource(R.string.diag_label_denylist),
                value = when (denylistReport?.state) {
                    MagiskDenylistDiagnostics.DenylistState.BLOCKING_TARGETS -> stringResource(R.string.diag_denylist_blocked)
                    MagiskDenylistDiagnostics.DenylistState.CLEAN -> stringResource(R.string.diag_denylist_clean)
                    MagiskDenylistDiagnostics.DenylistState.NOT_ACTIVE -> stringResource(R.string.diag_denylist_not_active)
                    MagiskDenylistDiagnostics.DenylistState.CHECKING, null -> stringResource(R.string.diag_denylist_checking)
                },
                healthy = when (denylistReport?.state) {
                    MagiskDenylistDiagnostics.DenylistState.BLOCKING_TARGETS -> false
                    MagiskDenylistDiagnostics.DenylistState.CLEAN, MagiskDenylistDiagnostics.DenylistState.NOT_ACTIVE -> true
                    MagiskDenylistDiagnostics.DenylistState.CHECKING, null -> null
                },
                isLoading = denylistReport == null || denylistReport.state == MagiskDenylistDiagnostics.DenylistState.CHECKING,
                onClick = if (denylistReport?.targets?.isNotEmpty() == true) onOpenDenylistDetails else null
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DiagnosticMetric(
                    label = stringResource(R.string.diag_label_android),
                    value = diagnostics?.let { UiText.get(R.string.waex_ui_api_1_s, "API %1\$s", it.sdk) } ?: "—",
                    modifier = Modifier.weight(1f)
                )
                DiagnosticMetric(
                    label = stringResource(R.string.diag_label_selinux),
                    value = diagnostics?.selinux?.let { InternalLabels.text(it) } ?: "—",
                    modifier = Modifier.weight(1f)
                )
                DiagnosticMetric(
                    label = stringResource(R.string.diag_label_root),
                    value = diagnostics?.let { if (it.rootAllowed) stringResource(R.string.diag_root_allowed) else stringResource(R.string.diag_root_not_allowed) } ?: "—",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.diag_disclaimer),
                style = typography.labelSm,
                color = colors.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onViewDetails,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                shape = radius.buttonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary
                )
            ) {
                Text(
                    text = stringResource(R.string.view_diagnostic_details),
                    style = typography.bodyMd,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = WaexIcons.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(radius.defaultShape)
                    .clickable(onClick = onViewSupportedVersions)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.supported_versions_catalog),
                    style = typography.bodyMd,
                    color = colors.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = WaexIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardDiagnosticRow(
    label: String,
    value: String,
    healthy: Boolean?,
    isLoading: Boolean = false,
    showValueText: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val scope = rememberCoroutineScope()
    val tooltipState = rememberTooltipState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(radius.smShape)
            .then(
                if (onClick != null) {
                    Modifier
                        .clickable(onClick = onClick)
                        .padding(vertical = 10.dp, horizontal = 4.dp)
                } else {
                    Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
                }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = typography.bodyMd,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))

        if (showValueText && !isLoading) {
            Text(
                text = value,
                style = typography.bodyMd,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (healthy != true) {
                Spacer(modifier = Modifier.width(8.dp))
            }
        }

        if (isLoading) {
            WaexCircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = colors.primary
            )
        } else if (!showValueText || healthy != true) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = {
                    PlainTooltip(
                        containerColor = colors.surfaceContainerHighest,
                        contentColor = colors.onSurface
                    ) {
                        Text(
                            text = value,
                            style = typography.bodySm,
                            fontWeight = FontWeight.Medium
                        )
                    }
                },
                state = tooltipState
            ) {
                Surface(
                    shape = CircleShape,
                    color = when (healthy) {
                        true -> colors.successContainer
                        false -> colors.errorContainer
                        null -> colors.warningContainer
                    },
                    modifier = Modifier
                        .size(24.dp)
                        .clickable {
                            if (onClick != null) {
                                onClick()
                            } else {
                                scope.launch { tooltipState.show() }
                            }
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        when (healthy) {
                            true -> {
                                Icon(
                                    imageVector = WaexIcons.Check,
                                    contentDescription = value,
                                    tint = colors.onSuccessContainer,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            false -> {
                                Icon(
                                    imageVector = WaexIcons.Clear,
                                    contentDescription = value,
                                    tint = colors.onErrorContainer,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            null -> {
                                Icon(
                                    imageVector = WaexIcons.Help,
                                    contentDescription = value,
                                    tint = colors.onWarningContainer,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (onClick != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = WaexIcons.ChevronRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun DiagnosticMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    Surface(
        modifier = modifier,
        shape = radius.smShape,
        color = colors.surfaceDim,
        border = BorderStroke(1.dp, colors.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = typography.labelSm,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = label,
                style = typography.labelSm,
                color = colors.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
fun InjectedHookRow(hook: InjectedHookItem) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    val status = hook.status.uppercase()
    val dotColor = when (status) {
        "ACTIVE" -> colors.primary
        "FAILED" -> colors.error
        "WAITING", "PENDING" -> colors.secondary
        else -> colors.onSurfaceVariant
    }

    Surface(
        shape = radius.defaultShape,
        color = colors.surfaceDim,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = InternalLabels.text(hook.name),
                        style = typography.bodyMd,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Box(
                        modifier = Modifier
                            .clip(radius.smShape)
                            .background(colors.surface)
                            .border(0.5.dp, colors.outlineVariant, radius.smShape)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = InternalLabels.text(hook.category),
                            style = typography.labelSm,
                            fontSize = 10.sp,
                            color = colors.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = hook.className,
                    style = typography.labelSm,
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (!hook.error.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = UiText.get(R.string.waex_ui_error_1_s, "Error: %1\$s", hook.error),
                        style = typography.labelSm,
                        fontSize = 10.sp,
                        color = colors.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = when (status) {
                    "ACTIVE" -> UiText.get(R.string.diag_status_active, "Active")
                    "FAILED" -> UiText.get(R.string.waex_ui_failed_label, "Failed")
                    else -> InternalLabels.text(status)
                },
                style = typography.labelSm,
                fontWeight = FontWeight.Medium,
                color = dotColor,
                fontSize = 11.sp
            )
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
        border = BorderStroke(1.dp, colors.outline),
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
