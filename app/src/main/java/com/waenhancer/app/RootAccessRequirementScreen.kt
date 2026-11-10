package com.waenhancer.app

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.components.WaexCircularProgressIndicator

enum class RootAccessState { Checking, Required, Granted }

@Composable
fun RootAccessRequirementScreen(
    state: RootAccessState,
    onRetry: () -> Unit,
    onExit: () -> Unit
) {
    BackHandler(onBack = onExit)
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val spacing = WaexTheme.spacing
    val checking = state == RootAccessState.Checking

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.pageMargin, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(1f, fill = false))

        Box(
            modifier = Modifier
                .size(72.dp)
                .background(colors.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (checking) {
                WaexCircularProgressIndicator(
                    modifier = Modifier.size(34.dp),
                    color = colors.primary,
                    strokeWidth = 3.dp
                )
            } else {
                Icon(
                    imageVector = WaexIcons.Security,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = if (checking) UiText.get(R.string.waex_ui_checking_root_access, "Checking root access") else UiText.get(R.string.waex_ui_root_access_is_required, "Root access is required"),
            style = typography.displayLgMobile,
            fontWeight = FontWeight.Bold,
            color = colors.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = if (checking) {
                UiText.get(R.string.waex_ui_approve_the_superuser_request_if_your, "Approve the superuser request if your root manager asks for permission.")
            } else {
                UiText.get(R.string.waex_ui_waenhancerx_supports_rooted_devices_wi, "WaEnhancerX supports rooted devices with an approved Xposed framework only. The app cannot continue until root access is available.")
            },
            style = typography.bodyMd,
            color = colors.onSurfaceVariant,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = radius.bentoCardShape,
            color = colors.surfaceDim,
            border = BorderStroke(1.dp, colors.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                RequirementRow(
                    title = UiText.get(R.string.waex_ui_why_this_is_required, "Why this is required"),
                    description = UiText.get(R.string.waex_ui_root_lets_waenhancerx_verify_the_modul, "Root lets WAEnhancerX verify the module scope and supported runtime environment. Non-root injection methods such as LSPatch and Shizuku-based alternatives are not supported.")
                )
                RequirementRow(
                    title = UiText.get(R.string.waex_ui_account_protection, "Account protection"),
                    description = UiText.get(R.string.waex_ui_unsupported_setups_may_behave_unpredic, "Unsupported setups may behave unpredictably and may increase the risk of WhatsApp restrictions. Root access does not remove every risk of using modifications.")
                )
                RequirementRow(
                    title = UiText.get(R.string.waex_ui_your_privacy, "Your privacy"),
                    description = UiText.get(R.string.waex_ui_this_check_runs_locally_using_the_syst, "This check runs locally using the system identity command. No root details or command output are uploaded.")
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = onRetry,
            enabled = !checking,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = radius.lgShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary
            )
        ) {
            Icon(
                imageVector = WaexIcons.Refresh,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = if (checking) UiText.get(R.string.diag_status_checking, "Checking…") else UiText.get(R.string.waex_ui_check_again, "Check again"),
                modifier = Modifier.padding(start = 8.dp),
                style = typography.bodyLg,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = onExit,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = radius.lgShape,
            border = BorderStroke(1.dp, colors.outlineVariant)
        ) {
            Text(
                text = UiText.get(R.string.waex_ui_exit_waenhancerx, "Exit WaEnhancerX"),
                style = typography.bodyMd,
                color = colors.onSurface
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun RequirementRow(title: String, description: String) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = WaexIcons.Success,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = typography.bodyMd,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Text(
                text = description,
                modifier = Modifier.padding(top = 3.dp),
                style = typography.bodyMd,
                color = colors.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}
