package com.waenhancer.ui.screens.settings

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
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val xml = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Could not read the selected file")
                    require(xml.length <= 2_000_000) { "Keybox file is too large" }
                    BootloaderSpooferFeature.parse(xml)
                    prefs.putString(BootloaderSpooferFeature.CUSTOM_XML, xml)
                }
            }.onSuccess {
                customReady = true
                custom = true
                prefs.putBoolean(BootloaderSpooferFeature.CUSTOM_ENABLED, true)
                Toast.makeText(context, "Custom keybox imported", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(context, "Invalid keybox: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    Scaffold(topBar = { WaexTopBar("Bootloader Spoofer", onBackClick = { nav.popBack() }) },
        containerColor = colors.background) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
            .padding(horizontal = WaexTheme.spacing.pageMargin, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val managedReady = updated.isNotBlank()
            val statusColor = when {
                syncing -> colors.primary
                custom && customReady -> Color(0xFF10B981)
                !custom && managedReady -> Color(0xFF10B981)
                syncError.isNotBlank() -> colors.error
                else -> Color(0xFFF59E0B)
            }
            Surface(shape = radius.bentoCardShape, color = statusColor.copy(alpha = 0.09f),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f)), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (syncing) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = statusColor)
                    else Surface(Modifier.size(12.dp), shape = androidx.compose.foundation.shape.CircleShape, color = statusColor) {}
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(when {
                            syncing -> "Refreshing managed keybox"
                            custom && customReady -> "Custom keybox ready"
                            !custom && managedReady -> "Managed keybox ready"
                            syncError.isNotBlank() -> "Managed keybox unavailable"
                            else -> "Keybox setup required"
                        }, style = typography.bodyLg, fontWeight = FontWeight.Bold, color = colors.onSurface)
                        Text(when {
                            syncing -> "Downloading and validating the certificate chain…"
                            syncError.isNotBlank() && !managedReady -> syncError
                            enabled -> "Active after WhatsApp is restarted"
                            else -> "Enable the feature when your source is ready"
                        }, style = typography.bodyMd, color = colors.onSurfaceVariant)
                    }
                }
            }
            Surface(shape = radius.bentoCardShape, color = colors.surfaceDim,
                border = BorderStroke(1.dp, colors.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("DEVICE ATTESTATION", style = typography.labelSm, fontWeight = FontWeight.Bold, color = colors.primary)
                    SettingRow("Enable Bootloader Spoofer",
                        "Spoof the locked and verified boot state used by WhatsApp integrity checks", enabled) {
                        enabled = it; prefs.putBoolean(BootloaderSpooferFeature.ENABLED, it)
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    SettingRow("Use Custom Keybox",
                        if (customReady) "Imported keybox selected" else "Import a keybox.xml before enabling", custom,
                        customReady) { custom = it; prefs.putBoolean(BootloaderSpooferFeature.CUSTOM_ENABLED, it) }
                }
            }
            Surface(shape = radius.bentoCardShape, color = colors.surfaceDim,
                border = BorderStroke(1.dp, colors.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("KEYBOX SOURCE", style = typography.labelSm, fontWeight = FontWeight.Bold, color = colors.primary)
                    Text(if (custom) "Custom keybox" else "WAEX managed keybox", style = typography.bodyLg,
                        fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                    Text(if (custom) "Stored privately on this device"
                    else if (managedReady) "Validated and stored privately\nUpdated: ${formatUpdated(updated)}"
                    else "No validated managed keybox is cached yet",
                        style = typography.bodyMd, color = colors.onSurfaceVariant)
                    Button(onClick = {
                        syncing = true; syncError = ""
                        BootloaderSpooferFeature.syncDefaultKeyboxAsync(context) { success, detail ->
                            syncing = false
                            if (success) {
                                updated = detail; syncError = ""
                                Toast.makeText(context, "Managed keybox updated", Toast.LENGTH_SHORT).show()
                            } else {
                                syncError = detail
                                Toast.makeText(context, "Refresh failed: $detail", Toast.LENGTH_LONG).show()
                            }
                        }
                    }, enabled = !syncing, modifier = Modifier.fillMaxWidth()) {
                        Text(if (syncing) "Refreshing…" else "Refresh managed keybox")
                    }
                    OutlinedButton(onClick = { importer.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (customReady) "Replace custom keybox.xml" else "Import custom keybox.xml")
                    }
                    OutlinedButton(onClick = {
                        verifying = true
                        scope.launch {
                            val selectedXml = withContext(Dispatchers.IO) {
                                prefs.getString(if (custom) BootloaderSpooferFeature.CUSTOM_XML
                                else BootloaderSpooferFeature.DEFAULT_XML, "")
                            }
                            report = withContext(Dispatchers.Default) {
                                BootloaderSpooferFeature.verify(context, selectedXml, !custom)
                            }
                            verifying = false; showReport = true
                        }
                    }, enabled = !verifying && (if (custom) customReady else managedReady),
                        modifier = Modifier.fillMaxWidth()) {
                        if (verifying) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Verify selected keybox")
                    }
                }
            }
            Text("Restart WhatsApp after changing the keybox source or enabling this feature.",
                style = typography.bodyMd, color = colors.onSurfaceVariant)
        }
    }
    if (showReport && report != null) VerificationSheet(report!!, onDismiss = { showReport = false })
}

