package com.waenhancer.ui.screens.dashboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import com.waenhancer.ui.components.WaexCircularProgressIndicator
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waenhancer.app.R
import com.waenhancer.core.preferences.SettingsBackupCodec
import com.waenhancer.licensing.report.IssueKind
import com.waenhancer.licensing.report.ReportLogin
import com.waenhancer.licensing.report.ReportSubmission
import com.waenhancer.ui.components.TargetAppIcon
import com.waenhancer.ui.components.WaexSheetPrimaryButton
import com.waenhancer.ui.components.WaexSheetTextButton
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import com.waenhancer.utils.DiagnosticsRecorder
import com.waenhancer.utils.SystemDiagnosticsReader
import com.waenhancer.utils.TargetAppManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

private const val REPORT_TELEGRAM_USER = "mubashardev"
private const val TITLE_MIN = 15
private const val TITLE_MAX = 50
private const val DESCRIPTION_MIN = 15
private const val STEP_KIND = 1
private const val STEP_APPS = 2
private const val STEP_TITLE = 3
private const val STEP_DESCRIPTION = 4

/** The wording shown for each kind of report: its title and a one-line explanation. */
private fun IssueKind.labels(): Pair<Int, Int> = when (this) {
    IssueKind.BUG -> R.string.report_issue_kind_bug to R.string.report_issue_kind_bug_desc
    IssueKind.ERROR -> R.string.report_issue_kind_error to R.string.report_issue_kind_error_desc
    IssueKind.MALFUNCTION -> R.string.report_issue_kind_malfunction to R.string.report_issue_kind_malfunction_desc
    IssueKind.FEATURE -> R.string.report_issue_kind_feature to R.string.report_issue_kind_feature_desc
}

private val TELEGRAM_PACKAGES = listOf(
    "org.telegram.messenger",
    "org.telegram.messenger.web",
    "org.thunderdog.challegram"
)

/** What the screen is doing once the reporter has pressed Submit. */
private sealed interface SendPhase {
    data object Idle : SendPhase

    /** Collecting the settings and device details that go on the public issue. */
    data object Composing : SendPhase

    /** Gathering the logs of the selected apps; nothing has left the device yet. */
    data class Preparing(val appName: String, val index: Int, val count: Int, val fraction: Float) : SendPhase

    /** Uploading the report; [progress] is null while the server finishes creating the issue. */
    data class Submitting(val progress: Float?) : SendPhase

    /** The issue exists; shown for a moment before leaving the screen. */
    data object Done : SendPhase
}

/**
 * Step-by-step bug report flow on its own screen, so nothing can dismiss it half way: review what is
 * sent, pick the affected apps, name the issue, describe it, then submit it to the bot.
 */
