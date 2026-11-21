package com.waenhancer.ui.screens.license

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waenhancer.app.R
import com.waenhancer.licensing.LicenseManager
import com.waenhancer.ui.components.WaexCircularProgressIndicator
import com.waenhancer.ui.components.WaexConfirmationBottomSheet
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.licensing.report.ReportLogin
import kotlin.concurrent.thread

private val ErrorRed = Color(0xFFF44336)

/**
 * License activation for a Telegram-logged-in install. There is no key field: the license is
 * resolved from the Telegram account, so one install cannot use another account's key.
 * Device limits are enforced by the server and surfaced here with a way to raise the limit.
 */
@Composable
fun TelegramLicenseActivation(
    login: ReportLogin.Login,
    onActivated: () -> Unit,
    onLoggedOut: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val context = LocalContext.current

    var verifying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }

    if (confirmLogout) {
        WaexConfirmationBottomSheet(
            title = stringResource(R.string.report_login_logout_title),
            message = stringResource(R.string.report_login_logout_message, login.label),
            confirmLabel = stringResource(R.string.report_login_logout),
            destructive = true,
            onConfirm = {
                confirmLogout = false
                thread { ReportLogin.logout(context) }
                onLoggedOut()
            },
            onDismiss = { confirmLogout = false }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.report_login_logged_in_as, login.label),
                style = typography.bodyMd,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                enabled = !verifying,
                onClick = { confirmLogout = true }
            ) {
                Text(stringResource(R.string.report_login_logout), color = colors.primary)
            }
        }
        Text(
            text = stringResource(R.string.license_tg_desc),
            style = typography.bodyMd,
            color = colors.onSurfaceVariant
        )

        limit?.let { (linked, max) ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.license_tg_limit_title, linked, max),
                    style = typography.bodyMd,
                    fontWeight = FontWeight.Bold,
                    color = ErrorRed
                )
                Text(
                    text = stringResource(R.string.license_tg_limit_desc),
                    style = typography.labelSm,
                    color = colors.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { runCatching { context.startActivity(ReportLogin.addDeviceSlotIntent()) } },
                    shape = radius.buttonShape,
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Text(stringResource(R.string.license_tg_limit_add), color = colors.primary, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (errorMessage.isNotEmpty()) {
            Text(
                text = errorMessage,
                style = typography.labelSm,
                color = ErrorRed,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        Button(
            onClick = {
                verifying = true
                errorMessage = ""
                limit = null
                LicenseManager.activateWithSession(
                    context,
                    object : LicenseManager.LicenseCallback {
                        override fun onSuccess(planName: String?, expiresAtStr: String?, tgUsername: String?) {
                            verifying = false
                            onActivated()
                        }

                        override fun onError(message: String?) {
                            verifying = false
                            errorMessage = message ?: context.getString(R.string.waex_ui_verification_failed)
                        }

                        override fun onDeviceLimitReached(linkedDevices: Int, deviceLimit: Int) {
                            verifying = false
                            limit = linkedDevices to deviceLimit
                            errorMessage = ""
                        }

                        override fun onLoginExpired() {
                            verifying = false
                            ReportLogin.clear(context)
                            onLoggedOut()
                        }
                    }
                )
            },
            enabled = !verifying,
            shape = radius.buttonShape,
            colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            if (verifying) {
                WaexCircularProgressIndicator(color = colors.onPrimary, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.waex_ui_verifying_with_server), style = typography.bodyMd.copy(fontWeight = FontWeight.Bold))
            } else {
                Text(stringResource(R.string.license_tg_use_my_license), style = typography.bodyMd.copy(fontWeight = FontWeight.Bold))
            }
        }
    }
}
