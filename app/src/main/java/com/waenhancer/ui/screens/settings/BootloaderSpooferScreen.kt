package com.waenhancer.ui.screens.settings

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.waenhancer.licensing.features.BootloaderSpooferFeature
import com.waenhancer.ui.components.StitchSwitch
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.components.WaexModalBottomSheet
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.components.WaexCircularProgressIndicator
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.shape.CircleShape
import com.waenhancer.ui.designsystem.WaexIcons
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun BootloaderSpooferScreen() {
    val context = LocalContext.current
    val nav = LocalWaexNavController.current
    val prefs = LocalWaexPreferenceManager.current
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(prefs.getBoolean(BootloaderSpooferFeature.ENABLED, true)) }
    var custom by remember { mutableStateOf(prefs.getBoolean(BootloaderSpooferFeature.CUSTOM_ENABLED, false)) }
    var customReady by remember { mutableStateOf(prefs.getString(BootloaderSpooferFeature.CUSTOM_XML, "").isNotBlank()) }
    var updated by remember { mutableStateOf(prefs.getString(BootloaderSpooferFeature.LAST_UPDATED, "")) }
    var syncError by remember { mutableStateOf(prefs.getString(BootloaderSpooferFeature.LAST_ERROR, "")) }
    var syncing by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<BootloaderSpooferFeature.VerificationReport?>(null) }
    var showReport by remember { mutableStateOf(false) }
    // Cache: track which source the report was generated for and when.
    // Key: "custom" or "managed"; value: epoch-millis of last successful verify.
    var reportSource by remember { mutableStateOf("") }
    var reportCachedAt by remember { mutableStateOf(0L) }
    val cacheValidMs = 60 * 60 * 1_000L // 1 hour

    fun isReportCacheValid(forCustom: Boolean): Boolean {
        val expectedSource = if (forCustom) "custom" else "managed"
        return reportSource == expectedSource &&
                System.currentTimeMillis() - reportCachedAt < cacheValidMs &&
                report != null
    }

    fun runVerify(forCustom: Boolean, autoOpen: Boolean = true) {
        if (isReportCacheValid(forCustom)) {
            if (autoOpen) showReport = true
            return
        }
        verifying = true
        if (autoOpen) showReport = true
        scope.launch {
            val selectedXml = withContext(Dispatchers.IO) {
                prefs.getString(
                    if (forCustom) BootloaderSpooferFeature.CUSTOM_XML
                    else BootloaderSpooferFeature.DEFAULT_XML, ""
                )
            }
            report = withContext(Dispatchers.Default) {
                BootloaderSpooferFeature.verify(context, selectedXml, !forCustom)
            }
            reportSource = if (forCustom) "custom" else "managed"
            reportCachedAt = System.currentTimeMillis()
            verifying = false
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val xml = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error(UiText.get(R.string.waex_ui_could_not_read_the_selected_file, "Could not read the selected file"))
                    require(xml.length <= 2_000_000) { UiText.get(R.string.waex_ui_keybox_file_is_too_large, "Keybox file is too large") }
                    BootloaderSpooferFeature.parse(xml)
                    prefs.putString(BootloaderSpooferFeature.CUSTOM_XML, xml)
                }
            }.onSuccess {
                customReady = true
                custom = true
                prefs.putBoolean(BootloaderSpooferFeature.CUSTOM_ENABLED, true)
                // Invalidate cache so next verify runs fresh for the new custom keybox.
                reportSource = ""
                Toast.makeText(context, UiText.get(R.string.waex_ui_custom_keybox_imported, "Custom keybox imported"), Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(context, UiText.get(R.string.waex_ui_invalid_keybox_1_s, "Invalid keybox: %1\$s", it.message), Toast.LENGTH_LONG).show() }
        }
    }

    Scaffold(topBar = { WaexTopBar(UiText.get(R.string.feat_bootloader_spoofer_title, "Bootloader Spoofer"), onBackClick = { nav.popBack() }) },
        containerColor = colors.background) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
            .padding(horizontal = WaexTheme.spacing.pageMargin, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val managedReady = updated.isNotBlank()
            val statusColor = when {
                syncing -> colors.primary
                custom && customReady -> colors.success
                !custom && managedReady -> colors.success
                syncError.isNotBlank() -> colors.error
                else -> colors.warning
            }
            Surface(shape = radius.bentoCardShape, color = statusColor.copy(alpha = 0.09f),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f)), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (syncing) WaexCircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = statusColor)
                    else Surface(Modifier.size(12.dp), shape = CircleShape, color = statusColor) {}
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(when {
                            syncing -> UiText.get(R.string.waex_ui_refreshing_managed_keybox, "Refreshing managed keybox")
                            custom && customReady -> UiText.get(R.string.waex_ui_custom_keybox_ready, "Custom keybox ready")
                            !custom && managedReady -> UiText.get(R.string.waex_ui_managed_keybox_ready, "Managed keybox ready")
                            syncError.isNotBlank() -> UiText.get(R.string.waex_ui_managed_keybox_unavailable, "Managed keybox unavailable")
                            else -> UiText.get(R.string.waex_ui_keybox_setup_required, "Keybox setup required")
                        }, style = typography.bodyLg, fontWeight = FontWeight.Bold, color = colors.onSurface)
                        Text(when {
                            syncing -> UiText.get(R.string.waex_ui_downloading_and_validating_the_certifi, "Downloading and validating the certificate chain…")
                            syncError.isNotBlank() && !managedReady -> syncError
                            enabled -> UiText.get(R.string.waex_ui_active_after_whatsapp_is_restarted, "Active after WhatsApp is restarted")
                            else -> UiText.get(R.string.waex_ui_enable_the_feature_when_your_source_is, "Enable the feature when your source is ready")
                        }, style = typography.bodyMd, color = colors.onSurfaceVariant)
                    }
                }
            }
            Surface(shape = radius.bentoCardShape, color = colors.surfaceDim,
                border = BorderStroke(1.dp, colors.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(UiText.get(R.string.waex_ui_device_attestation, "DEVICE ATTESTATION"), style = typography.labelSm, fontWeight = FontWeight.Bold, color = colors.primary)
                    SettingRow(UiText.get(R.string.waex_ui_enable_bootloader_spoofer, "Enable Bootloader Spoofer"),
                        UiText.get(R.string.waex_ui_spoof_the_locked_and_verified_boot_sta, "Spoof the locked and verified boot state used by WhatsApp integrity checks"), enabled) {
                        enabled = it; prefs.putBoolean(BootloaderSpooferFeature.ENABLED, it)
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    SettingRow(UiText.get(R.string.waex_ui_use_custom_keybox, "Use Custom Keybox"),
                        if (customReady) UiText.get(R.string.waex_ui_imported_keybox_selected, "Imported keybox selected") else UiText.get(R.string.waex_ui_import_a_keybox_xml_before_enabling, "Import a keybox.xml before enabling"), custom,
                        customReady) {
                        custom = it
                        prefs.putBoolean(BootloaderSpooferFeature.CUSTOM_ENABLED, it)
                        // Source changed — invalidate cache and silently re-verify in background.
                        reportSource = ""
                        val canVerifyNew = if (it) customReady else prefs.getString(BootloaderSpooferFeature.DEFAULT_XML, "").isNotBlank()
                        if (canVerifyNew) runVerify(forCustom = it, autoOpen = false)
                    }
                }
            }
            Surface(shape = radius.bentoCardShape, color = colors.surfaceDim,
                border = BorderStroke(1.dp, colors.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(UiText.get(R.string.waex_ui_keybox_source, "KEYBOX SOURCE"), style = typography.labelSm, fontWeight = FontWeight.Bold, color = colors.primary)
                    Text(if (custom) UiText.get(R.string.waex_ui_custom_keybox, "Custom keybox") else UiText.get(R.string.waex_ui_waex_managed_keybox, "WAEX managed keybox"), style = typography.bodyLg,
                        fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                    Text(if (custom) UiText.get(R.string.waex_ui_stored_privately_on_this_device, "Stored privately on this device")
                    else if (managedReady) UiText.get(R.string.waex_ui_validated_and_stored_privately_updated, "Validated and stored privately\nUpdated: %1\$s", formatUpdated(updated))
                    else UiText.get(R.string.waex_ui_no_validated_managed_keybox_is_cached, "No validated managed keybox is cached yet"),
                        style = typography.bodyMd, color = colors.onSurfaceVariant)
                    Button(onClick = {
                        syncing = true; syncError = ""
                        BootloaderSpooferFeature.syncDefaultKeyboxAsync(context) { success, detail ->
                            syncing = false
                            if (success) {
                                updated = detail; syncError = ""
                                Toast.makeText(context, UiText.get(R.string.waex_ui_managed_keybox_updated, "Managed keybox updated"), Toast.LENGTH_SHORT).show()
                            } else {
                                syncError = detail
                                Toast.makeText(context, UiText.get(R.string.waex_ui_refresh_failed_1_s, "Refresh failed: %1\$s", detail), Toast.LENGTH_LONG).show()
                            }
                        }
                    }, enabled = !syncing, modifier = Modifier.fillMaxWidth()) {
                        Text(if (syncing) UiText.get(R.string.waex_ui_refreshing, "Refreshing…") else UiText.get(R.string.waex_ui_refresh_managed_keybox, "Refresh managed keybox"))
                    }
                    OutlinedButton(onClick = { importer.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (customReady) UiText.get(R.string.waex_ui_replace_custom_keybox_xml, "Replace custom keybox.xml") else UiText.get(R.string.waex_ui_import_custom_keybox_xml, "Import custom keybox.xml"))
                    }
                    OutlinedButton(
                        onClick = { runVerify(forCustom = custom) },
                        enabled = !verifying && (if (custom) customReady else managedReady),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (verifying) {
                            WaexCircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(UiText.get(R.string.waex_ui_verifying_keybox, "Verifying keybox…"))
                        } else {
                            Text(UiText.get(R.string.waex_ui_verify_selected_keybox, "Verify selected keybox"))
                        }
                    }
                }
            }
            Text(UiText.get(R.string.waex_ui_restart_whatsapp_after_changing_the_ke, "Restart WhatsApp after changing the keybox source or enabling this feature."),
                style = typography.bodyMd, color = colors.onSurfaceVariant)
        }
    }
    if (showReport) VerificationSheet(
        report = report,
        isLoading = verifying,
        onDismiss = { showReport = false; if (!verifying) report = null }
    )
}

