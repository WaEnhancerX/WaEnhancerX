package com.waenhancer.ui.screens.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.app.R
import com.waenhancer.ui.components.WaexCircularProgressIndicator
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.utils.TargetAppManager
import com.waenhancer.utils.UiText
import com.waenhancer.utils.UniversalVersionValidator
import com.waenhancer.utils.WhatsAppApkInstaller
import com.waenhancer.utils.WhatsAppApkVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Installs a WhatsApp APK, APKM or XAPK the user already downloaded. Every file is verified
 * (package, supported version range, WhatsApp's signing certificate) before it is installed with
 * root, and anything modified or repacked is rejected.
 */
@Composable
fun InstallFromStorageScreen(packageName: String) {
    val context = LocalContext.current
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val spacing = WaexTheme.spacing
    val scope = rememberCoroutineScope()

    val detectedApps by TargetAppManager.detectedApps.collectAsState()
    val app = detectedApps.firstOrNull { it.packageName == packageName }
    val rules = remember(context) { UniversalVersionValidator.getBuiltinSupportedVersions(context) }

    var state by remember { mutableStateOf<ApkCheckState>(ApkCheckState.Idle) }
    val busy = state is ApkCheckState.Checking || state is ApkCheckState.Installing

    // Never leave staged copies of a downloaded APK behind.
    DisposableEffect(Unit) { onDispose { WhatsAppApkInstaller.cleanup(context) } }
    // The install keeps running under root, so do not let the user leave mid-way.
    BackHandler(enabled = busy) {}

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        state = ApkCheckState.Checking
        scope.launch {
            state = withContext(Dispatchers.IO) {
                runCatching {
                    val staged = WhatsAppApkInstaller.stage(context, uri)
                    val info = WhatsAppApkVerifier.inspect(context, staged.base)
                    if (info == null) {
                        WhatsAppApkInstaller.cleanup(context)
                        ApkCheckState.Unreadable
                    } else {
                        val splits = staged.splits.map {
                            WhatsAppApkVerifier.inspect(context, it, minBytes = WhatsAppApkVerifier.MIN_SPLIT_BYTES)
                        }
                        ApkCheckState.Ready(
                            WhatsAppApkVerifier.evaluate(packageName, info, rules, splits = splits),
                            staged
                        )
                    }
                }.getOrElse {
                    WhatsAppApkInstaller.cleanup(context)
                    ApkCheckState.Unreadable
                }
            }
        }
    }

    fun install(check: WhatsAppApkVerifier.Verification, staged: WhatsAppApkInstaller.StagedPackage) {
        state = ApkCheckState.Installing(check, staged)
        scope.launch {
            val error = withContext(Dispatchers.IO) { WhatsAppApkInstaller.installWithRoot(staged.apks) }
            WhatsAppApkInstaller.cleanup(context)
            state = ApkCheckState.Idle
            Toast.makeText(
                context,
                if (error == null) UiText.get(R.string.waex_ui_verify_install_ok, "Installed WhatsApp %1\$s", check.info.versionName.orEmpty())
                else UiText.get(R.string.waex_ui_verify_install_failed, "Install failed: %1\$s", error),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = UiText.get(R.string.waex_ui_install_storage_title, "Install from storage"),
                onBackClick = { if (!busy) navController.popBack() }
            )
        },
        containerColor = colors.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = spacing.pageMargin, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. The strict-verification promise.
            item {
                InfoCard(borderColor = colors.success.copy(alpha = 0.55f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = null,
                            tint = colors.success,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = UiText.get(R.string.waex_ui_install_storage_official_title, "Official WhatsApp files only"),
                                style = typography.bodyMd,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface
                            )
                            Text(
                                text = UiText.get(
                                    R.string.waex_ui_install_storage_official_body,
                                    "WAEX strictly checks the signature of every file before installing. Only an official, unmodified WhatsApp file signed by WhatsApp is installed. Any modified, patched, cloned or repacked APK is rejected."
                                ),
                                style = typography.labelSm,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 2. Target app and accepted ranges.
            item {
                InfoCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionLabel(UiText.get(R.string.waex_ui_install_storage_target, "TARGET APP"))
                        Text(
                            text = app?.appName ?: packageName,
                            style = typography.bodyMd,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                        Text(
                            text = if (app?.isInstalled == true) UiText.get(R.string.waex_ui_installed_v_1_s, "Installed: v%1\$s", app.versionName.removePrefix("v"))
                            else UiText.get(R.string.waex_ui_not_installed_on_this_device, "Not installed on this device"),
                            style = typography.labelSm,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        SectionLabel(UiText.get(R.string.waex_ui_install_storage_ranges, "ACCEPTED VERSION RANGES"))
                        Text(
                            text = rules.joinToString("  ·  "),
                            style = typography.labelSm,
                            color = colors.onSurface
                        )
                    }
                }
            }

            // 3. How it works.
            item {
                InfoCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel(UiText.get(R.string.waex_ui_install_storage_steps_title, "HOW IT WORKS"))
                        listOf(
                            UiText.get(R.string.waex_ui_install_storage_step1, "Download the APK, APKM or XAPK in your browser from a source you trust."),
                            UiText.get(R.string.waex_ui_install_storage_step2, "Choose the file here."),
                            UiText.get(R.string.waex_ui_install_storage_step3, "WAEX verifies the package, the version range and WhatsApp's signature."),
                            UiText.get(R.string.waex_ui_install_storage_step4, "If everything matches, it installs with root. Upgrading and downgrading both work.")
                        ).forEachIndexed { index, step ->
                            Text(
                                text = "${index + 1}.  $step",
                                style = typography.labelSm,
                                color = colors.onSurface
                            )
                        }
                    }
                }
            }

            // 4. Action and result.
            item {
                when (val current = state) {
                    ApkCheckState.Idle -> ChooseButton(
                        text = UiText.get(R.string.waex_ui_install_storage_choose, "Choose APK, APKM or XAPK"),
                        onClick = { picker.launch(arrayOf("*/*")) }
                    )
                    ApkCheckState.Checking -> BusyRow(UiText.get(R.string.waex_ui_verifying_apk, "Verifying APK…"))
                    ApkCheckState.Unreadable -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = UiText.get(R.string.waex_ui_verify_read_failed, "Could not read this file. Pick a WhatsApp .apk, .apkm or .xapk file."),
                            style = typography.bodyMd,
                            color = colors.error
                        )
                        ChooseButton(
                            text = UiText.get(R.string.waex_ui_install_storage_choose_another, "Choose another file"),
                            onClick = { picker.launch(arrayOf("*/*")) }
                        )
                    }
                    is ApkCheckState.Ready, is ApkCheckState.Installing -> {
                        val check = (current as? ApkCheckState.Ready)?.verification
                            ?: (current as ApkCheckState.Installing).verification
                        val staged = (current as? ApkCheckState.Ready)?.staged
                            ?: (current as ApkCheckState.Installing).staged
                        VerificationResult(
                            check = check,
                            installing = current is ApkCheckState.Installing,
                            onInstall = { install(check, staged) },
                            onChooseAnother = {
                                WhatsAppApkInstaller.cleanup(context)
                                state = ApkCheckState.Idle
                                picker.launch(arrayOf("*/*"))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VerificationResult(
    check: WhatsAppApkVerifier.Verification,
    installing: Boolean,
    onInstall: () -> Unit,
    onChooseAnother: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val info = check.info

    InfoCard(borderColor = if (check.passed) colors.success.copy(alpha = 0.55f) else colors.error.copy(alpha = 0.6f)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel(UiText.get(R.string.waex_ui_verify_apk_title, "VERIFY APK"))
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
            Text(
                text = if (check.passed) UiText.get(R.string.waex_ui_verify_passed_hint, "All checks passed. WAEX will install this file with root.")
                else UiText.get(R.string.waex_ui_verify_failed_hint, "Rejected: this file failed verification and will not be installed. WAEX only installs official WhatsApp files."),
                style = typography.bodyMd,
                fontWeight = FontWeight.SemiBold,
                color = if (check.passed) colors.success else colors.error
            )
            if (installing) {
                BusyRow(UiText.get(R.string.waex_ui_verify_installing, "Installing…"))
            } else {
                if (check.passed) {
                    Button(
                        onClick = onInstall,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                        shape = radius.buttonShape,
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text(UiText.get(R.string.waex_ui_verify_install_root, "Install with root"), fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedButton(
                    onClick = onChooseAnother,
                    shape = radius.buttonShape,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(UiText.get(R.string.waex_ui_install_storage_choose_another, "Choose another file"), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ChooseButton(text: String, onClick: () -> Unit) {
    val colors = WaexTheme.colors
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
        shape = WaexTheme.radius.buttonShape,
        modifier = Modifier.fillMaxWidth().height(48.dp)
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BusyRow(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        WaexCircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = WaexTheme.colors.primary
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = text, style = WaexTheme.typography.bodyMd, color = WaexTheme.colors.onSurfaceVariant)
    }
}

@Composable
private fun InfoCard(
    borderColor: Color = WaexTheme.colors.outlineVariant,
    content: @Composable () -> Unit
) {
    Surface(
        shape = WaexTheme.radius.mdShape,
        color = WaexTheme.colors.surfaceDim,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = WaexTheme.typography.labelSm,
        color = WaexTheme.colors.primary,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
    )
}

@Composable
private fun VerifyCheckRow(label: String, ok: Boolean, detail: String) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            imageVector = if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Close,
            contentDescription = null,
            tint = if (ok) colors.success else colors.error,
            modifier = Modifier.size(20.dp)
        )
        Column {
            Text(text = label, style = typography.bodyMd, fontWeight = FontWeight.Bold, color = colors.onSurface)
            Text(text = detail, style = typography.labelSm, color = colors.onSurfaceVariant)
        }
    }
}

/** State of the install-from-storage flow. */
private sealed interface ApkCheckState {
    data object Idle : ApkCheckState
    data object Checking : ApkCheckState
    data object Unreadable : ApkCheckState
    data class Ready(val verification: WhatsAppApkVerifier.Verification, val staged: WhatsAppApkInstaller.StagedPackage) : ApkCheckState
    data class Installing(val verification: WhatsAppApkVerifier.Verification, val staged: WhatsAppApkInstaller.StagedPackage) : ApkCheckState
}
