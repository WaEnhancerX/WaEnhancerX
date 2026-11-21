package com.waenhancer.ui.screens.dashboard

import com.waenhancer.ui.components.WaexSheetTitle

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waenhancer.app.R
import com.waenhancer.licensing.report.ReportLogin
import com.waenhancer.licensing.report.TelegramLoginFlow
import com.waenhancer.ui.components.WaexCircularProgressIndicator
import com.waenhancer.ui.components.WaexModalBottomSheet
import com.waenhancer.ui.components.WaexSheetHorizontalMargin
import com.waenhancer.ui.components.WaexSheetPrimaryButton
import com.waenhancer.ui.components.WaexSheetTextButton
import com.waenhancer.ui.designsystem.WaexTheme


/** Shown before the report sheet when nobody is logged in to Telegram yet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramLoginSheet(
    onLoggedIn: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var token by remember { mutableStateOf<String?>(null) }
    var waiting by remember { mutableStateOf(false) }

    // Wait while the user confirms inside Telegram.
    LaunchedEffect(token) {
        val current = token ?: return@LaunchedEffect
        when (val outcome = TelegramLoginFlow.await(context, current)) {
            is TelegramLoginFlow.Outcome.LoggedIn -> onLoggedIn(outcome.label)
            is TelegramLoginFlow.Outcome.LicenseConflict -> {
                waiting = false
                token = null
                Toast.makeText(
                    context,
                    context.getString(R.string.report_login_license_conflict, "@" + outcome.licenseUsername),
                    Toast.LENGTH_LONG
                ).show()
            }
            TelegramLoginFlow.Outcome.TimedOut -> {
                waiting = false
                token = null
                Toast.makeText(context, R.string.report_login_timeout, Toast.LENGTH_LONG).show()
            }
        }
    }

    WaexModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = WaexSheetHorizontalMargin)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            WaexSheetTitle(stringResource(R.string.report_login_title))
            Text(
                text = stringResource(R.string.report_login_desc),
                style = typography.bodyMd,
                color = colors.onSurfaceVariant
            )
            if (waiting) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WaexCircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.report_login_waiting),
                        style = typography.bodySm,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            WaexSheetPrimaryButton(
                text = stringResource(if (waiting) R.string.report_login_reopen else R.string.report_login_button),
                onClick = {
                    val next = token ?: ReportLogin.newToken()
                    if (openTelegram(context, next)) {
                        token = next
                        waiting = true
                    }
                }
            )
            WaexSheetTextButton(
                text = stringResource(R.string.report_issue_cancel),
                onClick = onDismiss
            )
        }
    }
}

private fun openTelegram(context: Context, token: String): Boolean {
    val opened = runCatching { context.startActivity(ReportLogin.loginIntent(token)) }.isSuccess
    if (!opened) Toast.makeText(context, R.string.report_issue_failed, Toast.LENGTH_LONG).show()
    return opened
}
