package com.waenhancer.ui.screens.license

import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.licensing.LicenseManager
import com.waenhancer.ui.components.WaexCard
import com.waenhancer.ui.components.WaexInfoBanner
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import org.json.JSONArray

data class ConnectedDeviceItem(
    val id: Int = 0,
    val deviceId: String = "",
    val devicePubKey: String = "",
    val deviceInfo: String = "Unknown Device",
    val lastLinkLocation: String = "",
    val createdAt: String = "",
    val isCurrent: Boolean = false
)

@Composable
fun shimmerBrush(targetValue: Float = 1000f): Brush {
    val shimmerColors = listOf(
        WaexTheme.colors.surfaceContainerLow.copy(alpha = 0.6f),
        WaexTheme.colors.surfaceContainerHighest.copy(alpha = 0.9f),
        WaexTheme.colors.surfaceContainerLow.copy(alpha = 0.6f)
    )
    val transition = rememberInfiniteTransition(label = "shimmerTransition")
    val translateAnimation = transition.animateFloat(
        initialValue = 0f,
        targetValue = targetValue,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )
    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset.Zero,
        end = Offset(x = translateAnimation.value, y = translateAnimation.value)
    )
}

@Composable
fun ManageDevicesShimmer(spacing: com.waenhancer.ui.designsystem.WaexSpacing, radius: com.waenhancer.ui.designsystem.WaexRadius) {
    val brush = shimmerBrush()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = spacing.pageMargin),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(spacing.stackSm))
            // Summary Card Shimmer
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp),
                shape = radius.cardShape,
                color = WaexTheme.colors.surface,
                border = BorderStroke(1.dp, WaexTheme.colors.outlineVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .width(90.dp)
                                .height(12.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(brush)
                        )
                        Box(
                            modifier = Modifier
                                .width(140.dp)
                                .height(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(brush)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(65.dp)
                            .height(24.dp)
                            .clip(CircleShape)
                            .background(brush)
                    )
                }
            }
        }

        item {
            Box(
                modifier = Modifier
                    .padding(start = 4.dp, top = 4.dp)
                    .width(110.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush)
            )
        }

        items(3) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                shape = radius.cardShape,
                color = WaexTheme.colors.surface,
                border = BorderStroke(1.dp, WaexTheme.colors.outlineVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(brush)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.55f)
                                .height(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(brush)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.4f)
                                .height(12.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(brush)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(58.dp)
                            .height(28.dp)
                            .clip(CircleShape)
                            .background(brush)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageDevicesScreen() {
    val navController = LocalWaexNavController.current
    val context = LocalContext.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    val prefs = remember {
        context.getSharedPreferences("com.waenhancer_preferences", android.content.Context.MODE_PRIVATE)
    }

    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var linkedCount by remember { mutableIntStateOf(prefs.getInt("linked_devices_count", 1)) }
    var deviceLimit by remember { mutableIntStateOf(prefs.getInt("device_limit", 2)) }
    val devicesList = remember { mutableStateListOf<ConnectedDeviceItem>() }

    var deviceToUnlink by remember { mutableStateOf<ConnectedDeviceItem?>(null) }
    var isUnlinking by remember { mutableStateOf(false) }
    var unlinkError by remember { mutableStateOf<String?>(null) }

    fun parseDevicesJson(jsonStr: String): List<ConnectedDeviceItem> {
        val result = mutableListOf<ConnectedDeviceItem>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(
                    ConnectedDeviceItem(
                        id = obj.optInt("id", 0),
                        deviceId = obj.optString("device_id", ""),
                        devicePubKey = obj.optString("device_pub_key", ""),
                        deviceInfo = obj.optString("device_info", "Android Device"),
                        lastLinkLocation = obj.optString("last_link_location", ""),
                        createdAt = obj.optString("created_at", ""),
                        isCurrent = obj.optBoolean("is_current", false)
                    )
                )
            }
        } catch (_: Exception) {}
        return result
    }

    fun loadDevices(isPull: Boolean = false) {
        if (isPull) {
            isRefreshing = true
        } else {
            isLoading = true
            errorMessage = null
        }
        LicenseManager.fetchConnectedDevices(context, object : LicenseManager.DevicesCallback {
            override fun onSuccess(linked: Int, limit: Int, json: String) {
                isLoading = false
                isRefreshing = false
                errorMessage = null
                linkedCount = linked
                deviceLimit = limit
                devicesList.clear()
                devicesList.addAll(parseDevicesJson(json))
            }

            override fun onError(msg: String?) {
                isLoading = false
                isRefreshing = false
                if (devicesList.isEmpty()) {
                    errorMessage = msg ?: "Failed to fetch connected devices."
                } else {
                    Toast.makeText(context, msg ?: "Could not refresh devices.", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    // Initial load and parse cached devices
    LaunchedEffect(Unit) {
        val cached = prefs.getString("connected_devices_json", "[]") ?: "[]"
        val parsed = parseDevicesJson(cached)
        if (parsed.isNotEmpty()) {
            devicesList.clear()
            devicesList.addAll(parsed)
            isLoading = false
            loadDevices(isPull = true)
        } else {
            loadDevices(isPull = false)
        }
    }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = "Connected Devices",
                onBackClick = { navController.popBack() }
            )
        },
        containerColor = colors.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isLoading && devicesList.isEmpty()) {
                ManageDevicesShimmer(spacing = spacing, radius = radius)
            } else if (errorMessage != null && devicesList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(spacing.pageMargin),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = WaexIcons.Warning,
                            contentDescription = null,
                            tint = colors.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = errorMessage ?: "Failed to load devices",
                            style = typography.bodyMd,
                            color = colors.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = { loadDevices(isPull = false) },
                            shape = radius.buttonShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primary,
                                contentColor = colors.onPrimary
                            )
                        ) {
                            Text(text = "Retry")
                        }
                    }
                }
            } else {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { loadDevices(isPull = true) },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = spacing.pageMargin),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            Spacer(modifier = Modifier.height(spacing.stackSm))
                            // Summary status card
                            WaexCard(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = "Active Device Slots",
                                            style = typography.labelSm,
                                            color = colors.onSurfaceVariant
                                        )
                                        Text(
                                            text = "$linkedCount of $deviceLimit devices in use",
                                            style = typography.titleMd,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.onSurface
                                        )
                                    }
                                    Surface(
                                        shape = CircleShape,
                                        color = if (linkedCount >= deviceLimit) Color(0xFFFF9800).copy(alpha = 0.15f) else colors.primaryContainer,
                                        border = BorderStroke(
                                            1.dp,
                                            if (linkedCount >= deviceLimit) Color(0xFFFF9800).copy(alpha = 0.4f) else colors.primary.copy(alpha = 0.3f)
                                        )
                                    ) {
                                        Text(
                                            text = if (linkedCount >= deviceLimit) "Limit Full" else "Available",
                                            style = typography.labelSm,
                                            fontWeight = FontWeight.Bold,
                                            color = if (linkedCount >= deviceLimit) Color(0xFFFF9800) else colors.primary,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Text(
                                text = "LINKED DEVICES",
                                style = typography.labelSm,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurfaceVariant,
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                            )
                        }

                        if (devicesList.isEmpty()) {
                            item {
                                WaexCard(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "No linked devices found for this license.",
                                        style = typography.bodyMd,
                                        color = colors.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 12.dp)
                                    )
                                }
                            }
                        } else {
                            items(devicesList, key = { it.deviceId.ifEmpty { it.id.toString() } }) { device ->
                                DeviceCardItem(
                                    device = device,
                                    onUnlinkClick = {
                                        unlinkError = null
                                        deviceToUnlink = device
                                    }
                                )
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            WaexInfoBanner(
                                title = "Device Management",
                                message = "You can unlink inactive devices here to free up slots for new installations. Unlinking your current device will deactivate Pro on this phone.",
                                bannerType = com.waenhancer.ui.components.BannerType.INFO
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }

        // Unlink Confirmation Bottom Sheet
        if (deviceToUnlink != null) {
            val targetDevice = deviceToUnlink!!
            val isCurrent = targetDevice.isCurrent
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

            ModalBottomSheet(
                onDismissRequest = {
                    if (!isUnlinking) deviceToUnlink = null
                },
                sheetState = sheetState,
                containerColor = colors.surface,
                tonalElevation = 8.dp,
                shape = radius.bottomSheetShape
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(top = 4.dp, bottom = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Header Icon
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFF44336).copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Color(0xFFF44336).copy(alpha = 0.3f)),
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = WaexIcons.Clear,
                                contentDescription = null,
                                tint = Color(0xFFF44336),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    // Title & Description
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (isCurrent) "Unlink This Device?" else "Unlink Connected Device?",
                            style = typography.titleMd,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                        Text(
                            text = if (isCurrent)
                                "Dissociating your current phone will revert WAEX to the Free tier and restart the app."
                            else
                                "This will disconnect '${targetDevice.deviceInfo}' from your license key and free up a device slot.",
                            style = typography.bodyMd,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }

                    // Target Device Details Card
                    Surface(
                        shape = radius.mdShape,
                        color = colors.surfaceContainerLow,
                        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Device Model",
                                    style = typography.labelSm,
                                    color = colors.onSurfaceVariant
                                )
                                Text(
                                    text = targetDevice.deviceInfo,
                                    style = typography.bodyMd,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onSurface
                                )
                            }
                            if (targetDevice.lastLinkLocation.isNotEmpty()) {
                                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.3f))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Location / IP",
                                        style = typography.labelSm,
                                        color = colors.onSurfaceVariant
                                    )
                                    Text(
                                        text = targetDevice.lastLinkLocation,
                                        style = typography.bodyMd,
                                        color = colors.onSurface
                                    )
                                }
                            }
                        }
                    }

                    if (unlinkError != null) {
                        Surface(
                            shape = radius.smShape,
                            color = Color(0xFFF44336).copy(alpha = 0.1f),
                            border = BorderStroke(1.dp, Color(0xFFF44336).copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = unlinkError ?: "Unlink failed",
                                style = typography.labelSm,
                                color = Color(0xFFF44336),
                                modifier = Modifier.padding(12.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    // Action Buttons
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                isUnlinking = true
                                unlinkError = null
                                LicenseManager.unlinkSpecificDevice(
                                    context,
                                    targetDevice.deviceId.ifEmpty { null },
                                    targetDevice.devicePubKey.ifEmpty { null },
                                    object : LicenseManager.UnlinkCallback {
                                        override fun onSuccess() {
                                            isUnlinking = false
                                            val wasCurrent = targetDevice.isCurrent
                                            deviceToUnlink = null
                                            if (wasCurrent) {
                                                Toast.makeText(context, "Current device unlinked. Reverted to Free tier.", Toast.LENGTH_LONG).show()
                                                navController.popBack()
                                            } else {
                                                Toast.makeText(context, "Device unlinked successfully.", Toast.LENGTH_SHORT).show()
                                                loadDevices(isPull = true)
                                            }
                                        }

                                        override fun onError(msg: String?) {
                                            isUnlinking = false
                                            unlinkError = msg ?: "Unlink failed. Please try again."
                                        }
                                    }
                                )
                            },
                            shape = radius.fullShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFF44336),
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            enabled = !isUnlinking
                        ) {
                            if (isUnlinking) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Unlinking Device…",
                                    style = typography.bodyMd.copy(fontWeight = FontWeight.Bold)
                                )
                            } else {
                                Text(
                                    text = if (isCurrent) "Confirm Unlink Current Device" else "Confirm Unlink Device",
                                    style = typography.bodyMd.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = { deviceToUnlink = null },
                            shape = radius.fullShape,
                            border = BorderStroke(1.dp, colors.outlineVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            enabled = !isUnlinking
                        ) {
                            Text(
                                text = "Cancel",
                                style = typography.bodyMd.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCardItem(
    device: ConnectedDeviceItem,
    onUnlinkClick: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    val borderColor = if (device.isCurrent) colors.primary.copy(alpha = 0.5f) else colors.outlineVariant
    val containerColor = colors.surface

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = radius.cardShape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Device Hardware Icon
            Surface(
                shape = CircleShape,
                color = if (device.isCurrent) colors.primaryContainer else colors.surfaceContainerLow,
                border = BorderStroke(1.dp, if (device.isCurrent) colors.primary.copy(alpha = 0.3f) else colors.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = WaexIcons.Smartphone,
                        contentDescription = null,
                        tint = if (device.isCurrent) colors.primary else colors.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Device Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = device.deviceInfo,
                        style = typography.bodyMd,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (device.isCurrent) {
                        Surface(
                            shape = CircleShape,
                            color = colors.primaryContainer,
                            border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = "This Device",
                                style = typography.labelSm,
                                fontWeight = FontWeight.Bold,
                                color = colors.primary,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                if (device.lastLinkLocation.isNotEmpty()) {
                    Text(
                        text = device.lastLinkLocation,
                        style = typography.labelSm,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            // Unlink Action Button
            Surface(
                shape = CircleShape,
                color = Color(0xFFF44336).copy(alpha = 0.1f),
                border = BorderStroke(1.dp, Color(0xFFF44336).copy(alpha = 0.25f)),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onUnlinkClick() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Unlink",
                        style = typography.labelSm,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF44336),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

