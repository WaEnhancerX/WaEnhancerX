package com.waenhancer.ui.components

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.waenhancer.ui.components.WaexCircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.xposed.utils.AppRestartHelper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.platform.LocalContext
import com.waenhancer.licensing.LicenseManager
import java.util.Locale

@Composable
fun LicenseActivationModal(
    onDismiss: () -> Unit,
    onActivated: () -> Unit
) {
    val context = LocalContext.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var licenseKey by remember { mutableStateOf("") }
    var verifyState by remember { mutableStateOf("idle") } // "idle" | "verifying" | "success" | "error"
    var errorMessage by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.pageMargin)
    ) {
        // Handle
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(colors.outlineVariant)
                .align(Alignment.CenterHorizontally)
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(colors.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = WaexIcons.Lock,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = UiText.get(R.string.waex_ui_activate_pro_license, "Activate Pro License"),
                        style = typography.headlineMd,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    Text(
                        text = UiText.get(R.string.waex_ui_hardware_locked_feature_activation, "Hardware-locked feature activation"),
                        style = typography.labelSm,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceDim)
                    .border(1.dp, colors.outlineVariant, CircleShape)
            ) {
                Icon(
                    imageVector = WaexIcons.Clear,
                    contentDescription = UiText.get(R.string.waex_ui_close, "Close"),
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Key Input Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(radius.mdShape)
                .background(
                    when (verifyState) {
                        "error" -> Color(0xFFFFECEC)
                        "success" -> Color(0xFFE8F5E9)
                        else -> colors.surfaceDim
                    }
                )
                .border(
                    width = 1.dp,
                    color = when (verifyState) {
                        "error" -> Color(0xFFF44336)
                        "success" -> Color(0xFF4CAF50)
                        else -> colors.outlineVariant
                    },
                    shape = radius.mdShape
                )
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (licenseKey.isEmpty()) {
                Text(
                    text = "WAEX-XXXX-XXXX-XXXX",
                    style = typography.bodyLg.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        letterSpacing = 1.2.sp
                    ),
                    color = colors.onSurfaceVariant.copy(alpha = 0.45f)
                )
            }
            BasicTextField(
                value = licenseKey,
                onValueChange = { input ->
                    if (verifyState != "verifying") {
                        licenseKey = input.uppercase(Locale.US)
                        verifyState = "idle"
                        errorMessage = ""
                    }
                },
                enabled = verifyState != "verifying",
                singleLine = true,
                textStyle = typography.bodyLg.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (verifyState == "verifying") colors.onSurface.copy(alpha = 0.5f) else colors.onSurface,
                    letterSpacing = 1.2.sp
                ),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (verifyState == "error" && errorMessage.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = WaexIcons.Warning,
                    contentDescription = null,
                    tint = Color(0xFFF44336),
                    modifier = Modifier.size(14.dp).padding(top = 2.dp)
                )
                Text(
                    text = errorMessage,
                    style = typography.labelSm,
                    color = Color(0xFFF44336),
                    lineHeight = 16.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(18.dp))

        // Activation Button
        Button(
            onClick = {
                val trimmedKey = licenseKey.trim().uppercase(Locale.US)
                if (trimmedKey.isEmpty()) {
                    verifyState = "error"
                    errorMessage = UiText.get(R.string.waex_ui_please_enter_your_license_key, "Please enter your license key.")
                    return@Button
                }
                if (!LicenseManager.isValidLicensePattern(trimmedKey)) {
                    verifyState = "error"
                    errorMessage = UiText.get(R.string.waex_ui_invalid_key_format_expected_waex_xxxx, "Invalid key format. Expected: WAEX-XXXX-XXXX-XXXX")
                    return@Button
                }

                verifyState = "verifying"
                errorMessage = ""

                LicenseManager.verifyLicense(
                    context,
                    trimmedKey,
                    object : LicenseManager.LicenseCallback {
                        override fun onSuccess(planName: String?, expiresAtStr: String?, tgUsername: String?) {
                            verifyState = "success"
                            onActivated()
                            AppRestartHelper.restartWhatsAppAndModule(context)
                        }

                        override fun onError(message: String?) {
                            verifyState = "error"
                            errorMessage = message ?: UiText.get(R.string.waex_ui_verification_failed, "Verification failed.")
                        }
                    }
                )
            },
            enabled = verifyState != "verifying" && licenseKey.trim().isNotEmpty(),
            shape = radius.lgShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary,
                disabledContainerColor = colors.primary.copy(alpha = 0.5f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            if (verifyState == "verifying") {
                WaexCircularProgressIndicator(
                    color = colors.onPrimary,
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = UiText.get(R.string.waex_ui_verifying_with_server, "Verifying with Server…"), style = typography.bodyMd, fontWeight = FontWeight.Bold)
            } else if (verifyState == "success") {
                Icon(
                    imageVector = WaexIcons.Success,
                    contentDescription = null,
                    tint = colors.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = UiText.get(R.string.waex_ui_pro_license_activated, "Pro License Activated!"), style = typography.bodyMd, fontWeight = FontWeight.Bold)
            } else {
                Text(text = UiText.get(R.string.waex_ui_verify_activate, "Verify & Activate"), style = typography.bodyMd, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Telegram Bot Button
        Surface(
            shape = radius.mdShape,
            color = colors.surfaceDim,
            border = BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    try {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://t.me/waenhancerx_bot")
                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        context.startActivity(intent)
                    } catch (ignored: Exception) {}
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = WaexIcons.Info,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = UiText.get(R.string.waex_ui_get_or_manage_license_via_waenhancerx, "Get or manage license via @waenhancerx_bot"),
                    style = typography.bodyMd.copy(color = colors.primary, fontWeight = FontWeight.Medium),
                    fontSize = 13.sp
                )
            }
        }
    }
}
