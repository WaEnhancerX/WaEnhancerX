package com.waenhancer.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.waenhancer.core.preferences.WaexPreferenceManager
import com.waenhancer.ui.components.StitchSwitch
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

enum class PermissionType {
    RUNTIME,
    MANAGE_ALL_FILES,
    SYSTEM_ALERT_WINDOW,
    BATTERY_OPTIMIZATION,
    INSTALL_PACKAGES
}

data class PermissionItem(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val type: PermissionType,
    val permissions: List<String> = emptyList()
)

@AndroidEntryPoint
class PermissionsActivity : ComponentActivity() {

    @Inject lateinit var preferenceManager: WaexPreferenceManager

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (areAllPermissionsGranted(this)) {
            navigateToHome()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (areAllPermissionsGranted(this)) {
            navigateToHome()
            return
        }

        enableEdgeToEdge()
        setContent {
            val themeMode = preferenceManager.getString("app_theme_mode", "System")
            val isDark = when (themeMode) {
                "Dark" -> true
                "Light" -> false
                else -> isSystemInDarkTheme()
            }
            WaexTheme(darkTheme = isDark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PermissionsScreen(
                        onContinue = { navigateToHome() }
                    )
                }
            }
        }
    }

    private fun navigateToHome() {
        val intent = Intent(this, MainActivity::class.java).apply {
            if (this@PermissionsActivity.intent.extras != null) {
                putExtras(this@PermissionsActivity.intent.extras!!)
            }
        }
        startActivity(intent)
        finish()
    }

    companion object {
        fun getRequiredPermissionItems(): List<PermissionItem> {
            val items = mutableListOf<PermissionItem>()

            // 1. Storage & Media
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                items.add(
                    PermissionItem(
                        id = "media_photos_video",
                        title = "Photos & Media Storage",
                        description = "Required to save status photos, split videos, and load custom wallpaper themes.",
                        icon = WaexIcons.Image,
                        type = PermissionType.RUNTIME,
                        permissions = listOf(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VIDEO,
                            Manifest.permission.READ_MEDIA_AUDIO
                        )
                    )
                )
            } else {
                items.add(
                    PermissionItem(
                        id = "storage_legacy",
                        title = "Storage Access",
                        description = "Required to save status updates, backup configurations, and export recordings.",
                        icon = WaexIcons.Folder,
                        type = PermissionType.RUNTIME,
                        permissions = listOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )
                    )
                )
            }

            // 2. All Files Access
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                items.add(
                    PermissionItem(
                        id = "manage_storage",
                        title = "All Files Access",
                        description = "Required for WhatsApp database backups, file spoofing, and restoring chat vaults.",
                        icon = WaexIcons.Folder,
                        type = PermissionType.MANAGE_ALL_FILES
                    )
                )
            }

            // 3. Contacts
            items.add(
                PermissionItem(
                    id = "contacts",
                    title = "Contacts Access",
                    description = "Required to resolve contact names and setup per-contact privacy rules.",
                    icon = WaexIcons.Contacts,
                    type = PermissionType.RUNTIME,
                    permissions = listOf(Manifest.permission.READ_CONTACTS)
                )
            )

            // 4. Audio Recording
            items.add(
                PermissionItem(
                    id = "audio_recording",
                    title = "Microphone & Call Recording",
                    description = "Required for automatic call recording and AI voice-to-text message transcription.",
                    icon = WaexIcons.Mic,
                    type = PermissionType.RUNTIME,
                    permissions = listOf(Manifest.permission.RECORD_AUDIO)
                )
            )

            // 5. Notifications
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                items.add(
                    PermissionItem(
                        id = "notifications",
                        title = "Post Notifications",
                        description = "Required for download progress indicators, online toast alerts, and Tasker sync.",
                        icon = WaexIcons.Notifications,
                        type = PermissionType.RUNTIME,
                        permissions = listOf(Manifest.permission.POST_NOTIFICATIONS)
                    )
                )
            }

            // 6. Draw Over Other Apps
            items.add(
                PermissionItem(
                    id = "overlay",
                    title = "Display Over Other Apps",
                    description = "Required to display floating action bubbles, online toasts, and chat quick menus.",
                    icon = WaexIcons.Layers,
                    type = PermissionType.SYSTEM_ALERT_WINDOW
                )
            )

            // 7. Battery Optimization Exemption
            items.add(
                PermissionItem(
                    id = "battery",
                    title = "Ignore Battery Optimizations",
                    description = "Prevents system throttling for background Tasker automations and persistent stealth hooks.",
                    icon = WaexIcons.Battery,
                    type = PermissionType.BATTERY_OPTIMIZATION
                )
            )

            // 8. Install Unknown Apps
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                items.add(
                    PermissionItem(
                        id = "install_packages",
                        title = "Install Unknown Apps",
                        description = "Required for seamless in-app version update downloads and module installations.",
                        icon = WaexIcons.Install,
                        type = PermissionType.INSTALL_PACKAGES
                    )
                )
            }

            return items
        }

        fun checkPermissionStatus(context: Context, item: PermissionItem): Boolean {
            return when (item.type) {
                PermissionType.RUNTIME -> {
                    item.permissions.all { perm ->
                        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                    }
                }
                PermissionType.MANAGE_ALL_FILES -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Environment.isExternalStorageManager()
                    } else {
                        true
                    }
                }
                PermissionType.SYSTEM_ALERT_WINDOW -> {
                    Settings.canDrawOverlays(context)
                }
                PermissionType.BATTERY_OPTIMIZATION -> {
                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    powerManager.isIgnoringBatteryOptimizations(context.packageName)
                }
                PermissionType.INSTALL_PACKAGES -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.packageManager.canRequestPackageInstalls()
                    } else {
                        true
                    }
                }
            }
        }

        fun areAllPermissionsGranted(context: Context): Boolean {
            return getRequiredPermissionItems().all { checkPermissionStatus(context, it) }
        }
    }
}

