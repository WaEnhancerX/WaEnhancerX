package com.waenhancer.ui.screens.license

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.components.WaexCard
import com.waenhancer.ui.components.WaexInfoBanner
import com.waenhancer.ui.components.WaexSectionHeader
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.licensing.LicenseManager
import com.waenhancer.ui.navigation.LocalWaexNavController

@Composable
fun LicenseActivationScreen() {
    val navController = LocalWaexNavController.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var proStatus by remember { mutableStateOf(LicenseManager.getProStatus(context)) }
    var planName by remember { mutableStateOf(LicenseManager.getProPlanName(context)) }
    var licenseKeyInput by remember { mutableStateOf("") }
    var isVerifying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showUnlinkDialog by remember { mutableStateOf(false) }


    val isActive = "ACTIVE".equals(proStatus, ignoreCase = true)
    val isExpired = "EXPIRED".equals(proStatus, ignoreCase = true)

    val prefs = context.getSharedPreferences("com.waenhancer_preferences", android.content.Context.MODE_PRIVATE)
    val maskedKey = remember(proStatus) {
        val storedKey = prefs.getString("license_key", "") ?: ""
        if (storedKey.length > 4) {
            val end = storedKey.takeLast(4)
            "****-****-****-$end"
        } else storedKey
    }
    val tgUser = remember(proStatus) { prefs.getString("tg_username", "") ?: "" }
    val expiresAt = remember(proStatus) { prefs.getLong("expires_at", 0L) }
    val linkedDevicesCount = remember(proStatus) { prefs.getInt("linked_devices_count", 1) }
    val deviceLimit = remember(proStatus) { prefs.getInt("device_limit", 2) }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = "License & Plan",
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
                .padding(spacing.pageMargin),
            verticalArrangement = Arrangement.spacedBy(spacing.stackLg)
        ) {
            if (isActive) {
                // ─── ACTIVE PLAN CARD ───
                WaexSectionHeader(
                    title = "Active Subscription",
                    subtitle = "Your Pro license is active and hardware-locked."
                )

                WaexCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Status", style = typography.bodyMd, color = colors.onSurfaceVariant)
                            Text(text = "Active Pro", style = typography.bodyLg, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                        }
                        androidx.compose.material3.HorizontalDivider(color = colors.outlineVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Plan", style = typography.bodyMd, color = colors.onSurfaceVariant)
                            Text(text = planName.ifEmpty { "Lifetime Pro" }, style = typography.bodyMd, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Expires", style = typography.bodyMd, color = colors.onSurfaceVariant)
                            val expiryText = if (expiresAt > 0) {
                                val sdf = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())
                                sdf.format(java.util.Date(expiresAt))
                            } else "Lifetime Access"
                            Text(text = expiryText, style = typography.bodyMd, color = colors.onSurface)
                        }
                        if (tgUser.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "Telegram Account", style = typography.bodyMd, color = colors.onSurfaceVariant)
                                Text(text = "@$tgUser", style = typography.bodyMd, fontWeight = FontWeight.Medium, color = colors.primary)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "License Key", style = typography.bodyMd, color = colors.onSurfaceVariant)
                            Text(text = maskedKey, style = typography.bodyMd, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = colors.onSurface)
                        }
                    }
                }

                // Action Buttons: Connected Devices / Unlink
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { showUnlinkDialog = true },
                        shape = radius.buttonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF44336).copy(alpha = 0.12f),
                            contentColor = Color(0xFFF44336)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text(text = "Unlink Device", fontWeight = FontWeight.SemiBold)
                    }
                }

            } else {
                // ─── FREE / EXPIRED ACTIVATION FORM ───
                WaexSectionHeader(
                    title = if (isExpired) "License Expired" else "Activate License Key",
                    subtitle = if (isExpired) "Your previous license has expired. Enter a new key to renew." else "Input your purchased key to verify your license."
                )

                if (isExpired) {
                    WaexInfoBanner(
                        message = "Your license period has ended. Pro features are currently locked.",
                        bannerType = com.waenhancer.ui.components.BannerType.ERROR,
                        title = "Subscription Expired"
                    )
                }

                // Key Input Box
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = radius.mdShape,
                    color = colors.surfaceContainerLow,
                    border = BorderStroke(1.dp, if (errorMessage.isNotEmpty()) Color(0xFFF44336) else if (licenseKeyInput.isNotEmpty()) colors.primary else colors.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = WaexIcons.Lock,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(spacing.stackSm))
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (licenseKeyInput.isEmpty()) {
                                Text(
                                    text = "WAEX-XXXX-XXXX-XXXX",
                                    style = typography.bodyLg,
                                    color = colors.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                            BasicTextField(
                                value = licenseKeyInput,
                                onValueChange = {
                                    licenseKeyInput = it
                                    errorMessage = ""
                                },
                                singleLine = true,
                                textStyle = typography.bodyLg.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = colors.onSurface
                                ),
                                cursorBrush = SolidColor(colors.primary),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        style = typography.labelSm,
                        color = Color(0xFFF44336),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                // CTA Button
                Button(
                    onClick = {
                        val trimmedKey = licenseKeyInput.trim()
                        if (!LicenseManager.isValidLicensePattern(trimmedKey)) {
                            errorMessage = "Invalid key format. Expected: WAEX-XXXX-XXXX-XXXX"
                            return@Button
                        }
                        isVerifying = true
                        errorMessage = ""

                        LicenseManager.verifyLicense(
                            context,
                            trimmedKey,
                            object : LicenseManager.LicenseCallback {
                                override fun onSuccess(resPlanName: String?, expiresAtStr: String?, tgUsername: String?) {
                                    isVerifying = false
                                    proStatus = "ACTIVE"
                                    planName = LicenseManager.getProPlanName(context)
                                }

                                override fun onError(msg: String?) {
                                    isVerifying = false
                                    errorMessage = msg ?: "Verification failed."
                                }
                            }
                        )
                    },
                    shape = radius.buttonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = licenseKeyInput.isNotBlank() && !isVerifying
                ) {
                    if (isVerifying) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = colors.onPrimary,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Verifying…", style = typography.bodyLg.copy(fontWeight = FontWeight.Bold))
                    } else {
                        Text(text = "Verify & Activate License", style = typography.bodyLg.copy(fontWeight = FontWeight.Bold))
                    }
                }

                Text(
                    text = "Get your key from Telegram Bot @waenhancerx_bot",
                    style = typography.bodyMd.copy(color = colors.primary, fontWeight = FontWeight.Medium),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            try {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://t.me/waenhancerx_bot")
                                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                                context.startActivity(intent)
                            } catch (ignored: Exception) {}
                        }
                        .padding(vertical = 4.dp)
                )
            }

            // Benefits Description
            WaexSectionHeader(title = "Verified Plan Benefits")
            WaexCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val benefits = listOf(
                        "File Size Spoofer: Up to 500MB media bypass" to true,
                        "Status Video Splitter: Unlimited video cuts" to true,
                        "Message Bomber: Automated burst messages active" to true,
                        "Voice Note Transcription: AI engine translation" to true,
                        "24/7 Priority Support access channel" to true
                    )

                    benefits.forEachIndexed { index, (benefit, active) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = WaexIcons.Success,
                                contentDescription = null,
                                tint = if (isActive) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(spacing.stackSm))
                            Text(
                                text = benefit,
                                style = typography.bodyMd,
                                color = if (isActive) colors.onSurface else colors.onSurfaceVariant
                            )
                        }
                        if (index < benefits.lastIndex) {
                            androidx.compose.material3.HorizontalDivider(color = colors.outlineVariant)
                        }
                    }
                }
            }
        }
    }

    if (showUnlinkDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showUnlinkDialog = false },
            title = { Text(text = "Unlink Device", fontWeight = FontWeight.Bold) },
            text = { Text(text = "This will unlink your device from this license key. You can re-link it later or use a different key.\n\nWhatsApp and WAEX will restart after unlinking.") },
            confirmButton = {
                Button(
                    onClick = {
                        showUnlinkDialog = false
                        LicenseManager.unlinkDevice(
                            context,
                            object : LicenseManager.UnlinkCallback {
                                override fun onSuccess() {
                                    proStatus = "FREE"
                                    planName = "Free"
                                }

                                override fun onError(msg: String?) {
                                    errorMessage = msg ?: "Unlink failed."
                                }
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                ) {
                    Text(text = "Confirm Unlink")
                }
            },

            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showUnlinkDialog = false }) {
                    Text(text = "Cancel")
                }
            }
        )
    }
}
