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
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.components.WaexCard
import com.waenhancer.ui.components.WaexInfoBanner
import com.waenhancer.ui.components.WaexSectionHeader
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.licensing.LicenseManager
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.Screen

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
                                    style = typography.bodyLg.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        letterSpacing = 1.2.sp
                                    ),
                                    color = colors.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                            BasicTextField(
                                value = licenseKeyInput,
                                onValueChange = {
                                    licenseKeyInput = it.uppercase(java.util.Locale.US)
                                    errorMessage = ""
                                },
                                singleLine = true,
                                textStyle = typography.bodyLg.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 1.2.sp,
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
                        val trimmedKey = licenseKeyInput.trim().uppercase(java.util.Locale.US)
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
                        Text(text = "Verifying with Server…", style = typography.bodyLg.copy(fontWeight = FontWeight.Bold))
                    } else {
                        Text(text = "Verify & Activate License", style = typography.bodyLg.copy(fontWeight = FontWeight.Bold))
                    }
                }

                Surface(
                    shape = radius.mdShape,
                    color = colors.surfaceContainerLow,
                    border = BorderStroke(1.dp, colors.outlineVariant),
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
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
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
                            text = "Get or manage license on Telegram @waenhancerx_bot",
                            style = typography.bodyMd.copy(color = colors.primary, fontWeight = FontWeight.Medium),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // ─── PRO FEATURES EXPLORATION ACTION BUTTON ───
            Surface(
                shape = radius.lgShape,
                color = colors.primaryContainer.copy(alpha = 0.6f),
                border = BorderStroke(1.5.dp, colors.primary.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(radius.lgShape)
                    .clickable {
                        navController.navigateTo(Screen.ProUpgradePaywall)
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(radius.mdShape)
                                .background(colors.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = WaexIcons.Premium,
                                contentDescription = null,
                                tint = colors.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Explore All Pro Features",
                                style = typography.bodyLg.copy(fontWeight = FontWeight.Bold),
                                color = colors.onSurface
                            )
                            Text(
                                text = "Compare features & unlock full potential",
                                style = typography.labelSm,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = WaexIcons.ChevronRight,
                        contentDescription = "View Features",
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Benefits Description & Quick Pro Tool Launchers
            WaexSectionHeader(
                title = "Pro Capabilities",
                subtitle = "Tap any tool to launch or view settings directly"
            )
            WaexCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val proTools = listOf(
                        Triple("File Size Spoofer", "Bypass media upload limits up to 10GB", Screen.FileSizeSpooferPro),
                        Triple("Status Video Splitter", "Auto-slice long videos into status clips", Screen.StatusVideoSplitterPro),
                        Triple("Message Bomber", "Automated repeat message blasting engine", Screen.MessageBomberPro),
                        Triple("Automation & Tasker", "Tasker hooks, webhooks & auto-responder", Screen.AutomationTasker),
                        Triple("Per-Contact Privacy", "Individual stealth, anti-revoke & blue tick overrides", Screen.PerContactPrivacyList),
                        Triple("Theme & Custom Styles", "Deep customization & visual engine", Screen.StylesSettings)
                    )

                    proTools.forEachIndexed { index, (title, desc, destinationScreen) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(radius.smShape)
                                .clickable {
                                    navController.navigateTo(destinationScreen)
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (isActive) WaexIcons.Success else WaexIcons.Lock,
                                    contentDescription = null,
                                    tint = if (isActive) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        text = title,
                                        style = typography.bodyMd.copy(fontWeight = FontWeight.SemiBold),
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = desc,
                                        style = typography.labelSm,
                                        color = colors.onSurfaceVariant,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            Icon(
                                imageVector = WaexIcons.ChevronRight,
                                contentDescription = null,
                                tint = colors.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        if (index < proTools.lastIndex) {
                            androidx.compose.material3.HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
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
