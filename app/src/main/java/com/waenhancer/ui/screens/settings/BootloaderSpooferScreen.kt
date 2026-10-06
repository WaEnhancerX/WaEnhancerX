package com.waenhancer.ui.screens.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    var enabled by remember { mutableStateOf(prefs.getBoolean(BootloaderSpooferFeature.ENABLED, false)) }
    var custom by remember { mutableStateOf(prefs.getBoolean(BootloaderSpooferFeature.CUSTOM_ENABLED, false)) }
    var customReady by remember { mutableStateOf(prefs.getString(BootloaderSpooferFeature.CUSTOM_XML, "").isNotBlank()) }
    var updated by remember { mutableStateOf(prefs.getString(BootloaderSpooferFeature.LAST_UPDATED, "")) }
    var syncError by remember { mutableStateOf(prefs.getString(BootloaderSpooferFeature.LAST_ERROR, "")) }
    var syncing by remember { mutableStateOf(false) }

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
                }
            }
            Text("Restart WhatsApp after changing the keybox source or enabling this feature.",
                style = typography.bodyMd, color = colors.onSurfaceVariant)
        }
    }
}

private fun formatUpdated(value: String): String = value
    .replace("T", " ")
    .replace(Regex("\\.\\d+Z$"), " UTC")

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
