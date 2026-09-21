package com.waenhancer.ui.screens.settings

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.utils.SystemDiagnostics
import com.waenhancer.utils.SystemDiagnosticsReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SystemHealthScreen() {
    val nav = LocalWaexNavController.current
    val context = LocalContext.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    var diagnostics by remember { mutableStateOf<SystemDiagnostics?>(null) }
    LaunchedEffect(Unit) { diagnostics = withContext(Dispatchers.IO) { SystemDiagnosticsReader.read(context) } }

    Scaffold(topBar = { WaexTopBar("System Diagnostics", onBackClick = { nav.popBack() }) }, containerColor = colors.background) { insets ->
        val data = diagnostics
        if (data == null) {
            Column(Modifier.fillMaxSize().padding(insets), Arrangement.Center, Alignment.CenterHorizontally) { CircularProgressIndicator(color = colors.primary) }
        } else {
            Column(
                Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(horizontal = spacing.pageMargin, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DiagnosticCard("Module & Framework", listOf(
                    "Module status" to if (data.moduleActive) "Active" else "Inactive",
                    "Module version" to "${data.moduleVersion} (${data.moduleVersionCode})",
                    "Xposed framework" to data.framework.name,
                    "Framework package" to (data.framework.packageName ?: "Not available"),
                    "Framework version" to (data.framework.version ?: "Not available"),
                    "Xposed API" to (data.framework.api?.toString() ?: "Not reported yet"),
                    "Root allowed" to if (data.rootAllowed) "Yes" else "No"
                ), if (data.moduleActive) Color(0xFF10B981) else colors.error)

                DiagnosticCard("Device Environment", listOf(
                    "Android" to "${data.androidVersion} (API ${data.sdk})",
                    "Device" to data.device,
                    "Supported ABIs" to data.abis,
                    "Kernel" to data.kernel,
                    "SELinux" to data.selinux
                ), colors.primary)

                DiagnosticCard("Hook Runtime", listOf(
                    "Reported hooked targets" to data.hookedTargets,
                    "Manager process hooked" to if (data.moduleActive) "Yes" else "No",
                    "Runtime API captured" to if (data.framework.api != null) "Yes" else "No"
                ), colors.primary)
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
    Surface(shape = radius.bentoCardShape, color = colors.surfaceContainerLow, border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant), modifier = Modifier.fillMaxWidth()) {
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
