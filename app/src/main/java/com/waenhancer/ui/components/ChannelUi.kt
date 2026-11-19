package com.waenhancer.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.waenhancer.app.R
import com.waenhancer.update.UpdateChannel
import com.waenhancer.utils.UiText

fun channelLabel(channel: UpdateChannel): String = when (channel) {
    UpdateChannel.STABLE -> UiText.get(R.string.waex_ui_channel_stable, "Stable")
    UpdateChannel.BETA -> UiText.get(R.string.waex_ui_channel_beta, "Beta")
    UpdateChannel.NIGHTLY -> UiText.get(R.string.waex_ui_channel_nightly, "Nightly")
}

fun channelDescription(channel: UpdateChannel): String = when (channel) {
    UpdateChannel.STABLE -> UiText.get(R.string.waex_ui_channel_stable_desc, "Fully tested releases only")
    UpdateChannel.BETA -> UiText.get(R.string.waex_ui_channel_beta_desc, "Early builds before they reach stable")
    UpdateChannel.NIGHTLY -> UiText.get(R.string.waex_ui_channel_nightly_desc, "Every automated build, untested and may break")
}

/** Confirmation shown before a downgrade, a pre-release install or a move to a less stable channel. */
@Composable
fun RiskConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(UiText.get(R.string.waex_ui_cancel, "Cancel")) } },
    )
}

/** Warning text for installing [target] over [installed], or null when no warning is needed. */
fun installRiskMessage(isDowngrade: Boolean, target: UpdateChannel, installed: UpdateChannel): String? {
    val parts = buildList {
        if (isDowngrade) add(UiText.get(R.string.waex_ui_install_risk_downgrade, "This replaces your installed version with an older one. Data written by the newer version may not be readable by the older one."))
        if (target != UpdateChannel.STABLE && target.stabilityRank < installed.stabilityRank) {
            add(UiText.get(R.string.waex_ui_install_risk_prerelease, "Beta and nightly builds are not release-tested and may contain bugs."))
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}