private fun formatUpdated(value: String): String {
    if (value.isBlank()) return ""
    return runCatching {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val cleanValue = value.replace(Regex("\\.\\d+Z$|Z$"), "")
        val formatter = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
        formatter.format(parser.parse(cleanValue) ?: Date())
    }.getOrElse {
        value.replace("T", " ")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerificationSheet(
    report: BootloaderSpooferFeature.VerificationReport?,
    isLoading: Boolean,
    onDismiss: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val scoreColor = when {
        isLoading || report == null -> colors.onSurfaceVariant
        report.totalScore >= 85 -> colors.success
        report.totalScore >= 60 -> colors.warning
        else -> colors.error
    }

    WaexModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = radius.bottomSheetShape,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(colors.outlineVariant.copy(alpha = 0.6f))
            )
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row: Title & Close Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        UiText.get(R.string.waex_ui_keybox_verification, "Keybox Verification"),
                        style = typography.headlineMd,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    Text(
                        if (isLoading) UiText.get(R.string.waex_ui_verifying_keybox, "Verifying keybox…")
                        else report?.source ?: "",
                        style = typography.labelSm,
                        color = colors.onSurfaceVariant
                    )
                }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceDim)
                        .border(1.dp, colors.outlineVariant.copy(alpha = 0.5f), CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = WaexIcons.Clear,
                        contentDescription = UiText.get(R.string.waex_ui_close, "Close"),
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Score Banner
            Surface(
                shape = radius.cardShape,
                color = scoreColor.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, scoreColor.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            WaexCircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                strokeWidth = 3.dp,
                                color = colors.primary
                            )
                            Text(
                                UiText.get(R.string.waex_ui_verifying_keybox, "Verifying keybox…"),
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    "${report?.totalScore ?: 0}",
                                    style = typography.headlineMd,
                                    fontWeight = FontWeight.Bold,
                                    color = scoreColor,
                                    fontSize = 28.sp
                                )
                                Text(
                                    " / 100",
                                    style = typography.bodyMd,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp, start = 2.dp)
                                )
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(scoreColor.copy(alpha = 0.15f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                if (report?.recommended == true) UiText.get(R.string.waex_ui_recommended, "RECOMMENDED") else UiText.get(R.string.waex_ui_needs_attention, "NEEDS ATTENTION"),
                                style = typography.labelSm,
                                fontWeight = FontWeight.Bold,
                                color = scoreColor,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            if (!isLoading) {
                if (report != null && report.error.isNotBlank()) {
                    AuditCard(UiText.get(R.string.waex_ui_parse_error, "Parse error"), 0, 0, listOf(report.error to false))
                } else if (report != null) {
                    AuditCard(
                        UiText.get(R.string.waex_ui_spoofer_runtime, "Spoofer Runtime"),
                        report.runtimeScore,
                        10,
                        listOf(
                            UiText.get(R.string.waex_ui_feature_enabled, "Feature enabled") to report.featureEnabled,
                            UiText.get(R.string.waex_ui_whatsapp_hook_active, "WhatsApp hook active") to report.hookActive
                        )
                    )

                    AuditCard(
                        UiText.get(R.string.waex_ui_ec_attestation_chain, "EC Attestation Chain"),
                        report.ecScore,
                        40,
                        listOf(
                            UiText.get(R.string.waex_ui_certificates_parsed, "Certificates parsed") to report.ecPresent,
                            UiText.get(R.string.waex_ui_trust_chain_verified, "Trust chain verified") to report.ecChainValid,
                            UiText.get(R.string.waex_ui_private_key_matches, "Private key matches") to report.ecKeyMatches,
                            UiText.get(R.string.waex_ui_certificate_is_current, "Certificate is current") to !report.ecExpired
                        )
                    )

                    if (report.rsaNotInKeybox) {
                        AuditCard(
                            UiText.get(R.string.waex_ui_rsa_attestation_chain, "RSA Attestation Chain"),
                            report.rsaScore,
                            20,
                            listOf(
                                UiText.get(R.string.waex_ui_ec_only_keybox, "EC-only keybox") to true,
                                UiText.get(R.string.waex_ui_rsa_not_applicable, "RSA not applicable") to true
                            )
                        )
                    } else {
                        AuditCard(
                            UiText.get(R.string.waex_ui_rsa_attestation_chain, "RSA Attestation Chain"),
                            report.rsaScore,
                            20,
                            listOf(
                                UiText.get(R.string.waex_ui_certificates_parsed, "Certificates parsed") to report.rsaPresent,
                                UiText.get(R.string.waex_ui_trust_chain_verified, "Trust chain verified") to report.rsaChainValid,
                                UiText.get(R.string.waex_ui_private_key_matches, "Private key matches") to report.rsaKeyMatches,
                                UiText.get(R.string.waex_ui_certificate_is_current, "Certificate is current") to !report.rsaExpired
                            )
                        )
                    }

                    AuditCard(
                        UiText.get(R.string.waex_ui_integrity_estimate, "Integrity Estimate"),
                        report.integrityScore,
                        30,
                        listOf(
                            UiText.get(R.string.waex_ui_basic_integrity_supported, "Basic integrity supported") to report.basicIntegrity,
                            UiText.get(R.string.waex_ui_device_integrity_supported, "Device integrity supported") to report.deviceIntegrity,
                            UiText.get(R.string.waex_ui_strong_integrity_supported, "Strong integrity supported") to report.strongIntegritySupported
                        )
                    )
                }

                Text(
                    UiText.get(R.string.waex_ui_this_is_a_local_configuration_audit_no, "This is a local configuration audit, not a live Google Play Integrity verdict."),
                    style = typography.labelSm,
                    color = colors.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }

            Button(
                onClick = onDismiss,
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = radius.fullShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary
                )
            ) {
                Text(UiText.get(R.string.waex_ui_done, "Done"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun AuditCard(title: String, score: Int, maximum: Int, checks: List<Pair<String, Boolean>>) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    Surface(
        shape = radius.cardShape,
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = typography.bodyMd,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "$score / $maximum",
                    style = typography.labelSm,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary
                )
            }

            HorizontalDivider(
                color = colors.outlineVariant.copy(alpha = 0.3f),
                thickness = 0.5.dp
            )

            checks.forEach { (label, passed) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(if (passed) colors.successContainer else colors.errorContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (passed) WaexIcons.Check else WaexIcons.Clear,
                            contentDescription = null,
                            tint = if (passed) colors.onSuccessContainer else colors.onErrorContainer,
                            modifier = Modifier.size(11.dp)
                        )
                    }
                    Text(
                        label,
                        style = typography.bodyMd,
                        color = if (passed) colors.onSurface else colors.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, summary: String, checked: Boolean, enabled: Boolean = true,
                       onChecked: (Boolean) -> Unit) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = typography.bodyLg, fontWeight = FontWeight.SemiBold,
                color = if (enabled) colors.onSurface else colors.onSurfaceVariant)
            Text(summary, style = typography.bodyMd, color = colors.onSurfaceVariant)
        }
        StitchSwitch(checked = checked, onCheckedChange = { if (enabled) onChecked(it) })
    }
}
