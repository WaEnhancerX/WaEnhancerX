package com.waenhancer.ui.screens.settings

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.waenhancer.ui.components.WaexModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.components.StitchSwitch
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import com.waenhancer.update.UpdateDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.navigationBarsPadding

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSettingsScreen() {
    val navController = LocalWaexNavController.current
    val prefManager = LocalWaexPreferenceManager.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var autoCheckUpdates by remember(prefManager.targetEpoch) { mutableStateOf(prefManager.getBoolean("update_auto_check", true)) }
    var allowDowngrades by remember(prefManager.targetEpoch) { mutableStateOf(prefManager.getBoolean("downgrades_enabled", false)) }
    var rootAutoInstall by remember(prefManager.targetEpoch) { mutableStateOf(prefManager.getBoolean("root_auto_install", false)) }
    var updateFrequency by remember(prefManager.targetEpoch) { mutableStateOf(prefManager.getString("update_frequency", "daily")) }
    var checkModuleOpen by remember(prefManager.targetEpoch) { mutableStateOf(prefManager.getBoolean("update_check_module_open", true)) }
    var checkWhatsAppOpen by remember(prefManager.targetEpoch) { mutableStateOf(prefManager.getBoolean("update_check_whatsapp_open", true)) }
    var ignoredBehavior by remember(prefManager.targetEpoch) { mutableStateOf(prefManager.getString("update_ignored_behavior", "next_release")) }
    var selector by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = UiText.get(R.string.update_settings_title, "Update Settings"),
                onBackClick = { navController.popBack() }
            )
        },
        containerColor = colors.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.pageMargin, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // General Update Behaviors Card
            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceDim,
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = UiText.get(R.string.waex_ui_automated_checks, "AUTOMATED CHECKS"),
                        style = typography.labelSm,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = UiText.get(R.string.waex_ui_auto_check_for_updates, "Auto-Check for Updates"),
                                style = typography.bodyLg,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface
                            )
                            Text(
                                text = UiText.get(R.string.waex_ui_periodically_check_the_waenhancerx_rel, "Periodically check the WaEnhancerX release service"),
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        StitchSwitch(
                            checked = autoCheckUpdates,
                            onCheckedChange = {
                                autoCheckUpdates = it
                                prefManager.putBoolean("update_auto_check", it)
                            }
                        )
                    }

                    HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 14.dp))

                    UpdateSwitchRow(
                        title = UiText.get(R.string.waex_ui_check_when_module_opens, "Check when module opens"),
                        description = UiText.get(R.string.waex_ui_run_an_eligible_update_check_when_waen, "Run an eligible update check when WaEnhancerX opens"),
                        checked = checkModuleOpen,
                        onCheckedChange = {
                            checkModuleOpen = it
                            prefManager.putBoolean("update_check_module_open", it)
                        }
                    )

                    HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 14.dp))

                    UpdateSwitchRow(
                        title = UiText.get(R.string.waex_ui_check_when_whatsapp_opens, "Check when WhatsApp opens"),
                        description = UiText.get(R.string.waex_ui_ask_the_module_to_check_when_whatsapp, "Ask the module to check when WhatsApp starts"),
                        checked = checkWhatsAppOpen,
                        onCheckedChange = {
                            checkWhatsAppOpen = it
                            prefManager.putBoolean("update_check_whatsapp_open", it)
                        }
                    )

                    HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            selector = "frequency"
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(UiText.get(R.string.waex_ui_check_frequency, "Check frequency"), style = typography.bodyLg, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                            Text(frequencyLabel(updateFrequency), style = typography.bodyMd, color = colors.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Text(UiText.get(R.string.waex_ui_change, "Change"), color = colors.primary, fontWeight = FontWeight.Bold)
                    }

                    HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            selector = "ignored"
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(UiText.get(R.string.waex_ui_after_ignoring_an_update, "After ignoring an update"), style = typography.bodyLg, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                            Text(ignoredLabel(ignoredBehavior), style = typography.bodyMd, color = colors.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Text(UiText.get(R.string.waex_ui_change, "Change"), color = colors.primary, fontWeight = FontWeight.Bold)
                    }

                    HorizontalDivider(
                        thickness = 1.dp,
                        color = colors.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 14.dp)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = UiText.get(R.string.waex_ui_allow_version_downgrades, "Allow Version Downgrades"),
                                style = typography.bodyLg,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface
                            )
                            Text(
                                text = UiText.get(R.string.waex_ui_install_older_builds_over_newer_versio, "Install older builds over newer versions (Root needed)"),
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        StitchSwitch(
                            checked = allowDowngrades,
                            onCheckedChange = {
                                if (!it) {
                                    allowDowngrades = false
                                    prefManager.putBoolean("downgrades_enabled", false)
                                } else scope.launch {
                                    val granted = withContext(Dispatchers.IO) { UpdateDownloader.hasRootAccess() }
                                    if (granted) {
                                        allowDowngrades = true
                                        prefManager.putBoolean("downgrades_enabled", true)
                                    } else Toast.makeText(context, UiText.get(R.string.waex_ui_root_access_is_required_to_enable_vers, "Root access is required to enable version downgrades"), Toast.LENGTH_LONG).show()
                                }
                            }
                        )
                    }

                    HorizontalDivider(
                        thickness = 1.dp,
                        color = colors.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 14.dp)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = UiText.get(R.string.waex_ui_silent_root_install, "Silent Root Install"),
                                style = typography.bodyLg,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface
                            )
                            Text(
                                text = UiText.get(R.string.waex_ui_automatically_install_downloaded_apk_v, "Automatically install downloaded APK via root shell"),
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        StitchSwitch(
                            checked = rootAutoInstall,
                            onCheckedChange = {
                                if (!it) {
                                    rootAutoInstall = false
                                    prefManager.putBoolean("root_auto_install", false)
                                } else scope.launch {
                                    val granted = withContext(Dispatchers.IO) { UpdateDownloader.hasRootAccess() }
                                    if (granted) {
                                        rootAutoInstall = true
                                        prefManager.putBoolean("root_auto_install", true)
                                    } else Toast.makeText(context, UiText.get(R.string.waex_ui_root_access_is_required_to_enable_sile, "Root access is required to enable silent installation"), Toast.LENGTH_LONG).show()
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    selector?.let { selectionType ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val isFrequency = selectionType == "frequency"
        val options = if (isFrequency) updateFrequencyOptions() else ignoredUpdateOptions()
        val selectedValue = if (isFrequency) updateFrequency else ignoredBehavior
        WaexModalBottomSheet(
            onDismissRequest = { selector = null },
            sheetState = sheetState,
            containerColor = colors.surface,
            shape = radius.bottomSheetShape
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (isFrequency) UiText.get(R.string.waex_ui_check_frequency, "Check frequency")
                    else UiText.get(R.string.waex_ui_after_ignoring_an_update, "After ignoring an update"),
                    style = typography.titleLg,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
                options.forEach { option ->
                    val selected = selectedValue == option.first
                    Surface(
                        shape = radius.cardShape,
                        color = if (selected) colors.primary.copy(alpha = 0.12f) else colors.surfaceDim,
                        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (isFrequency) {
                                updateFrequency = option.first
                                prefManager.putString("update_frequency", option.first)
                            } else {
                                ignoredBehavior = option.first
                                prefManager.putString("update_ignored_behavior", option.first)
                            }
                            selector = null
                        }
                    ) {
                        Text(
                            text = option.second,
                            style = typography.bodyLg,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) colors.primary else colors.onSurface,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = typography.bodyLg, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Text(description, style = typography.bodyMd, color = colors.onSurfaceVariant, fontSize = 12.sp)
        }
        StitchSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun frequencyLabel(value: String) = when (value) {
    "every_open" -> UiText.get(R.string.waex_ui_every_eligible_app_open, "Every eligible app open")
    "six_hours" -> UiText.get(R.string.waex_ui_at_most_every_6_hours, "At most every 6 hours")
    "weekly" -> UiText.get(R.string.waex_ui_at_most_once_a_week, "At most once a week")
    else -> UiText.get(R.string.waex_ui_at_most_once_a_day, "At most once a day")
}

private fun updateFrequencyOptions(): List<Pair<String, String>> = listOf(
    "every_open" to UiText.get(R.string.waex_ui_every_eligible_app_open, "Every eligible app open"),
    "six_hours" to UiText.get(R.string.waex_ui_at_most_every_6_hours, "At most every 6 hours"),
    "daily" to UiText.get(R.string.waex_ui_at_most_once_a_day, "At most once a day"),
    "weekly" to UiText.get(R.string.waex_ui_at_most_once_a_week, "At most once a week")
)

private fun ignoredLabel(value: String) = when (value) {
    "next_check" -> UiText.get(R.string.waex_ui_show_it_again_on_the_next_check, "Show it again on the next check")
    "after_day" -> UiText.get(R.string.waex_ui_remind_me_again_after_one_day, "Remind me again after one day")
    "after_week" -> UiText.get(R.string.waex_ui_remind_me_again_after_one_week, "Remind me again after one week")
    else -> UiText.get(R.string.waex_ui_wait_until_a_newer_release_is_availabl, "Wait until a newer release is available")
}

private fun ignoredUpdateOptions(): List<Pair<String, String>> = listOf(
    "next_release" to UiText.get(R.string.waex_ui_wait_until_a_newer_release_is_availabl, "Wait until a newer release is available"),
    "next_check" to UiText.get(R.string.waex_ui_show_it_again_on_the_next_check, "Show it again on the next check"),
    "after_day" to UiText.get(R.string.waex_ui_remind_me_again_after_one_day, "Remind me again after one day"),
    "after_week" to UiText.get(R.string.waex_ui_remind_me_again_after_one_week, "Remind me again after one week")
)
