package com.waenhancer.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import com.waenhancer.utils.UniversalVersionValidator

@Composable
fun SupportedVersionsScreen() {
    val navController = LocalWaexNavController.current
    val prefManager = LocalWaexPreferenceManager.current
    val context = LocalContext.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    // Preference States
    var isCustomizeEnabled by remember {
        mutableStateOf(prefManager.getBoolean("customize_supported_versions", true))
    }
    var isBypassEnabled by remember {
        mutableStateOf(prefManager.getBoolean("bypass_version_check", false))
    }

    val customVersionsList = remember {
        mutableStateListOf<String>().apply {
            val prefs = com.waenhancer.config.PreferenceStores.publicStore(context)
            addAll(UniversalVersionValidator.getCustomVersions(prefs))
        }
    }

    val prefs = remember(context) { com.waenhancer.config.PreferenceStores.publicStore(context) }

    // Dynamic list of detected WhatsApp packages & clones
    val detectedApps = remember(customVersionsList.size, isCustomizeEnabled, isBypassEnabled) {
        com.waenhancer.utils.WhatsAppPackageDetector.detectWhatsAppApps(context)
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var inputVersionText by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }

    fun refreshCustomList() {
        customVersionsList.clear()
        customVersionsList.addAll(UniversalVersionValidator.getCustomVersions(prefs))
    }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = "Supported Versions",
                onBackClick = { navController.popBack() }
            )
        },
        containerColor = colors.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = spacing.pageMargin,
                vertical = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Installed App Status Banner
            item {
                Text(
                    text = "TARGET APPLICATIONS COMPATIBILITY",
                    style = typography.labelSm,
                    color = colors.primary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    detectedApps.forEach { app ->
                        val cleanVer = app.versionName.removePrefix("v")
                        val isSupported = app.isInstalled && (isBypassEnabled || UniversalVersionValidator.isSupported(context, cleanVer, prefs))
                        AppVersionStatusCard(
                            appName = app.appName,
                            packageName = app.packageName,
                            versionName = if (app.isInstalled) cleanVer else null,
                            isSupported = isSupported,
                            onAddCurrentClick = {
                                if (app.isInstalled) {
                                    UniversalVersionValidator.addCustomVersion(prefs, cleanVer)
                                    prefManager.putBoolean("customize_supported_versions", true)
                                    isCustomizeEnabled = true
                                    refreshCustomList()
                                }
                            }
                        )
                    }
                }
            }

            // 2. Global Version Controls
            item {
                Text(
                    text = "SETTINGS & ENFORCEMENT",
                    style = typography.labelSm,
                    color = colors.primary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    shape = radius.mdShape,
                    color = colors.surfaceDim,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        // Customize switch
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = !isCustomizeEnabled
                                    isCustomizeEnabled = next
                                    prefManager.putBoolean("customize_supported_versions", next)
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text = "Enable Custom Versions",
                                    style = typography.bodyMd,
                                    color = colors.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Allow user-defined versions and wildcard rules to load hooks",
                                    style = typography.labelSm,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            com.waenhancer.ui.components.StitchSwitch(
                                checked = isCustomizeEnabled,
                                onCheckedChange = { checked ->
                                    isCustomizeEnabled = checked
                                    prefManager.putBoolean("customize_supported_versions", checked)
                                }
                            )
                        }

                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))

                        // Bypass switch
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = !isBypassEnabled
                                    isBypassEnabled = next
                                    prefManager.putBoolean("bypass_version_check", next)
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text = "Bypass Version Verification",
                                    style = typography.bodyMd,
                                    color = if (isBypassEnabled) colors.error else colors.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Force WAEX hooks to run on any version without checking (Advanced)",
                                    style = typography.labelSm,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            com.waenhancer.ui.components.StitchSwitch(
                                checked = isBypassEnabled,
                                onCheckedChange = { checked ->
                                    isBypassEnabled = checked
                                    prefManager.putBoolean("bypass_version_check", checked)
                                }
                            )
                        }
                    }
                }
            }

            // 3. User-Defined Custom Versions
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "USER-DEFINED VERSIONS (${customVersionsList.size})",
                        style = typography.labelSm,
                        color = colors.primary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Button(
                        onClick = {
                            inputVersionText = ""
                            inputError = null
                            showAddDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primary,
                            contentColor = colors.surface
                        ),
                        shape = radius.smShape,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Add",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Add Rule", style = typography.labelSm, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (customVersionsList.isEmpty()) {
                item {
                    Surface(
                        shape = radius.mdShape,
                        color = colors.surfaceDim.copy(alpha = 0.5f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.padding(20.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = "No custom version rules added yet.\nTap \"Add Rule\" to add your WhatsApp version or wildcards (e.g. 2.26.xx).",
                                style = typography.labelSm,
                                color = colors.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(customVersionsList) { customVer ->
                    VersionRuleItem(
                        version = customVer,
                        badge = "User Defined",
                        badgeColor = colors.primary,
                        onDeleteClick = {
                            UniversalVersionValidator.removeCustomVersion(prefs, customVer)
                            refreshCustomList()
                        }
                    )
                }
            }

            // 4. Universal Predefined System Versions
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "UNIVERSAL BUILT-IN VERSIONS",
                    style = typography.labelSm,
                    color = colors.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            items(UniversalVersionValidator.getBuiltinSupportedVersions(context)) { sysVer ->
                VersionRuleItem(
                    version = sysVer,
                    badge = "Universal Base",
                    badgeColor = colors.secondary,
                    onDeleteClick = null
                )
            }
        }
    }

    // Add Custom Version Dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = {
                Text(
                    text = "Add Supported Version",
                    style = typography.headlineMd,
                    color = colors.onSurface,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter a specific build (e.g. 2.26.30.97) or a wildcard (e.g. 2.26.30.xx) to match builds in that branch.",
                        style = typography.labelSm,
                        color = colors.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = inputVersionText,
                        onValueChange = {
                            inputVersionText = it
                            inputError = null
                        },
                        label = { Text("Version or Wildcard") },
                        placeholder = { Text("e.g. 2.26.xx or 2.26.18.15") },
                        isError = inputError != null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (inputVersionText.trim().isNotEmpty()) {
                                UniversalVersionValidator.addCustomVersion(prefs, inputVersionText.trim())
                                prefManager.putBoolean("customize_supported_versions", true)
                                isCustomizeEnabled = true
                                refreshCustomList()
                                showAddDialog = false
                            }
                        }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.primary,
                            unfocusedBorderColor = colors.outlineVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (inputError != null) {
                        Text(text = inputError!!, color = colors.error, style = typography.labelSm)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = inputVersionText.trim()
                        if (clean.isEmpty()) {
                            inputError = "Please enter a version number"
                            return@Button
                        }
                        UniversalVersionValidator.addCustomVersion(prefs, clean)
                        prefManager.putBoolean("customize_supported_versions", true)
                        isCustomizeEnabled = true
                        refreshCustomList()
                        showAddDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                ) {
                    Text("Add Version")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel", color = colors.onSurfaceVariant)
                }
            },
            containerColor = colors.surface,
            shape = radius.mdShape
        )
    }
}

