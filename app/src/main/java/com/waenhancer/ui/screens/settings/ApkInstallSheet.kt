package com.waenhancer.ui.screens.settings

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waenhancer.app.R
import com.waenhancer.ui.components.WaexCircularProgressIndicator
import com.waenhancer.ui.components.WaexModalBottomSheet
import com.waenhancer.ui.components.WaexSheetHorizontalMargin
import com.waenhancer.ui.components.WaexSheetPrimaryButton
import com.waenhancer.ui.components.WaexSheetTextButton
import com.waenhancer.ui.components.WaexSheetTitle
import com.waenhancer.ui.components.waexSheetAccent
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.utils.UiText
import com.waenhancer.utils.UniversalVersionValidator
import com.waenhancer.utils.WhatsAppApkInstaller
import com.waenhancer.utils.WhatsAppApkVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the sheet is: validating step by step, ready to install, installing, or stopped. */
private sealed interface InstallPhase {
    /** [step] is the validation step currently running (see [VALIDATION_STEPS]). */
    data class Validating(val step: Int) : InstallPhase
    data object Unreadable : InstallPhase
    data class Ready(
        val verification: WhatsAppApkVerifier.Verification,
        val staged: WhatsAppApkInstaller.StagedPackage
    ) : InstallPhase
    data class Installing(
        val verification: WhatsAppApkVerifier.Verification,
        val staged: WhatsAppApkInstaller.StagedPackage
    ) : InstallPhase
    data class Failed(
        val verification: WhatsAppApkVerifier.Verification,
        val staged: WhatsAppApkInstaller.StagedPackage,
        val message: String
    ) : InstallPhase
}

private const val VALIDATION_STEPS = 3