@Composable
fun ReportIssueScreen() {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val context = LocalContext.current
    val navController = LocalWaexNavController.current
    val scope = rememberCoroutineScope()
    val preferenceManager = LocalWaexPreferenceManager.current

    val username = remember(context) { ReportLogin.currentUser(context).orEmpty() }
    val moduleVersion = remember(context) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    val moduleActivation by TargetAppManager.moduleActivation.collectAsState()
    val targets by TargetAppManager.detectedApps.collectAsState()
    val diagnostics = remember(moduleActivation.active) {
        SystemDiagnosticsReader.getOrFastSnapshot(context, moduleActivation.active)
    }

    var step by remember { mutableIntStateOf(0) }
    var forward by remember { mutableStateOf(true) }
    var kind by remember { mutableStateOf<IssueKind?>(null) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf<SendPhase>(SendPhase.Idle) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    val imeBottom = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    var buttonsHeight by remember { mutableStateOf(0.dp) }
    val sending = phase != SendPhase.Idle
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
    val details = remember(username, moduleVersion, frameworkLabel, configMode) {
        listOf(
            context.getString(R.string.report_issue_label_telegram) to username,
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
        STEP_KIND -> kind != null
        STEP_APPS -> selected.isNotEmpty()
        STEP_TITLE -> titleLength in TITLE_MIN..TITLE_MAX
        else -> descriptionLength >= DESCRIPTION_MIN
    }

    // A feature request is an idea, not a failure: it names no target apps, so that step is skipped.
    val isFeature = kind == IssueKind.FEATURE
    val steps = if (isFeature) {
        listOf(0, STEP_KIND, STEP_TITLE, STEP_DESCRIPTION)
    } else {
        listOf(0, STEP_KIND, STEP_APPS, STEP_TITLE, STEP_DESCRIPTION)
    }
    val position = steps.indexOf(step)
    fun goNext() { forward = true; step = steps[position + 1] }
    fun goBack() { forward = false; step = steps[position - 1] }

    val animatedProgress by animateFloatAsState(
        targetValue = (position + 1) / steps.size.toFloat(),
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "reportProgress"
    )


    BackHandler(enabled = true) {
        when {
            sending -> Unit // Never abandon a report that is being prepared or sent.
            position > 0 -> goBack()
            else -> navController.popBack()
        }
    }

    // No verified login, or it expired: hand the report to Telegram as a message instead.
    fun handOffToTelegram(message: String) {
        val launched = launchTelegram(context, message)
        phase = SendPhase.Idle
        if (launched) navController.popBack()
    }

    fun submit() {
        errorMessage = null
        val chosen = if (isFeature) emptyList() else installedTargets.filter { it.packageName in selected }
        val reportTargets = chosen.map { it.toReportTarget() }
        val reportKind = kind ?: IssueKind.BUG
        phase = SendPhase.Composing
        scope.launch {
            val telegramLabel = context.getString(R.string.report_issue_label_telegram)
            val isUniversal = preferenceManager.getString("module_config_mode", "Universal") == "Universal"
            // An idea comes with no app settings: there is no app involved to describe.
            val settingsSections = if (isFeature) {
                emptyList()
            } else {
                withContext(Dispatchers.IO) {
                    runCatching {
                        SettingsBackupCodec.renderSettingsSections(
                            context,
                            perApp = !isUniversal,
                            packages = chosen.map { it.packageName }
                        )
                    }.getOrDefault(emptyList())
                }
            }
            val request = ReportSubmission.Request(
                kind = reportKind,
                title = title.trim(),
                description = description.trim(),
                details = details.filter { it.first != telegramLabel },
                targets = reportTargets,
                settingsSections = settingsSections,
                journalFile = { DiagnosticsRecorder.export(context) }
            )
            val outcome = ReportSubmission.submit(context, request) { progress ->
                phase = when (progress) {
                    is ReportSubmission.Progress.Preparing ->
                        SendPhase.Preparing(progress.appName, progress.index, progress.count, progress.fraction)
                    is ReportSubmission.Progress.Submitting -> SendPhase.Submitting(progress.fraction)
                }
            }
            when (outcome) {
                ReportSubmission.Outcome.Sent -> {
                    phase = SendPhase.Done
                    delay(1400)
                    phase = SendPhase.Idle
                    navController.popBack()
                }
                is ReportSubmission.Outcome.RateLimited -> {
                    Toast.makeText(context, R.string.report_issue_rate_limited, Toast.LENGTH_LONG).show()
                    phase = SendPhase.Idle
                }
                is ReportSubmission.Outcome.Rejected -> {
                    // The server refused the report and created nothing: show why and stay on the form.
                    errorMessage = outcome.message.ifBlank { context.getString(R.string.report_issue_rejected) }
                    phase = SendPhase.Idle
                }
                ReportSubmission.Outcome.Unconfirmed -> {
                    // The request may have reached the server before the answer was lost, so do not
                    // resend through Telegram: that could create a duplicate issue.
                    errorMessage = context.getString(R.string.report_issue_unconfirmed)
                    phase = SendPhase.Idle
                }
                is ReportSubmission.Outcome.LogsUnavailable -> {
                    // Without every selected app's log the report would be incomplete: nothing was sent.
                    errorMessage = context.getString(R.string.report_issue_logs_failed, outcome.appName)
                    phase = SendPhase.Idle
                }
                is ReportSubmission.Outcome.NoSession -> handOffToTelegram(outcome.fallbackMessage)
                is ReportSubmission.Outcome.SessionExpired -> {
                    Toast.makeText(context, R.string.license_tg_login_required, Toast.LENGTH_LONG).show()
                    handOffToTelegram(outcome.fallbackMessage)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = stringResource(R.string.report_issue_title),
                onBackClick = { if (!sending) navController.popBack() }
            )
        },
        containerColor = colors.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = WaexTheme.spacing.pageMargin)
                .padding(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (sending) {
                SendingPanel(phase, withLogs = !isFeature, modifier = Modifier.weight(1f).fillMaxWidth())
            } else {
                // The buttons stay anchored to the bottom and slide under the keyboard; only the
                // form area shrinks to the space the keyboard leaves free.
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(bottom = maxOf(imeBottom, buttonsHeight + 16.dp)),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.report_issue_step_of, position + 1, steps.size),
                            style = typography.bodySm,
                            color = colors.onSurfaceVariant
                        )

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
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            transitionSpec = {
                                val dir = if (forward) 1 else -1
                                (fadeIn(tween(250)) + slideInHorizontally(tween(300)) { it / 6 * dir }) togetherWith
                                    (fadeOut(tween(150)) + slideOutHorizontally(tween(300)) { -it / 6 * dir })
                            },
                            label = "reportStep"
                        ) { current ->
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                when (current) {
                                    0 -> {
                                        Text(
                                            text = stringResource(R.string.report_issue_review_desc),
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

                                    STEP_KIND -> {
                                        Text(
                                            text = stringResource(R.string.report_issue_kind_prompt),
                                            style = typography.bodyMd,
                                            color = colors.onSurfaceVariant
                                        )
                                        IssueKind.entries.forEach { option ->
                                            val (titleRes, descriptionRes) = option.labels()
                                            val chosen = kind == option
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(WaexTheme.radius.mdShape)
                                                    .background(if (chosen) colors.primaryContainer else colors.surfaceContainer)
                                                    .clickable(role = Role.RadioButton) { kind = option }
                                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(selected = chosen, onClick = { kind = option })
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = stringResource(titleRes),
                                                        style = typography.bodyMd,
                                                        fontWeight = FontWeight.Medium,
                                                        color = colors.onSurface
                                                    )
                                                    Text(
                                                        text = stringResource(descriptionRes),
                                                        style = typography.bodySm,
                                                        color = colors.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    STEP_APPS -> {
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
                                                TargetAppIcon(
                                                    packageName = app.packageName,
                                                    size = 36.dp,
                                                    modifier = Modifier.padding(start = 8.dp, end = 8.dp)
                                                )
                                            }
                                        }
                                    }

                                    STEP_TITLE -> {
                                        Text(
                                            text = stringResource(
                                                if (isFeature) R.string.report_issue_feature_title_prompt else R.string.report_issue_title_prompt,
                                                TITLE_MIN,
                                                TITLE_MAX
                                            ),
                                            style = typography.bodyMd,
                                            color = colors.onSurfaceVariant
                                        )
                                        OutlinedTextField(
                                            value = title,
                                            onValueChange = { title = it.take(TITLE_MAX) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            label = {
                                                Text(
                                                    stringResource(
                                                        if (isFeature) R.string.report_issue_feature_title_hint else R.string.report_issue_title_hint
                                                    )
                                                )
                                            },
                                            placeholder = {
                                                Text(
                                                    stringResource(
                                                        if (isFeature) R.string.report_issue_feature_title_example else R.string.report_issue_title_example
                                                    )
                                                )
                                            },
                                            supportingText = { Text("${title.length}/$TITLE_MAX") }
                                        )
                                        kind?.let { chosenKind ->
                                            Text(
                                                text = stringResource(
                                                    R.string.report_issue_title_preview,
                                                    chosenKind.prefixedTitle(title.trim().ifEmpty { "…" })
                                                ),
                                                style = typography.bodySm,
                                                color = colors.onSurfaceVariant
                                            )
                                        }
                                    }

                                    else -> {
                                        Text(
                                            text = stringResource(
                                                if (isFeature) R.string.report_issue_feature_description_prompt else R.string.report_issue_description_prompt
                                            ),
                                            style = typography.bodyMd,
                                            color = colors.onSurfaceVariant
                                        )
                                        OutlinedTextField(
                                            value = description,
                                            onValueChange = { description = it },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 160.dp),
                                            minLines = 6,
                                            label = {
                                                Text(
                                                    stringResource(
                                                        if (isFeature) R.string.report_issue_feature_description_hint else R.string.report_issue_description_hint
                                                    )
                                                )
                                            },
                                            placeholder = {
                                                Text(
                                                    stringResource(
                                                        if (isFeature) R.string.report_issue_feature_description_example else R.string.report_issue_description_example
                                                    )
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }

                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .onSizeChanged { buttonsHeight = with(density) { it.height.toDp() } },
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        errorMessage?.let { message ->
                            Text(
                                text = message,
                                style = typography.bodySm,
                                color = colors.onErrorContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(WaexTheme.radius.mdShape)
                                    .background(colors.errorContainer)
                                    .padding(12.dp)
                            )
                        }
                        WaexSheetPrimaryButton(
                            text = stringResource(if (step == STEP_DESCRIPTION) R.string.report_issue_submit else R.string.report_issue_next),
                            onClick = {
                                if (step != STEP_DESCRIPTION) goNext() else submit()
                            },
                            enabled = canContinue
                        )
                        WaexSheetTextButton(
                            text = stringResource(if (step == 0) R.string.report_issue_cancel else R.string.report_issue_back),
                            onClick = { if (position == 0) navController.popBack() else goBack() }
                        )
                    }
                }
            }
        }
    }
}

private const val SEND_STEPS = 4

/** Which of the four steps is running and how far along it is (0..1); [SEND_STEPS] means all done. */
private fun SendPhase.stepAndFraction(): Pair<Int, Float?> = when (this) {
    SendPhase.Idle, SendPhase.Composing -> 0 to null
    is SendPhase.Preparing -> 1 to fraction
    is SendPhase.Submitting -> if (progress == null) 3 to null else 2 to progress
    SendPhase.Done -> SEND_STEPS to 1f
}

/** A checklist of what is happening while the report is prepared and sent, so waiting is never blank. */
@Composable
private fun SendingPanel(phase: SendPhase, withLogs: Boolean, modifier: Modifier = Modifier) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val (rawStep, fraction) = phase.stepAndFraction()
    // A feature request has no logs to gather, so that row is left out and the later ones shift up.
    val total = if (withLogs) SEND_STEPS else SEND_STEPS - 1
    val step = if (withLogs) rawStep else if (rawStep <= 1) 0 else rawStep - 1

    val overall by animateFloatAsState(
        targetValue = ((step + (fraction?.takeIf { withLogs || rawStep > 1 } ?: 0f)) / total).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "sendProgress"
    )
    val preparingDetail = (phase as? SendPhase.Preparing)
        ?.let { stringResource(R.string.report_issue_preparing_app, it.appName, it.index, it.count) }
        .orEmpty()
    val uploadDetail = (phase as? SendPhase.Submitting)?.progress
        ?.let { stringResource(R.string.report_issue_submitting_percent, (it * 100).toInt()) }
        .orEmpty()
    val finishingDetail = stringResource(R.string.report_issue_finishing)
    val labels = if (withLogs) {
        listOf(
            R.string.report_issue_step_settings,
            R.string.report_issue_step_logs,
            R.string.report_issue_step_upload,
            R.string.report_issue_step_create
        )
    } else {
        listOf(
            R.string.report_issue_step_settings,
            R.string.report_issue_step_upload,
            R.string.report_issue_step_create
        )
    }
    val details = if (withLogs) {
        listOf("", preparingDetail, uploadDetail, finishingDetail)
    } else {
        listOf("", uploadDetail, finishingDetail)
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(
                    when {
                        phase is SendPhase.Done -> R.string.report_issue_done
                        rawStep <= 1 -> R.string.report_issue_preparing
                        else -> R.string.report_issue_submitting
                    }
                ),
                style = typography.titleMd,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface
            )
            Text(
                text = stringResource(
                    if (phase is SendPhase.Done) R.string.report_issue_done_desc else R.string.report_issue_preparing_hint
                ),
                style = typography.bodyMd,
                color = colors.onSurfaceVariant
            )
        }

        LinearProgressIndicator(
            progress = { overall },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color = colors.primary,
            trackColor = colors.surfaceContainerHighest
        )

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            labels.forEachIndexed { index, label ->
                val state = when {
                    index < step -> 2 // done
                    index == step -> 1 // active
                    else -> 0 // waiting
                }
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        when (state) {
                            2 -> Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            1 -> WaexCircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                            else -> Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(colors.surfaceContainerHighest)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(label),
                            style = typography.bodyMd,
                            fontWeight = if (state == 1) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (state == 0) colors.onSurfaceVariant else colors.onSurface
                        )
                        if (state == 1 && details[index].isNotEmpty()) {
                            Text(
                                text = details[index],
                                style = typography.bodySm,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
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