@Composable
private fun AppVersionStatusCard(
    appName: String,
    packageName: String,
    versionName: String?,
    isSupported: Boolean,
    onAddCurrentClick: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    val isInstalled = versionName != null

    Surface(
        shape = radius.mdShape,
        color = colors.surfaceDim,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (!isInstalled) colors.outlineVariant
            else if (isSupported) Color(0xFF10B981).copy(alpha = 0.4f)
            else Color(0xFFF59E0B).copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (packageName == "com.whatsapp") Color(0xFF25D366).copy(alpha = 0.15f)
                                else Color(0xFF3B82F6).copy(alpha = 0.15f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = appName,
                            tint = if (packageName == "com.whatsapp") Color(0xFF25D366) else Color(0xFF3B82F6),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = appName,
                            style = typography.bodyMd,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                        Text(
                            text = if (isInstalled) "Installed: v$versionName" else "Not installed on this device",
                            style = typography.labelSm,
                            color = colors.onSurfaceVariant
                        )
                    }
                }

                // Status Badge
                Surface(
                    shape = radius.smShape,
                    color = when {
                        !isInstalled -> colors.outlineVariant.copy(alpha = 0.3f)
                        isSupported -> Color(0xFF10B981).copy(alpha = 0.15f)
                        else -> Color(0xFFF59E0B).copy(alpha = 0.15f)
                    }
                ) {
                    Text(
                        text = when {
                            !isInstalled -> "Unavailable"
                            isSupported -> "Supported"
                            else -> "Unsupported"
                        },
                        style = typography.labelSm,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            !isInstalled -> colors.onSurfaceVariant
                            isSupported -> Color(0xFF10B981)
                            else -> Color(0xFFF59E0B)
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Quick 1-tap add button if unsupported
            if (isInstalled && !isSupported) {
                Button(
                    onClick = onAddCurrentClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF59E0B),
                        contentColor = Color.Black
                    ),
                    shape = radius.smShape,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)
                ) {
                    Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Add v$versionName to Supported List",
                        style = typography.labelSm,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionRuleItem(
    version: String,
    badge: String,
    badgeColor: Color,
    onDeleteClick: (() -> Unit)?
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    Surface(
        shape = radius.mdShape,
        color = colors.surfaceDim,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = version,
                    style = typography.bodyMd,
                    color = colors.onSurface,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.width(10.dp))

                Surface(
                    shape = radius.smShape,
                    color = badgeColor.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = badge,
                        style = typography.labelSm,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (onDeleteClick != null) {
                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "Delete",
                        tint = colors.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