@Composable
fun PermissionsScreen(
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val spacing = WaexTheme.spacing

    val permissionItems = remember { PermissionsActivity.getRequiredPermissionItems() }
    val permissionStatusMap = remember { mutableStateMapOf<String, Boolean>() }

    var currentRequestingItem by remember { mutableStateOf<PermissionItem?>(null) }

    fun refreshStatuses() {
        permissionItems.forEach { item ->
            permissionStatusMap[item.id] = PermissionsActivity.checkPermissionStatus(context, item)
        }
    }

    // Refresh statuses on resume lifecycle
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshStatuses()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        refreshStatuses()
    }

    // Runtime launcher
    val multiplePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        refreshStatuses()
    }

    val singlePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        refreshStatuses()
    }

    val genericSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        refreshStatuses()
    }

    fun requestPermission(item: PermissionItem) {
        currentRequestingItem = item
        when (item.type) {
            PermissionType.RUNTIME -> {
                if (item.permissions.size == 1) {
                    singlePermissionLauncher.launch(item.permissions.first())
                } else if (item.permissions.size > 1) {
                    multiplePermissionLauncher.launch(item.permissions.toTypedArray())
                }
            }
            PermissionType.MANAGE_ALL_FILES -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        genericSettingsLauncher.launch(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        genericSettingsLauncher.launch(intent)
                    }
                }
            }
            PermissionType.SYSTEM_ALERT_WINDOW -> {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                genericSettingsLauncher.launch(intent)
            }
            PermissionType.BATTERY_OPTIMIZATION -> {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    genericSettingsLauncher.launch(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    genericSettingsLauncher.launch(intent)
                }
            }
            PermissionType.INSTALL_PACKAGES -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    )
                    genericSettingsLauncher.launch(intent)
                }
            }
        }
    }

    val totalCount = permissionItems.size
    val grantedCount = permissionItems.count { permissionStatusMap[it.id] == true }
    val allGranted = totalCount > 0 && grantedCount == totalCount
    val progress = if (totalCount > 0) grantedCount.toFloat() / totalCount.toFloat() else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "progress"
    )

    Scaffold(
        containerColor = colors.background,
        bottomBar = {
            Surface(
                color = colors.surface,
                shadowElevation = 12.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.pageMargin, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnimatedVisibility(
                        visible = allGranted,
                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(tween(300)),
                        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(tween(200))
                    ) {
                        Button(
                            onClick = onContinue,
                            shape = radius.lgShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primary,
                                contentColor = colors.onPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Continue to WaEnhancerX",
                                    style = typography.bodyLg,
                                    fontWeight = FontWeight.Bold
                                )
                                Icon(
                                    imageVector = WaexIcons.ChevronRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    if (!allGranted) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Enable all $totalCount permissions above to proceed ($grantedCount/$totalCount completed)",
                                style = typography.labelSm,
                                color = colors.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = spacing.pageMargin)
                        .padding(top = 24.dp, bottom = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(colors.primary, colors.secondary)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = WaexIcons.Security,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Permissions Required",
                        style = typography.displayLgMobile,
                        fontWeight = FontWeight.Bold,
                        color = colors.onBackground
                    )


                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "To unlock stealth features, status downloaders, AI transcription, and automations, grant the required permissions below.",
                        style = typography.bodyMd,
                        color = colors.onSurfaceVariant,
                        lineHeight = 20.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Progress Overview Card
                    Surface(
                        shape = radius.bentoCardShape,
                        color = colors.surfaceDim,
                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Setup Progress",
                                    style = typography.bodyMd,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSurface
                                )
                                Text(
                                    text = "$grantedCount of $totalCount Granted",
                                    style = typography.labelSm,
                                    fontWeight = FontWeight.Bold,
                                    color = if (allGranted) Color(0xFF22C55E) else colors.primary
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(CircleShape),
                                color = if (allGranted) Color(0xFF22C55E) else colors.primary,
                                trackColor = colors.outlineVariant.copy(alpha = 0.4f),
                                strokeCap = StrokeCap.Round
                            )
                        }
                    }
                }
            }

            items(permissionItems, key = { it.id }) { item ->
                val isGranted = permissionStatusMap[item.id] == true
                val cardBg by animateColorAsState(
                    targetValue = if (isGranted) colors.surface else colors.surfaceDim,
                    animationSpec = tween(300),
                    label = "card_bg"
                )
                val iconBg by animateColorAsState(
                    targetValue = if (isGranted) Color(0xFF22C55E).copy(alpha = 0.15f) else colors.primaryContainer,
                    animationSpec = tween(300),
                    label = "icon_bg"
                )
                val iconTint by animateColorAsState(
                    targetValue = if (isGranted) Color(0xFF22C55E) else colors.primary,
                    animationSpec = tween(300),
                    label = "icon_tint"
                )

                Surface(
                    shape = radius.bentoCardShape,
                    color = cardBg,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isGranted) Color(0xFF22C55E).copy(alpha = 0.35f) else colors.outlineVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.pageMargin)
                        .clickable(enabled = !isGranted) {
                            requestPermission(item)
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(iconBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isGranted) WaexIcons.Success else item.icon,
                                contentDescription = null,
                                tint = iconTint,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = item.title,
                                style = typography.bodyLg,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface
                            )
                            Text(
                                text = item.description,
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Switch: when granted, switch is on and disabled. When not granted, clicking it triggers permission flow.
                        StitchSwitch(
                            checked = isGranted,
                            onCheckedChange = { checked ->
                                if (!isGranted && checked) {
                                    requestPermission(item)
                                }
                            }
                        )
                    }
                }

            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