/**
 * Installs a file, whether it was downloaded inside WAEX or picked from storage. The file is
 * validated live as soon as the sheet opens (copy, read, verify package, version range and
 * WhatsApp's signature); only a file that passes can be installed, with root. The button says
 * Upgrade, Downgrade or Reinstall, worked out from the version found inside the file itself.
 *
 * [version] is only a hint for the title before the file has been read (a downloaded card knows it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkInstallSheet(
    packageName: String,
    appName: String,
    version: String?,
    source: Uri,
    installedVersion: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val scope = rememberCoroutineScope()
    val rules = remember(context) { UniversalVersionValidator.getBuiltinSupportedVersions(context) }
    var phase by remember(source) { mutableStateOf<InstallPhase>(InstallPhase.Validating(0)) }
    var attempt by remember(source) { mutableIntStateOf(0) }

    // The version inside the file wins; before it is read, the card's version (if any) is used.
    val fileVersion = when (val current = phase) {
        is InstallPhase.Ready -> current.verification.info.versionName
        is InstallPhase.Installing -> current.verification.info.versionName
        is InstallPhase.Failed -> current.verification.info.versionName
        else -> null
    }
    val shownVersion = fileVersion ?: version
    val change = remember(installedVersion, shownVersion) {
        if (shownVersion == null) VersionChange.INSTALL else VersionChange.of(installedVersion, shownVersion)
    }
    val busy = phase is InstallPhase.Validating || phase is InstallPhase.Installing

    // Never leave a staged copy of the file behind.
    DisposableEffect(source) { onDispose { WhatsAppApkInstaller.cleanup(context) } }

    // Real-time validation: each step reports as soon as it finishes.
    LaunchedEffect(source, attempt) {
        phase = InstallPhase.Validating(0)
        val staged = withContext(Dispatchers.IO) {
            runCatching { WhatsAppApkInstaller.stage(context, source) }.getOrNull()
        }
        if (staged == null) {
            phase = InstallPhase.Unreadable
            return@LaunchedEffect
        }
        phase = InstallPhase.Validating(1)
        val info = withContext(Dispatchers.IO) {
            runCatching { WhatsAppApkVerifier.inspect(context, staged.base) }.getOrNull()
        }
        if (info == null) {
            WhatsAppApkInstaller.cleanup(context)
            phase = InstallPhase.Unreadable
            return@LaunchedEffect
        }
        phase = InstallPhase.Validating(2)
        val verification = withContext(Dispatchers.IO) {
            val splits = staged.splits.map {
                WhatsAppApkVerifier.inspect(context, it, minBytes = WhatsAppApkVerifier.MIN_SPLIT_BYTES)
            }
            WhatsAppApkVerifier.evaluate(packageName, info, rules, splits = splits)
        }
        phase = InstallPhase.Ready(verification, staged)
    }

    fun install(verification: WhatsAppApkVerifier.Verification, staged: WhatsAppApkInstaller.StagedPackage) {
        phase = InstallPhase.Installing(verification, staged)
        scope.launch {
            val error = withContext(Dispatchers.IO) { WhatsAppApkInstaller.installWithRoot(staged.apks) }
            if (error == null) {
                Toast.makeText(
                    context,
                    UiText.get(R.string.waex_ui_verify_install_ok, "Installed WhatsApp %1\$s", verification.info.versionName.orEmpty()),
                    Toast.LENGTH_LONG
                ).show()
                onDismiss()
            } else {
                phase = InstallPhase.Failed(verification, staged, error)
            }
        }
    }

    WaexModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = WaexSheetHorizontalMargin)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            WaexSheetTitle(
                if (shownVersion != null) "${change.label()} $appName $shownVersion"
                else UiText.get(R.string.waex_ui_install_downloaded_apk, "Install from storage")
            )
            Text(
                text = when {
                    installedVersion.isNullOrBlank() ->
                        UiText.get(R.string.waex_ui_not_installed_on_this_device, "Not installed on this device")
                    shownVersion != null -> "v${installedVersion.removePrefix("v")}  →  $shownVersion"
                    else -> UiText.get(R.string.waex_ui_installed_v_1_s, "Installed: v%1\$s", installedVersion.removePrefix("v"))
                },
                style = typography.bodyMd,
                color = colors.onSurfaceVariant
            )

            when (val current = phase) {
                is InstallPhase.Validating -> ValidationProgress(current.step)

                InstallPhase.Unreadable -> {
                    Text(
                        text = UiText.get(
                            R.string.waex_ui_verify_read_failed,
                            "Could not read this file. Pick a WhatsApp .apk, .apkm or .xapk file."
                        ),
                        style = typography.bodyMd,
                        color = colors.error
                    )
                    WaexSheetPrimaryButton(
                        text = UiText.get(R.string.waex_ui_try_again, "Try again"),
                        onClick = { attempt++ }
                    )
                }

                is InstallPhase.Ready, is InstallPhase.Installing, is InstallPhase.Failed -> {
                    val verification = when (current) {
                        is InstallPhase.Ready -> current.verification
                        is InstallPhase.Installing -> current.verification
                        is InstallPhase.Failed -> current.verification
                        else -> error("unreachable")
                    }
                    val staged = when (current) {
                        is InstallPhase.Ready -> current.staged
                        is InstallPhase.Installing -> current.staged
                        is InstallPhase.Failed -> current.staged
                        else -> error("unreachable")
                    }
                    VerificationRows(verification)
                    Text(
                        text = if (verification.passed) {
                            UiText.get(R.string.waex_ui_verify_passed_hint, "All checks passed. WAEX will install this file with root.")
                        } else {
                            UiText.get(
                                R.string.waex_ui_verify_failed_hint,
                                "Rejected: this file failed verification and will not be installed. WAEX only installs official WhatsApp files."
                            )
                        },
                        style = typography.bodyMd,
                        fontWeight = FontWeight.SemiBold,
                        color = if (verification.passed) waexSheetAccent() else colors.error
                    )
                    if (current is InstallPhase.Failed) {
                        Text(
                            text = UiText.get(R.string.waex_ui_verify_install_failed, "Install failed: %1\$s", current.message),
                            style = typography.labelSm,
                            color = colors.error
                        )
                    }
                    if (current is InstallPhase.Installing) {
                        BusyRow(UiText.get(R.string.waex_ui_verify_installing, "Installing…"))
                    } else if (verification.passed) {
                        WaexSheetPrimaryButton(
                            text = if (current is InstallPhase.Failed) {
                                UiText.get(R.string.waex_ui_try_again, "Try again")
                            } else {
                                UiText.get(R.string.waex_ui_install_sheet_button, "%1\$s with root", change.label())
                            },
                            onClick = { install(verification, staged) }
                        )
                    }
                }
            }

            if (phase !is InstallPhase.Installing) {
                WaexSheetTextButton(
                    text = UiText.get(R.string.action_cancel, "Cancel"),
                    onClick = onDismiss,
                    enabled = phase !is InstallPhase.Validating
                )
            }
        }
    }
}

/** The validation steps as a checklist: finished steps tick, the running one spins. */
@Composable
private fun ValidationProgress(currentStep: Int) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val labels = listOf(
        UiText.get(R.string.waex_ui_validate_step_copy, "Reading the file"),
        UiText.get(R.string.waex_ui_validate_step_inspect, "Reading package details"),
        UiText.get(R.string.waex_ui_validate_step_verify, "Checking package, version and WhatsApp's signature")
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        labels.forEachIndexed { index, label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    index < currentStep -> Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = waexSheetAccent(),
                        modifier = Modifier.size(20.dp)
                    )
                    index == currentStep -> WaexCircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.primary
                    )
                    else -> Spacer(Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = label,
                    style = typography.bodyMd,
                    fontWeight = if (index == currentStep) FontWeight.Bold else FontWeight.Normal,
                    color = if (index <= currentStep) colors.onSurface else colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun VerificationRows(check: WhatsAppApkVerifier.Verification) {
    val info = check.info
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        VerifyCheckRow(
            label = UiText.get(R.string.waex_ui_verify_check_package, "Package"),
            ok = check.packageMatches,
            detail = if (check.packageMatches) info.packageName.orEmpty()
            else UiText.get(R.string.waex_ui_verify_check_package_bad, "This file is for a different app (%2\$s)", check.expectedPackage, info.packageName ?: "?")
        )
        VerifyCheckRow(
            label = UiText.get(R.string.waex_ui_verify_check_version, "Version"),
            ok = check.versionSupported,
            detail = if (check.versionSupported) UiText.get(R.string.waex_ui_verify_check_version_ok, "%1\$s is inside the supported range %2\$s", info.versionName.orEmpty(), check.matchedRule.orEmpty())
            else UiText.get(R.string.waex_ui_verify_check_version_bad, "%1\$s is outside every supported range", info.versionName ?: "?")
        )
        VerifyCheckRow(
            label = UiText.get(R.string.waex_ui_verify_check_signature, "Signature"),
            ok = check.signatureTrusted,
            detail = if (check.signatureTrusted) UiText.get(R.string.waex_ui_verify_check_signature_ok, "Signed by WhatsApp")
            else UiText.get(R.string.waex_ui_verify_check_signature_bad, "This file is not signed by WhatsApp. It may have been modified or repacked.")
        )
        if (check.splitCount > 0) {
            VerifyCheckRow(
                label = UiText.get(R.string.waex_ui_verify_check_splits, "Split APKs"),
                ok = check.splitsTrusted,
                detail = if (check.splitsTrusted) UiText.get(R.string.waex_ui_verify_check_splits_ok, "%1\$s included, same package and signer", check.splitCount.toString())
                else UiText.get(R.string.waex_ui_verify_check_splits_bad, "A split APK has the wrong package or signer")
            )
        }
    }
}
