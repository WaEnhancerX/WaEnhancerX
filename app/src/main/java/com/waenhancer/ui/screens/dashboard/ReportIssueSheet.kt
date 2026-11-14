package com.waenhancer.ui.screens.dashboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waenhancer.app.R
import com.waenhancer.ui.components.WaexModalBottomSheet
import com.waenhancer.ui.components.WaexSheetHorizontalMargin
import com.waenhancer.ui.components.WaexSheetPrimaryButton
import com.waenhancer.ui.components.WaexSheetTextButton
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import com.waenhancer.core.preferences.SettingsBackupCodec
import com.waenhancer.utils.HookedAppInfo
import com.waenhancer.utils.SystemDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

private const val REPORT_TELEGRAM_USER = "mubashardev"
private const val TITLE_MIN = 15
private const val TITLE_MAX = 50
private const val DESCRIPTION_MIN = 15
private const val LAST_STEP = 3
private const val STEP_APPS = 1
private const val STEP_TITLE = 2

private val TELEGRAM_PACKAGES = listOf(
    "org.telegram.messenger",
    "org.telegram.messenger.web",
    "org.thunderdog.challegram"
)

/**
 * Step-by-step bug report flow: review what is sent, name the issue, describe it, then hand the
 * report (plus a settings backup) to Telegram.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportIssueSheet(
    moduleVersion: String,
    diagnostics: SystemDiagnostics?,
    targets: List<HookedAppInfo>,
    onDismiss: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferenceManager = LocalWaexPreferenceManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var step by remember { mutableIntStateOf(0) }
    var forward by remember { mutableStateOf(true) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    val installedTargets = remember(targets) { targets.filter { it.isInstalled } }
    val selected = remember(installedTargets) {
        mutableStateListOf<String>().apply {
            if (installedTargets.size == 1) add(installedTargets.first().packageName)
        }
    }

    val unknown = stringResource(R.string.report_issue_unknown)
    val frameworkLabel = diagnostics?.framework?.let { fw ->
        listOfNotNull(fw.name, fw.version, fw.api?.let { "API $it" }).joinToString(" · ")
    } ?: unknown
    val configMode = if (preferenceManager.getString("module_config_mode", "Universal") == "Universal") {
        stringResource(R.string.report_issue_config_universal)
    } else {
        stringResource(R.string.report_issue_config_per_app)
    }
    val details = remember(moduleVersion, frameworkLabel, configMode) {
        listOf(
            context.getString(R.string.report_issue_label_device) to "${Build.MANUFACTURER} ${Build.MODEL}",
            context.getString(R.string.report_issue_label_android) to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            context.getString(R.string.report_issue_label_framework) to frameworkLabel,
            context.getString(R.string.report_issue_label_module) to moduleVersion,
            context.getString(R.string.report_issue_label_config_mode) to configMode
        )
    }

    val titleLength = title.trim().length
    val descriptionLength = description.trim().length
    val canContinue = when (step) {
        0 -> true
        STEP_APPS -> selected.isNotEmpty()
        STEP_TITLE -> titleLength in TITLE_MIN..TITLE_MAX
        else -> descriptionLength >= DESCRIPTION_MIN
    }

    val animatedProgress by animateFloatAsState(
        targetValue = (step + 1) / (LAST_STEP + 1f),
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "reportProgress"
    )

    WaexModalBottomSheet(
        onDismissRequest = { if (!sending) onDismiss() },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = WaexSheetHorizontalMargin)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.report_issue_title),
                    style = typography.titleMd,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
                Text(
                    text = stringResource(R.string.report_issue_step_of, step + 1, LAST_STEP + 1),
                    style = typography.bodySm,
                    color = colors.onSurfaceVariant
                )
            }

            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape),
                color = colors.primary,
                trackColor = colors.surfaceContainerHighest
            )

            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val dir = if (forward) 1 else -1
                    (fadeIn(tween(250)) + slideInHorizontally(tween(300)) { it / 6 * dir }) togetherWith
                        (fadeOut(tween(150)) + slideOutHorizontally(tween(300)) { -it / 6 * dir })
                },
                label = "reportStep"
            ) { current ->
                when (current) {
                    0 -> Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.report_issue_review_desc, "@$REPORT_TELEGRAM_USER"),
                            style = typography.bodyMd,
                            color = colors.onSurfaceVariant
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(WaexTheme.radius.mdShape)
                                .background(colors.surfaceContainer)
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            details.forEach { (label, value) ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = label,
                                        style = typography.bodySm,
                                        color = colors.onSurfaceVariant,
                                        modifier = Modifier.width(110.dp)
                                    )
                                    Text(
                                        text = value,
                                        style = typography.bodySm,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    STEP_APPS -> Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.report_issue_apps_prompt),
                            style = typography.bodyMd,
                            color = colors.onSurfaceVariant
                        )
                        if (installedTargets.isEmpty()) {
                            Text(
                                text = stringResource(R.string.report_issue_apps_none),
                                style = typography.bodySm,
                                color = colors.error
                            )
                        }
                        installedTargets.forEach { app ->
                            val checked = app.packageName in selected
                            val toggle = {
                                if (checked) selected.remove(app.packageName) else selected.add(app.packageName)
                                Unit
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(WaexTheme.radius.mdShape)
                                    .background(colors.surfaceContainer)
                                    .clickable(role = Role.Checkbox, onClick = toggle)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = { toggle() })
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.appName,
                                        style = typography.bodyMd,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = "${app.packageName} · ${app.versionName}",
                                        style = typography.bodySm,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    STEP_TITLE -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = stringResource(R.string.report_issue_title_prompt, TITLE_MIN, TITLE_MAX),
                            style = typography.bodyMd,
                            color = colors.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it.take(TITLE_MAX) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.report_issue_title_hint)) },
                            supportingText = { Text("${title.length}/$TITLE_MAX") }
                        )
                    }

                    else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = stringResource(R.string.report_issue_description_prompt),
                            style = typography.bodyMd,
                            color = colors.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 140.dp),
                            minLines = 5,
                            label = { Text(stringResource(R.string.report_issue_description_hint)) }
                        )
                    }
                }
            }

            WaexSheetPrimaryButton(
                text = stringResource(if (step == LAST_STEP) R.string.report_issue_submit else R.string.report_issue_next),
                onClick = {
                    if (step < LAST_STEP) {
                        forward = true
                        step++
                    } else {
                        sending = true
                        scope.launch {
                            val packages = selected.toSet()
                            val chosen = installedTargets.filter { it.packageName in packages }
                            val message = withContext(Dispatchers.IO) {
                                val settings = runCatching {
                                    SettingsBackupCodec.renderPlainText(context, packages)
                                }.getOrDefault("")
                                buildReportMessage(
                                    title = title.trim(),
                                    description = description.trim(),
                                    details = details,
                                    apps = chosen,
                                    settings = settings
                                )
                            }
                            val launched = launchTelegram(context, message)
                            sending = false
                            if (launched) onDismiss()
                        }
                    }
                },
                enabled = canContinue && !sending
            )
            WaexSheetTextButton(
                text = stringResource(if (step == 0) R.string.report_issue_cancel else R.string.report_issue_back),
                onClick = { if (step == 0) onDismiss() else { forward = false; step-- } },
                enabled = !sending
            )
        }
    }
}

private fun buildReportMessage(
    title: String,
    description: String,
    details: List<Pair<String, String>>,
    apps: List<HookedAppInfo>,
    settings: String
): String = buildString {
    append("🐛 **").append(title).append("**\n\n")
    details.forEach { (label, value) -> append("**").append(label).append(":** ").append(value).append('\n') }
    apps.forEach { app -> append("**").append(app.appName).append(":** ").append(app.versionName).append('\n') }
    append("\n---\n").append(description).append('\n')
    if (settings.isNotEmpty()) {
        append("\n**Settings:**\n```\n").append(settings).append("\n```\n")
    }
}

private fun launchTelegram(context: Context, message: String): Boolean {
    val telegram = TELEGRAM_PACKAGES.firstOrNull { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    val share = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_TEXT, message)
        type = "text/plain"
        telegram?.let { setPackage(it) }
    }
    val intent = if (telegram != null) share else Intent.createChooser(share, null)
    if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) {
        return true
    }

    val url = "https://t.me/$REPORT_TELEGRAM_USER?text=" + URLEncoder.encode(message, "UTF-8")
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val opened = runCatching { context.startActivity(view) }.isSuccess
    if (!opened) {
        Toast.makeText(context, R.string.report_issue_failed, Toast.LENGTH_LONG).show()
    }
    return opened
}