private fun formatUpdated(value: String): String = value
    .replace("T", " ")
    .replace(Regex("\\.\\d+Z$"), " UTC")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerificationSheet(report: BootloaderSpooferFeature.VerificationReport, onDismiss: () -> Unit) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val scoreColor = when {
        report.totalScore >= 85 -> Color(0xFF10B981)
        report.totalScore >= 60 -> Color(0xFFF59E0B)
        else -> colors.error
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = radius.bottomSheetShape,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
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
                        "Keybox Verification",
                        style = typography.headlineMd,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    Text(
                        report.source,
                        style = typography.labelSm,
                        color = colors.onSurfaceVariant
                    )
                }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(colors.surfaceDim)
                        .border(1.dp, colors.outlineVariant.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = com.waenhancer.ui.designsystem.WaexIcons.Clear,
                        contentDescription = "Close",
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
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "${report.totalScore}",
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
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(scoreColor.copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            if (report.recommended) "RECOMMENDED" else "NEEDS ATTENTION",
                            style = typography.labelSm,
                            fontWeight = FontWeight.Bold,
                            color = scoreColor,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            if (report.error.isNotBlank()) {
                AuditCard("Parse error", 0, 0, listOf(report.error to false))
            } else {
                AuditCard(
                    "Spoofer Runtime",
                    report.runtimeScore,
                    10,
                    listOf(
                        "Feature enabled" to report.featureEnabled,
                        "WhatsApp hook active" to report.hookActive
                    )
                )

                AuditCard(
                    "EC Attestation Chain",
                    report.ecScore,
                    40,
                    listOf(
                        "Certificates parsed" to report.ecPresent,
                        "Trust chain verified" to report.ecChainValid,
                        "Private key matches" to report.ecKeyMatches,
                        "Certificate is current" to !report.ecExpired
                    )
                )

                AuditCard(
                    "RSA Attestation Chain",
                    report.rsaScore,
                    20,
                    listOf(
                        "Certificates parsed" to report.rsaPresent,
                        "Trust chain verified" to report.rsaChainValid,
                        "Private key matches" to report.rsaKeyMatches,
                        "Certificate is current" to !report.rsaExpired
                    )
                )

                AuditCard(
                    "Integrity Estimate",
                    report.integrityScore,
                    30,
                    listOf(
                        "Basic integrity supported" to report.basicIntegrity,
                        "Device integrity supported" to report.deviceIntegrity,
                        "Strong integrity supported" to false
                    )
                )
            }

            Text(
                "This is a local configuration audit, not a live Google Play Integrity verdict.",
                style = typography.labelSm,
                color = colors.onSurfaceVariant.copy(alpha = 0.8f)
            )

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = radius.fullShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary
                )
            ) {
                Text("Done", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(if (passed) Color(0xFF10B981).copy(alpha = 0.15f) else colors.error.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (passed) com.waenhancer.ui.designsystem.WaexIcons.Check else com.waenhancer.ui.designsystem.WaexIcons.Clear,
                            contentDescription = null,
                            tint = if (passed) Color(0xFF10B981) else colors.error,
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
