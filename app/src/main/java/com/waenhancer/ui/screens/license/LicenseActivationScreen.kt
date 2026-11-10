package com.waenhancer.ui.screens.license

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import android.widget.Toast
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
import com.waenhancer.ui.components.WaexCircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import com.waenhancer.ui.components.WaexConfirmationBottomSheet
import com.waenhancer.ui.components.WaexInfoBanner
import com.waenhancer.ui.components.WaexSectionHeader
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.components.NetworkErrorCenter
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import com.waenhancer.licensing.LicenseManager
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.Screen
import com.waenhancer.xposed.utils.AppRestartHelper
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import com.waenhancer.ui.components.BannerType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PurchasePlan(
    val id: Int = 0,
    val name: String,
    val originalPrice: String?,
    val offerPrice: String,
    val period: String = "",
    val desc: String = "",
    val badge: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseActivationScreen() {
    val navController = LocalWaexNavController.current
    val context = LocalContext.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var proStatus by remember { mutableStateOf(LicenseManager.getProStatus(context)) }
    var planName by remember { mutableStateOf(LicenseManager.getProPlanName(context)) }
    var licenseKeyInput by remember { mutableStateOf("") }
    var isVerifying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showRemoveLicenseDialog by remember { mutableStateOf(false) }
    var isRemovingLicense by remember { mutableStateOf(false) }
    var removeLicenseError by remember { mutableStateOf("") }

    val fallbackPlans = remember {
        listOf(
            PurchasePlan(
                id = 2,
                name = UiText.get(R.string.waex_ui_pro_monthly, "Pro Monthly"),
                originalPrice = "3.50",
                offerPrice = "2.30",
                period = UiText.get(R.string.waex_ui_month, "/ Month"),
                desc = UiText.get(R.string.waex_ui_full_access_to_all_pro_features_for_30, "Full access to all Pro features for 30 days")
            ),
            PurchasePlan(
                id = 3,
                name = UiText.get(R.string.waex_ui_pro_yearly, "Pro Yearly"),
                originalPrice = "28.50",
                offerPrice = "18.99",
                period = UiText.get(R.string.waex_ui_year, "/ Year"),
                desc = UiText.get(R.string.waex_ui_save_33_with_full_pro_access_for_365_d, "Save 33% with full Pro access for 365 days"),
                badge = UiText.get(R.string.waex_ui_best_value, "Best Value")
            )
        )
    }

    var plans by remember { mutableStateOf<List<PurchasePlan>>(emptyList()) }
    var isLoadingPlans by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val cachePrefs = context.getSharedPreferences("waex_plans_cache", Context.MODE_PRIVATE)
        val cacheTime = cachePrefs.getLong("plans_cache_time", 0L)
        val cachedData = cachePrefs.getString("plans_cache_data", null)
        val currentTime = System.currentTimeMillis()

        if (!cachedData.isNullOrBlank() && (currentTime - cacheTime) < 3600000L) { // 1 hour TTL
            try {
                val parsed = parsePlansJson(cachedData)
                if (parsed.isNotEmpty()) {
                    plans = parsed
                    isLoadingPlans = false
                    return@LaunchedEffect
                }
            } catch (_: Exception) {}
        }

        // Fetch from API
        withContext(Dispatchers.IO) {
            var urlConnection: HttpURLConnection? = null
            try {
                val url = URL("https://waex.mubashar.dev/api/v1/plans")
                urlConnection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty(UiText.get(R.string.waex_ui_accept, "Accept"), "application/json")
                }

                val inputStream = BufferedInputStream(urlConnection.inputStream)
                val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }

                val responseStr = sb.toString()
                val parsed = parsePlansJson(responseStr)

                if (parsed.isNotEmpty()) {
                    cachePrefs.edit()
                        .putString("plans_cache_data", responseStr)
                        .putLong("plans_cache_time", System.currentTimeMillis())
                        .apply()

                    withContext(Dispatchers.Main) {
                        plans = parsed
                        isLoadingPlans = false
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        plans = fallbackPlans
                        isLoadingPlans = false
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (plans.isEmpty()) {
                        plans = fallbackPlans
                    }
                    isLoadingPlans = false
                }
            } finally {
                urlConnection?.disconnect()
            }
        }
    }


    val isActive = "ACTIVE".equals(proStatus, ignoreCase = true)
    val isExpired = "EXPIRED".equals(proStatus, ignoreCase = true)

    val prefs = context.getSharedPreferences("com.waenhancer_preferences", Context.MODE_PRIVATE)
    val maskedKey = remember(proStatus) {
        val storedKey = prefs.getString("license_key", "") ?: ""
        if (storedKey.length > 4) {
            val end = storedKey.takeLast(4)
            "****-****-****-$end"
        } else storedKey
    }
    val tgUser = remember(proStatus) { prefs.getString("tg_username", "") ?: "" }
    val ghUser = remember(proStatus) {
        val raw = prefs.getString("gh_username", "") ?: ""
        if (raw.isNotBlank() && !raw.equals("null", ignoreCase = true)) raw else ""
    }
    val expiresAt = remember(proStatus) { prefs.getLong("expires_at", 0L) }
    val linkedDevicesCount = remember(proStatus) { prefs.getInt("linked_devices_count", 1) }
    val deviceLimit = remember(proStatus) { prefs.getInt("device_limit", 2) }

    if (showRemoveLicenseDialog) {
        WaexConfirmationBottomSheet(
            title = UiText.get(R.string.waex_ui_remove_license_confirmation, "Remove License?"),
            message = UiText.get(R.string.waex_ui_this_will_unlink_this_device_remove_th, "This will unlink this device, remove the saved license, and return WAEX to the Free tier."),
            confirmLabel = UiText.get(R.string.waex_ui_remove_license_action, "Remove License"),
            destructive = true,
            confirmEnabled = !isRemovingLicense,
            onDismiss = {
                if (!isRemovingLicense) {
                    showRemoveLicenseDialog = false
                    removeLicenseError = ""
                }
            },
            onConfirm = {
                isRemovingLicense = true
                removeLicenseError = ""
                LicenseManager.unlinkDevice(
                    context,
                    object : LicenseManager.UnlinkCallback {
                        override fun onSuccess() {
                            isRemovingLicense = false
                            showRemoveLicenseDialog = false
                            proStatus = LicenseManager.getProStatus(context)
                            planName = LicenseManager.getProPlanName(context)
                            Toast.makeText(context, UiText.get(R.string.waex_ui_license_removed_from_this_device, "License removed from this device."), Toast.LENGTH_LONG).show()
                            AppRestartHelper.restartWhatsAppAndModule(context)
                        }

                        override fun onError(msg: String?) {
                            isRemovingLicense = false
                            if (NetworkErrorCenter.isNetworkError(msg)) {
                                removeLicenseError = ""
                                NetworkErrorCenter.show(msg)
                            } else {
                                removeLicenseError = msg ?: UiText.get(R.string.waex_ui_unable_to_remove_the_license_please_tr, "Unable to remove the license. Please try again.")
                            }
                        }
                    }
                )
            },
            supportingContent = {
                if (removeLicenseError.isNotEmpty()) {
                    Text(
                        text = removeLicenseError,
                        style = typography.labelSm,
                        color = colors.error
                    )
                }
            },
            confirmContent = {
                if (isRemovingLicense) {
                    WaexCircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(UiText.get(R.string.waex_ui_removing, "Removing…"))
                } else {
                    Text(UiText.get(R.string.waex_ui_remove_license_action, "Remove License"), fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = UiText.get(R.string.title_license_plan, "License & Plan"),
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
                    title = UiText.get(R.string.waex_ui_active_subscription, "Active Subscription"),
                    subtitle = UiText.get(R.string.waex_ui_your_pro_license_is_active_and_hardwar, "Your Pro license is active and hardware-locked.")
                )

                WaexCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = UiText.get(R.string.waex_ui_status, "Status"), style = typography.bodyMd, color = colors.onSurfaceVariant)
                            Text(text = UiText.get(R.string.waex_ui_active_pro, "Active Pro"), style = typography.bodyLg, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                        }
                        HorizontalDivider(color = colors.outlineVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = UiText.get(R.string.waex_ui_plan, "Plan"), style = typography.bodyMd, color = colors.onSurfaceVariant)
                            Text(text = planName.ifEmpty { UiText.get(R.string.waex_ui_lifetime_pro, "Lifetime Pro") }, style = typography.bodyMd, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = UiText.get(R.string.waex_ui_expires, "Expires"), style = typography.bodyMd, color = colors.onSurfaceVariant)
                            val expiryText = if (expiresAt > 0) {
                                val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                                sdf.format(Date(expiresAt))
                            } else UiText.get(R.string.waex_ui_lifetime_access, "Lifetime Access")
                            Text(text = expiryText, style = typography.bodyMd, color = colors.onSurface)
                        }
                        if (tgUser.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = UiText.get(R.string.waex_ui_telegram_account, "Telegram Account"), style = typography.bodyMd, color = colors.onSurfaceVariant)
                                Text(text = "@$tgUser", style = typography.bodyMd, fontWeight = FontWeight.Medium, color = colors.primary)
                            }
                        }
                        if (ghUser.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = UiText.get(R.string.waex_ui_github_account, "GitHub Account"), style = typography.bodyMd, color = colors.onSurfaceVariant)
                                Text(text = "@$ghUser", style = typography.bodyMd, fontWeight = FontWeight.Medium, color = colors.primary)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = UiText.get(R.string.waex_ui_license_key, "License Key"), style = typography.bodyMd, color = colors.onSurfaceVariant)
                            Text(text = maskedKey, style = typography.bodyMd, fontFamily = FontFamily.Monospace, color = colors.onSurface)
                        }
                    }
                }

                // Action Button: Connected Devices
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { navController.navigateTo(Screen.ManageDevices) },
                        shape = radius.buttonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primary,
                            contentColor = colors.onPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(
                            imageVector = WaexIcons.Install,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = UiText.get(R.string.waex_ui_manage_connected_devices, "Manage Connected Devices"),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        removeLicenseError = ""
                        showRemoveLicenseDialog = true
                    },
                    shape = radius.buttonShape,
                    border = BorderStroke(1.dp, Color(0xFFF44336)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF44336)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = WaexIcons.Clear,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = UiText.get(R.string.waex_ui_remove_license_action, "Remove License"), fontWeight = FontWeight.SemiBold)
                }

            } else {
                // ─── FREE / EXPIRED ACTIVATION FORM ───
                WaexSectionHeader(
                    title = if (isExpired) UiText.get(R.string.waex_ui_license_expired, "License Expired") else UiText.get(R.string.waex_ui_activate_license_key, "Activate License Key"),
                    subtitle = if (isExpired) UiText.get(R.string.waex_ui_your_previous_license_has_expired_ente, "Your previous license has expired. Enter a new key to renew.") else UiText.get(R.string.waex_ui_input_your_purchased_key_to_verify_you, "Input your purchased key to verify your license.")
                )

                if (isExpired) {
                    WaexInfoBanner(
                        message = UiText.get(R.string.waex_ui_your_license_period_has_ended_pro_feat, "Your license period has ended. Pro features are currently locked."),
                        bannerType = BannerType.ERROR,
                        title = UiText.get(R.string.waex_ui_subscription_expired, "Subscription Expired")
                    )
                }

                // ─── COMPACT ACTIVATION CARD ───
                WaexCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            text = UiText.get(R.string.waex_ui_enter_your_license_key_to_unlock_all_p, "Enter your license key to unlock all premium features."),
                            style = typography.bodyMd,
                            color = colors.onSurfaceVariant
                        )

                        // Key Input Box
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = radius.smShape,
                            color = if (isVerifying) colors.surfaceContainerLow.copy(alpha = 0.5f) else colors.surfaceContainerLow,
                            border = BorderStroke(
                                1.dp,
                                if (errorMessage.isNotEmpty()) Color(0xFFF44336)
                                else if (isVerifying) colors.outlineVariant.copy(alpha = 0.5f)
                                else if (licenseKeyInput.isNotEmpty()) colors.primary
                                else colors.outlineVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = WaexIcons.Lock,
                                    contentDescription = null,
                                    tint = if (isVerifying) colors.onSurfaceVariant.copy(alpha = 0.38f)
                                    else if (licenseKeyInput.isNotEmpty()) colors.primary
                                    else colors.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (licenseKeyInput.isEmpty()) {
                                        Text(
                                            text = "WAEX-XXXX-XXXX-XXXX",
                                            style = typography.bodyMd.copy(
                                                fontFamily = FontFamily.Monospace,
                                                letterSpacing = 1.sp
                                            ),
                                            color = colors.onSurfaceVariant.copy(alpha = 0.45f)
                                        )
                                    }
                                    BasicTextField(
                                        value = licenseKeyInput,
                                        onValueChange = {
                                            if (!isVerifying) {
                                                licenseKeyInput = it.uppercase(Locale.US)
                                                errorMessage = ""
                                            }
                                        },
                                        enabled = !isVerifying,
                                        singleLine = true,
                                        textStyle = typography.bodyMd.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            letterSpacing = 1.sp,
                                            color = if (isVerifying) colors.onSurface.copy(alpha = 0.5f) else colors.onSurface
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

                        // Verify Button
                        Button(
                            onClick = {
                                val trimmedKey = licenseKeyInput.trim().uppercase(Locale.US)
                                if (!LicenseManager.isValidLicensePattern(trimmedKey)) {
                                    errorMessage = UiText.get(R.string.waex_ui_invalid_key_format_expected_waex_xxxx, "Invalid key format. Expected: WAEX-XXXX-XXXX-XXXX")
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
                                            AppRestartHelper.restartWhatsAppAndModule(context)
                                        }

                                        override fun onError(msg: String?) {
                                            isVerifying = false
                                            if (NetworkErrorCenter.isNetworkError(msg)) {
                                                errorMessage = ""
                                                NetworkErrorCenter.show(msg)
                                            } else {
                                                errorMessage = msg ?: UiText.get(R.string.waex_ui_verification_failed, "Verification failed.")
                                            }
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
                                .height(48.dp),
                            enabled = licenseKeyInput.isNotBlank() && !isVerifying
                        ) {
                            if (isVerifying) {
                                WaexCircularProgressIndicator(
                                    color = colors.onPrimary,
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = UiText.get(R.string.waex_ui_verifying_with_server, "Verifying with Server…"),
                                    style = typography.bodyMd.copy(fontWeight = FontWeight.Bold)
                                )
                            } else {
                                Text(
                                    text = UiText.get(R.string.waex_ui_verify_activate, "Verify & Activate"),
                                    style = typography.bodyMd.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }

                // ─── PURCHASE LICENSE PLANS SECTION ───
                WaexSectionHeader(
                    title = UiText.get(R.string.waex_ui_purchase_license_key, "Purchase License Key"),
                    subtitle = UiText.get(R.string.waex_ui_instant_automated_delivery_via_telegra, "Instant automated delivery via Telegram bot")
                )

                if (isLoadingPlans && plans.isEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(2) {
                            Surface(
                                shape = radius.bentoCardShape,
                                color = colors.surfaceDim,
                                border = BorderStroke(1.dp, colors.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(width = 120.dp, height = 18.dp)
                                            .clip(radius.smShape)
                                            .background(colors.outlineVariant)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(width = 80.dp, height = 24.dp)
                                            .clip(radius.smShape)
                                            .background(colors.outlineVariant)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(0.7f)
                                            .height(14.dp)
                                            .clip(radius.smShape)
                                            .background(colors.outlineVariant)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        plans.forEach { plan ->
                            Surface(
                                shape = radius.bentoCardShape,
                                color = colors.surfaceDim,
                                border = BorderStroke(1.dp, colors.outlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(radius.bentoCardShape)
                                    .clickable {
                                        try {
                                            val intent = Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("https://t.me/waenhancerx_bot?start=subscribe")
                                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                            context.startActivity(intent)
                                        } catch (ignored: Exception) {}
                                    }
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    // Top Row: Plan Name + Badge
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = plan.name,
                                            style = typography.bodyLg,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.onSurface
                                        )

                                        if (plan.badge != null && plan.badge.isNotBlank()) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(radius.fullShape)
                                                    .background(colors.primary)
                                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = plan.badge.uppercase(Locale.US),
                                                    style = typography.labelSm.copy(fontSize = 10.sp),
                                                    fontWeight = FontWeight.Bold,
                                                    color = colors.onPrimary
                                                )
                                            }
                                        }
                                    }

                                    // Price Row
                                    Row(
                                        verticalAlignment = Alignment.Bottom,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (plan.originalPrice != null && plan.originalPrice.isNotBlank() && plan.originalPrice != plan.offerPrice) {
                                            Text(
                                                text = "$${plan.originalPrice}",
                                                style = typography.bodySm.copy(
                                                    textDecoration = TextDecoration.LineThrough
                                                ),
                                                color = colors.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = "$${plan.offerPrice}",
                                            style = typography.titleLg.copy(fontSize = 20.sp),
                                            fontWeight = FontWeight.Bold,
                                            color = colors.primary
                                        )
                                        if (plan.period.isNotBlank()) {
                                            Text(
                                                text = plan.period,
                                                style = typography.bodySm,
                                                color = colors.onSurfaceVariant,
                                                modifier = Modifier.padding(bottom = 2.dp)
                                            )
                                        }
                                    }

                                    // Description
                                    if (plan.desc.isNotBlank()) {
                                        Text(
                                            text = plan.desc,
                                            style = typography.labelSm,
                                            color = colors.onSurfaceVariant,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Telegram Help Link
                Surface(
                    shape = radius.smShape,
                    color = colors.surfaceContainerLow,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(radius.smShape)
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
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = WaexIcons.Info,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = UiText.get(R.string.waex_ui_manage_license_via_telegram_waenhancer, "Manage license via Telegram @waenhancerx_bot"),
                            style = typography.bodyMd.copy(color = colors.primary, fontWeight = FontWeight.Medium),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // ─── FREE VS PRO FEATURES COMPARISON TABLE ───
            WaexSectionHeader(
                title = UiText.get(R.string.waex_ui_free_vs_pro_features, "Free vs Pro Features"),
                subtitle = UiText.get(R.string.waex_ui_tap_any_feature_to_navigate_directly_t, "Tap any feature to navigate directly to its settings")
            )

            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceDim,
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    // Table Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF0F0F2))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = UiText.get(R.string.waex_ui_feature, "Feature"),
                            style = typography.labelSm,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.weight(1.5f)
                        )
                        Text(
                            text = UiText.get(R.string.waex_ui_free, "Free"),
                            style = typography.labelSm,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = UiText.get(R.string.pro_feature_tag, "Pro"),
                            style = typography.labelSm,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)

                    data class ComparisonFeature(
                        val name: String,
                        val freeVal: String,
                        val proVal: String,
                        val onNavigate: () -> Unit
                    )

                    val comparisonRows = listOf(
                        ComparisonFeature(UiText.get(R.string.waex_ui_always_typing, "Always Typing"), "✗", "✓") {
                            navController.navigateToPreference(1, "presence", "always_online")
                        },
                        ComparisonFeature(UiText.get(R.string.waex_ui_custom_status_view, "Custom Status View"), "✗", "✓") {
                            navController.navigateToPreference(2, null, "custom_status_view")
                        },
                        ComparisonFeature(UiText.get(R.string.feat_status_video_splitter_title, "Status Video Splitter"), "✗", "✓") {
                            navController.navigateToPreference(2, null, "status_video_splitter")
                        },
                        ComparisonFeature(UiText.get(R.string.feat_send_audio_as_voice_status_title, "Audio to Voice Status"), "✗", "✓") {
                            navController.navigateToPreference(2, null, "send_audio_as_voice_status")
                        },
                        ComparisonFeature(UiText.get(R.string.waex_ui_preserve_deleted_messages, "Preserve Deleted Messages"), "✗", "✓") {
                            navController.navigateToPreference(1, "conversation", "anti_revoke_messages")
                        },
                        ComparisonFeature(UiText.get(R.string.feat_message_bomber_title, "Message Bomber"), "✗", "✓") {
                            navController.scrollToTargetKey = "message_bomber"
                            navController.highlightTargetKey = "message_bomber"
                            navController.navigateTo(Screen.ConversationEnhancements)
                        },
                        ComparisonFeature(UiText.get(R.string.feat_file_size_spoofer_title, "File Size Spoofer"), "✗", "✓") {
                            navController.navigateToPreference(2, null, "file_size_spoofer")
                        },
                        ComparisonFeature(UiText.get(R.string.waex_ui_automation_tasker, "Automation & Tasker"), "✗", "✓") {
                            navController.navigateTo(Screen.AutomationTasker)
                        },
                        ComparisonFeature(UiText.get(R.string.waex_ui_per_contact_privacy, "Per-Contact Privacy"), "✗", "✓") {
                            navController.navigateTo(Screen.PerContactPrivacyList)
                        },
                        ComparisonFeature(UiText.get(R.string.waex_ui_theme_custom_styles, "Theme & Custom Styles"), UiText.get(R.string.waex_ui_basic, "Basic"), UiText.get(R.string.waex_ui_full, "Full")) {
                            navController.navigateTo(Screen.StylesSettings)
                        }
                    )

                    comparisonRows.forEachIndexed { idx, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { item.onNavigate() }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.name,
                                style = typography.bodyMd,
                                color = colors.onSurface,
                                modifier = Modifier.weight(1.5f)
                            )
                            Text(
                                text = item.freeVal,
                                style = typography.bodyMd,
                                fontWeight = if (item.freeVal == "✓") FontWeight.Bold else FontWeight.Medium,
                                color = if (item.freeVal == "✓") Color(0xFF4CAF50) else if (item.freeVal == "✗") colors.onSurfaceVariant.copy(alpha = 0.4f) else colors.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = item.proVal,
                                style = typography.bodyMd,
                                fontWeight = FontWeight.Bold,
                                color = colors.primary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (idx < comparisonRows.lastIndex) {
                            HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun parsePlansJson(jsonStr: String): List<PurchasePlan> {
    val list = mutableListOf<PurchasePlan>()
    val array = JSONArray(jsonStr)
    for (i in 0 until array.length()) {
        val obj = array.getJSONObject(i)
        val id = obj.optInt("id", 0)
        val name = obj.optString("name", "")
        val originalPrice = if (obj.has("original_price") && !obj.isNull("original_price")) {
            obj.optString("original_price")
        } else null
        val offerPrice = obj.optString("offer_price", "")
        val badge = if (obj.has("badge") && !obj.isNull("badge")) {
            obj.optString("badge")
        } else null
        var period = obj.optString("period", "")
        if (period.isEmpty()) {
            if (name.contains("monthly", ignoreCase = true)) {
                period = UiText.get(R.string.waex_ui_month, "/ Month")
            } else if (name.contains("yearly", ignoreCase = true)) {
                period = UiText.get(R.string.waex_ui_year, "/ Year")
            }
        }
        var desc = obj.optString("desc", "")
        if (desc.isEmpty()) {
            desc = if (name.contains("monthly", ignoreCase = true)) {
                UiText.get(R.string.waex_ui_full_access_to_all_pro_features_for_30, "Full access to all Pro features for 30 days")
            } else if (name.contains("yearly", ignoreCase = true)) {
                UiText.get(R.string.waex_ui_save_33_with_full_pro_access_for_365_d, "Save 33% with full Pro access for 365 days")
            } else {
                UiText.get(R.string.waex_ui_unlock_all_premium_pro_capabilities, "Unlock all premium Pro capabilities")
            }
        }

        if (name.isNotBlank() && offerPrice.isNotBlank()) {
            list.add(
                PurchasePlan(
                    id = id,
                    name = name,
                    originalPrice = originalPrice,
                    offerPrice = offerPrice,
                    period = period,
                    desc = desc,
                    badge = badge
                )
            )
        }
    }
    return list
}
