package com.waenhancer.ui.screens.settings

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.components.StitchSwitch
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import com.waenhancer.utils.SystemDiagnostics
import com.waenhancer.utils.SystemDiagnosticsReader
import com.waenhancer.utils.WhatsAppPackageDetector
import com.waenhancer.utils.XposedRuntimeInspector
import com.waenhancer.utils.TargetRuntimePresentation
import com.waenhancer.ui.components.rememberRuntimeObservationClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.BorderStroke

@Composable
fun SystemHealthScreen() {
    val nav = LocalWaexNavController.current
    val preferences = LocalWaexPreferenceManager.current
    val context = LocalContext.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    var diagnostics by remember {
        mutableStateOf(SystemDiagnosticsReader.cached())
    }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var runtimeSnapshot by remember {
        mutableStateOf(XposedRuntimeInspector.cached())
    }
    var showHookToast by remember {
        mutableStateOf(preferences.getBoolean("show_hook_toast", true))
    }
    var showRestartSheet by remember {
        mutableStateOf(preferences.getBoolean("show_restart_sheet", true))
    }
    LaunchedEffect(refreshTrigger) {
        diagnostics = withContext(Dispatchers.IO) { SystemDiagnosticsReader.read(context) }
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
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTrigger++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            WaexTopBar(
                UiText.get(R.string.waex_ui_system_diagnostics_005acd73, "System Diagnostics"),
                onBackClick = { nav.popBack() },
                actions = {
                    IconButton(onClick = { refreshTrigger++ }) {
                        Icon(
                            imageVector = WaexIcons.Refresh,
                            contentDescription = UiText.get(R.string.waex_ui_refresh_diagnostics_7bce98ec, "Refresh diagnostics"),
                            tint = colors.onSurface
                        )
                    }
                }
            )
        },
        containerColor = colors.background
    ) { insets ->
        val data = diagnostics
        if (data == null) {
            Column(Modifier.fillMaxSize().padding(insets), Arrangement.Center, Alignment.CenterHorizontally) { CircularProgressIndicator(color = colors.primary) }
        } else {
            val reportedApps = remember(data, refreshTrigger, runtimeSnapshot) {
                WhatsAppPackageDetector.detectWhatsAppApps(context, runtimeSnapshot)
                    .filter { it.isInstalled }
            }
            val observationTime = rememberRuntimeObservationClock()
            val targetApps = reportedApps.map {
                TargetRuntimePresentation.asOf(it, observationTime.wall, observationTime.elapsed)
            }
            Column(
                Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(horizontal = spacing.pageMargin, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DiagnosticCard(UiText.get(R.string.waex_ui_module_framework_b4c7101d, "Module & Framework"), listOf(
                    UiText.get(R.string.waex_ui_module_status_78cf7255, "Module status") to if (data.moduleActive) UiText.get(R.string.diag_status_active, "Active") else UiText.get(R.string.diag_status_inactive, "Inactive"),
                    UiText.get(R.string.waex_ui_module_version_d923cdd4, "Module version") to "${data.moduleVersion} (${data.moduleVersionCode})",
                    UiText.get(R.string.waex_ui_xposed_framework_171b0ce2, "Xposed framework") to com.waenhancer.utils.InternalLabels.text(data.framework.name),
                    UiText.get(R.string.waex_ui_framework_package_2cd0a851, "Framework package") to (data.framework.packageName ?: UiText.get(R.string.waex_ui_not_available_67a926f7, "Not available")),
                    UiText.get(R.string.waex_ui_framework_version_b4522d19, "Framework version") to (data.framework.version ?: UiText.get(R.string.waex_ui_not_available_67a926f7, "Not available")),
                    UiText.get(R.string.waex_ui_xposed_api_09eb1963, "Xposed API") to (data.framework.api?.toString() ?: UiText.get(R.string.waex_ui_not_reported_yet_355b4779, "Not reported yet")),
                    UiText.get(R.string.waex_ui_root_allowed_fb11f039, "Root allowed") to if (data.rootAllowed) UiText.get(R.string.waex_ui_yes_85a39ab3, "Yes") else UiText.get(R.string.waex_ui_no_1ea442a1, "No")
                ), if (data.moduleActive) Color(0xFF10B981) else colors.error)

                DiagnosticCard(UiText.get(R.string.waex_ui_device_environment_663d37f3, "Device Environment"), listOf(
                    "Android" to "${data.androidVersion} (API ${data.sdk})",
                    UiText.get(R.string.waex_ui_device_6ba0bdec, "Device") to data.device,
                    UiText.get(R.string.waex_ui_supported_abis_05063fc8, "Supported ABIs") to data.abis,
                    UiText.get(R.string.waex_ui_kernel_8a716159, "Kernel") to data.kernel,
                    "SELinux" to com.waenhancer.utils.InternalLabels.text(data.selinux)
                ), colors.primary)

                DiagnosticCard(
                    UiText.get(R.string.waex_ui_hook_runtime_3e54a2b2, "Hook Runtime"),
                    buildList {
                        add(UiText.get(R.string.waex_ui_reported_hooked_targets_0f7b326d, "Reported hooked targets") to com.waenhancer.utils.InternalLabels.text(data.hookedTargets))
                        add(UiText.get(R.string.waex_ui_manager_process_hooked_a78a75a7, "Manager process hooked") to if (data.moduleActive) UiText.get(R.string.waex_ui_yes_85a39ab3, "Yes") else UiText.get(R.string.waex_ui_no_1ea442a1, "No"))
                        add(UiText.get(R.string.waex_ui_runtime_api_captured_2625bdc2, "Runtime API captured") to if (data.framework.api != null) UiText.get(R.string.waex_ui_yes_85a39ab3, "Yes") else UiText.get(R.string.waex_ui_no_1ea442a1, "No"))
                        targetApps.forEach { app ->
                            add(UiText.get(R.string.waex_ui_1_s_hooks_f0333300, "%1\$s hooks", app.appName) to com.waenhancer.utils.InternalLabels.text(app.injectionEvidence.label))
                            add(UiText.get(R.string.waex_ui_1_s_preference_bridge_c27008b2, "%1\$s preference bridge", app.appName) to com.waenhancer.utils.InternalLabels.text(TargetRuntimePresentation.bridge(app)))
                            add(UiText.get(R.string.waex_ui_1_s_initialization_a35e3f64, "%1\$s initialization", app.appName) to com.waenhancer.utils.InternalLabels.text(TargetRuntimePresentation.initialization(app)))
                            app.initializationError?.takeIf { it.isNotBlank() }?.let {
                                add(UiText.get(R.string.waex_ui_1_s_last_error_2f54987d, "%1\$s last error", app.appName) to it)
                            }
                        }
                    },
                    colors.primary
                )

                Surface(
                    shape = radius.bentoCardShape,
                    color = colors.surfaceContainerLow,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                UiText.get(R.string.waex_ui_show_hook_injection_toast_dc03f223, "Show hook injection toast"),
                                style = typography.bodyLg,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface
                            )
                            Text(
                                UiText.get(R.string.waex_ui_show_the_injecting_hooks_notification_3b01d0c9, "Show the injecting hooks notification when WhatsApp opens"),
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant
                            )
                        }
                        StitchSwitch(
                            checked = showHookToast,
                            onCheckedChange = { checked ->
                                showHookToast = checked
                                preferences.putBoolean("show_hook_toast", checked)
                            }
                        )
                    }
                }
                Surface(
                    shape = radius.bentoCardShape,
                    color = colors.surfaceContainerLow,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                UiText.get(R.string.waex_ui_show_restart_sheet_after_changes_7fd4dac3, "Show restart sheet after changes"),
                                style = typography.bodyLg,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface
                            )
                            Text(
                                UiText.get(R.string.waex_ui_preferences_refresh_immediately_restar_e3de7bee, "Preferences refresh immediately; restarting WhatsApp re-injects hooks that only load at startup"),
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant
                            )
                        }
                        StitchSwitch(
                            checked = showRestartSheet,
                            onCheckedChange = { checked ->
                                showRestartSheet = checked
                                preferences.putBoolean("show_restart_sheet", checked)
                            }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun DiagnosticCard(title: String, entries: List<Pair<String, String>>, accent: Color) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    Surface(shape = radius.bentoCardShape, color = colors.surfaceContainerLow, border = BorderStroke(1.dp, colors.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = typography.bodyLg, fontWeight = FontWeight.Bold, color = accent)
            Spacer(Modifier.height(12.dp))
            entries.forEachIndexed { index, (label, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(label, style = typography.bodyMd, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(value, style = typography.bodyMd, fontWeight = FontWeight.SemiBold, color = colors.onSurface, modifier = Modifier.weight(1.3f))
                }
                if (index < entries.lastIndex) HorizontalDivider(color = colors.outlineVariant)
            }
        }
    }
}
