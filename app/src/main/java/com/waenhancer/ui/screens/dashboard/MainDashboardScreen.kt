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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
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
import com.waenhancer.utils.HookedAppInfo
import com.waenhancer.utils.InjectedHookItem
import com.waenhancer.utils.SystemDiagnostics
import com.waenhancer.utils.SystemDiagnosticsReader
import com.waenhancer.utils.UniversalVersionValidator
import com.waenhancer.utils.WhatsAppPackageDetector
import com.waenhancer.utils.XposedRuntimeInspector
import com.waenhancer.utils.TargetRuntimePresentation
import com.waenhancer.ui.components.rememberRuntimeObservationClock
import com.waenhancer.xposed.utils.AppRestartHelper
import com.waenhancer.xposed.utils.ModuleStatus
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
    onOpenModal: (String) -> Unit
) {
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val preferenceManager = LocalWaexPreferenceManager.current
    val isModuleActive = remember { ModuleStatus.isModuleActive() }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            runCatching { preferenceManager.exportSettings(uri) }
                .onSuccess { Toast.makeText(context, UiText.get(R.string.waex_ui_1_s_settings_backed_up_1a3f7819, "%1\$s settings backed up", it), Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, UiText.get(R.string.waex_ui_backup_failed_1_s_5a9e913e, "Backup failed: %1\$s", it.message), Toast.LENGTH_LONG).show() }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { preferenceManager.importSettings(uri) }
                .onSuccess { Toast.makeText(context, UiText.get(R.string.waex_ui_1_s_settings_restored_restart_whatsapp_09d38d41, "%1\$s settings restored. Restart WhatsApp to apply.", it), Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(context, UiText.get(R.string.waex_ui_restore_failed_1_s_995c8df5, "Restore failed: %1\$s", it.message), Toast.LENGTH_LONG).show() }
        }
    }
    val startBackup = {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        runCatching { backupLauncher.launch("WAEX-settings-$stamp.waex") }
            .onFailure { Toast.makeText(context, UiText.get(R.string.waex_ui_could_not_open_file_picker_1_s_5bc514b4, "Could not open file picker: %1\$s", it.message), Toast.LENGTH_LONG).show() }
        Unit
    }
    val startRestore = {
        runCatching { restoreLauncher.launch(arrayOf("application/octet-stream", "*/*")) }
            .onFailure { Toast.makeText(context, UiText.get(R.string.waex_ui_could_not_open_file_picker_1_s_5bc514b4, "Could not open file picker: %1\$s", it.message), Toast.LENGTH_LONG).show() }
        Unit
    }

    var refreshTrigger by remember { mutableIntStateOf(0) }
    var diagnosticsRefreshTrigger by remember { mutableIntStateOf(0) }
    var runtimeSnapshot by remember {
        mutableStateOf(XposedRuntimeInspector.cached())
    }
    var systemDiagnostics by remember {
        mutableStateOf(SystemDiagnosticsReader.cached())
    }
    var hmaRefreshTrigger by remember { mutableIntStateOf(0) }
    var hmaStatus by remember {
        mutableStateOf<HmaDiagnostics.HmaReport?>(HmaDiagnostics.cached())
    }

    // Target heartbeats arrive every ten seconds. They refresh target evidence, while the
    // root/framework/package inventory only needs a screen resume or an explicit refresh.
    LaunchedEffect(diagnosticsRefreshTrigger) {
        systemDiagnostics = withContext(Dispatchers.IO) { SystemDiagnosticsReader.read(context) }
    }

    LaunchedEffect(refreshTrigger) {
        val packages = withContext(Dispatchers.IO) {
            WhatsAppPackageDetector.detectWhatsAppApps(context, runtimeSnapshot)
                .filter { it.isInstalled }.map { it.packageName }
        }
        val refreshed = withContext(Dispatchers.IO) {
            XposedRuntimeInspector.inspect(context, packages)
        }
        runtimeSnapshot = refreshed
    }

    LaunchedEffect(hmaRefreshTrigger) {
        val targets = withContext(Dispatchers.IO) {
            WhatsAppPackageDetector.detectWhatsAppApps(context, runtimeSnapshot)
                .filter { it.isInstalled }.map { it.packageName }
        }
        val report = HmaDiagnostics.checkHmaStatus(
            context = context,
            targetPackages = targets,
            forceRefresh = hmaRefreshTrigger > 0
        )
        hmaStatus = report
    }

    // Re-check target apps on resume and whenever target app sends active broadcast
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTrigger++
                diagnosticsRefreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                refreshTrigger++
            }
        }
        val filter = IntentFilter("com.waenhancer.ACTION_TARGET_APP_ACTIVE")
        if (Build.VERSION.SDK_INT >= 33) {
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
    val reportedApps = remember(refreshTrigger, runtimeSnapshot) {
        WhatsAppPackageDetector.detectWhatsAppApps(context, runtimeSnapshot)
    }
    val observationTime = rememberRuntimeObservationClock()
    val detectedApps = reportedApps.map {
        TargetRuntimePresentation.asOf(it, observationTime.wall, observationTime.elapsed)
    }
    val prefs = remember(context) { PreferenceStores.publicStore(context) }
    val versionDecisions = remember(reportedApps, refreshTrigger) {
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
    var showTargetCompatibilitySheet by remember { mutableStateOf(false) }
    var isFixingHma by remember { mutableStateOf(false) }

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
                border = BorderStroke(
                    1.dp,
                    if (hasTargetReview) Color(0xFFEF4444).copy(alpha = 0.5f) else colors.outline
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
                                        hasTargetReview -> Color(0xFFEF4444)
                                        else -> colors.primary
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = when {
                                !isModuleActive -> stringResource(R.string.module_inactive_version, moduleVersion)
                                hasTargetReview -> stringResource(R.string.module_active_version, "$moduleVersion • ${stringResource(R.string.diag_status_review_required)}")
                                else -> stringResource(R.string.module_active_version, moduleVersion)
                            },
                            style = typography.bodyMd,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = if (hasTargetReview) Color(0xFFEF4444) else colors.onSurface,
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
                                text = stringResource(R.string.title_changelog),
                                style = typography.labelSm,
                                color = colors.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)
                    Spacer(modifier = Modifier.height(14.dp))

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
                            val statusText = com.waenhancer.utils.InternalLabels.text(status.text)
                            val statusTextColor = statusDotColor

                            Surface(
                                shape = radius.defaultShape,
                                color = colors.surfaceDim,
                                border = BorderStroke(
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
                                                    contentDescription = UiText.get(R.string.waex_ui_restart_app_07fe185c, "Restart App"),
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
                                                    fontSize = 11.sp
                                                )
                                            }
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.view_all),
                                                    style = typography.labelSm,
                                                    color = colors.primary,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium
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
                diagnostics = systemDiagnostics,
                targetSummary = targetSummary,
                hmaReport = hmaStatus,
                onRefresh = {
                    refreshTrigger++
                    diagnosticsRefreshTrigger++
                    hmaRefreshTrigger++
                },
                onViewDetails = { navController.navigateTo(Screen.SystemHealth) },
                onViewSupportedVersions = { navController.navigateTo(Screen.SupportedVersions) },
                onOpenHmaDetails = { showHmaDetailSheet = true },
                onOpenTargetCompatibility = { showTargetCompatibilitySheet = true }
            )

            // Quick Backup & Maintenance Row
            Surface(
                shape = radius.cardShape,
                color = colors.surface,
                border = BorderStroke(1.dp, colors.outline),
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
                                .height(42.dp),
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
                                .height(42.dp),
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
                            .height(42.dp),
                        shape = radius.buttonShape,
                        border = BorderStroke(1.dp, colors.error.copy(alpha = 0.55f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = colors.errorContainer.copy(alpha = 0.2f),
                            contentColor = colors.error
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = WaexIcons.Clear,
                                contentDescription = null,
                                tint = colors.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.reset_settings_btn),
                                style = typography.bodyMd,
                                fontWeight = FontWeight.Medium,
                                color = colors.error
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
                    Toast.makeText(context, UiText.get(R.string.waex_ui_all_settings_reset_to_default_75452587, "All settings reset to default"), Toast.LENGTH_SHORT).show()
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

    ModalBottomSheet(
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
                isRed -> Color(0xFFEF4444)
                isYellow -> Color(0xFFF59E0B)
                else -> Color(0xFF10B981)
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
                            text = com.waenhancer.utils.InternalLabels.text(targetSummary.text),
                            style = typography.labelLg,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                        Text(
                            text = when {
                                isYellow -> stringResource(R.string.diag_target_sheet_partial_warning)
                                isRed -> stringResource(R.string.diag_status_review_required)
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
                    val itemColor = if (isAppReady) Color(0xFF10B981) else Color(0xFFF59E0B)

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
                                isAppReady -> com.waenhancer.utils.InternalLabels.text("Injected • Initialization reported complete")
                                !app.isHooked -> com.waenhancer.utils.InternalLabels.text(app.injectionEvidence.label)
                                app.runtimeStatus != "READY" -> UiText.get(R.string.waex_l10n_detail_init,
                                    "Injected • Initialization: %1\$s", com.waenhancer.utils.InternalLabels.text(TargetRuntimePresentation.initialization(app)))
                                else -> UiText.get(R.string.waex_l10n_detail_bridge,
                                    "Injected • Bridge: %1\$s", com.waenhancer.utils.InternalLabels.text(TargetRuntimePresentation.bridge(app)))
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
    onFixTarget: (String) -> Unit,
    isFixing: Boolean = false,
    onDismiss: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val activeTargets = remember(report) { report.targetDetails.filter { it.isBlocking } }
    val cleanTargets = remember(report) { report.targetDetails.filter { !it.isBlocking } }

    var targetToConfirm by remember { mutableStateOf<HmaDiagnostics.TargetIsolationDetail?>(null) }

    ModalBottomSheet(
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
                                    CircularProgressIndicator(
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
                                color = Color(0xFFEF4444).copy(alpha = 0.15f),
                                modifier = Modifier.size(26.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = WaexIcons.Clear,
                                        contentDescription = null,
                                        tint = Color(0xFFEF4444),
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
                                color = Color(0xFF10B981).copy(alpha = 0.15f),
                                modifier = Modifier.size(26.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = WaexIcons.Check,
                                        contentDescription = null,
                                        tint = Color(0xFF10B981),
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

@Composable
private fun DashboardDiagnosticsCard(
    diagnostics: SystemDiagnostics?,
    targetSummary: TargetRuntimePresentation.Summary,
    hmaReport: HmaDiagnostics.HmaReport?,
    onRefresh: () -> Unit,
    onViewDetails: () -> Unit,
    onViewSupportedVersions: () -> Unit,
    onOpenHmaDetails: () -> Unit,
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
            val isDiagnosticsLoading = diagnostics == null || hmaReport == null || hmaReport.state == HmaDiagnostics.HmaState.CHECKING

            // Determine overall health:
            // Error (Red): module not active, framework not detected when checked, targetSummary is explicitly false, or HMA blocking
            // Warning (Yellow): unconfirmed state, target review needed, etc.
            // Healthy (Green): everything healthy and ok
            val isCriticalError = (diagnostics != null && !diagnostics.moduleActive) ||
                    (hmaReport != null && hmaReport.state == HmaDiagnostics.HmaState.BLOCKING_TARGETS) ||
                    (targetSummary.healthy == false)

            val isWarning = !isCriticalError && (targetSummary.healthy == null || (diagnostics != null && diagnostics.framework.name == "Not detected"))

            val healthColor = when {
                isCriticalError -> Color(0xFFEF4444)
                isWarning -> Color(0xFFF59E0B)
                else -> Color(0xFF10B981)
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
                        CircularProgressIndicator(
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
                value = when (diagnostics?.moduleActive) {
                    true -> stringResource(R.string.diag_status_active)
                    false -> stringResource(R.string.diag_status_inactive)
                    null -> stringResource(R.string.diag_status_checking)
                },
                healthy = diagnostics?.moduleActive,
                isLoading = diagnostics == null
            )
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.65f))
            DashboardDiagnosticRow(
                label = stringResource(R.string.diag_label_framework),
                value = diagnostics?.framework?.name?.let { com.waenhancer.utils.InternalLabels.text(it) } ?: stringResource(R.string.diag_framework_detecting),
                healthy = diagnostics?.let { it.moduleActive && it.framework.name != "Not detected" }
            )
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.65f))
            DashboardDiagnosticRow(
                label = stringResource(R.string.diag_label_compatibility),
                value = com.waenhancer.utils.InternalLabels.text(targetSummary.text),
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

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DiagnosticMetric(
                    label = stringResource(R.string.diag_label_android),
                    value = diagnostics?.let { UiText.get(R.string.waex_ui_api_1_s_7ed74ebc, "API %1\$s", it.sdk) } ?: "—",
                    modifier = Modifier.weight(1f)
                )
                DiagnosticMetric(
                    label = stringResource(R.string.diag_label_selinux),
                    value = diagnostics?.selinux?.let { com.waenhancer.utils.InternalLabels.text(it) } ?: "—",
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
            CircularProgressIndicator(
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
                        true -> Color(0xFF10B981).copy(alpha = 0.15f)
                        false -> Color(0xFFEF4444).copy(alpha = 0.15f)
                        null -> Color(0xFFF59E0B).copy(alpha = 0.15f)
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
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            false -> {
                                Icon(
                                    imageVector = WaexIcons.Clear,
                                    contentDescription = value,
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            null -> {
                                Icon(
                                    imageVector = WaexIcons.Help,
                                    contentDescription = value,
                                    tint = Color(0xFFF59E0B),
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

    val isSuccess = (hook.status == "ACTIVE")
    val dotColor = if (isSuccess) colors.primary else colors.error

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
                        text = com.waenhancer.utils.InternalLabels.text(hook.name),
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
                            text = com.waenhancer.utils.InternalLabels.text(hook.category),
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
                        text = UiText.get(R.string.waex_ui_error_1_s_81a2d6c7, "Error: %1\$s", hook.error),
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
                text = if (isSuccess) UiText.get(R.string.diag_status_active, "Active") else UiText.get(R.string.waex_ui_failed_031a8f0f, "Failed"),
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
