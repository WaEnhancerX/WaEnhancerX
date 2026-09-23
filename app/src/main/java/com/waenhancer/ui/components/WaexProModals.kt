package com.waenhancer.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LicenseActivationModal(
    onDismiss: () -> Unit,
    onActivated: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
                        text = "Activate Pro License",
                        style = typography.headlineMd,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    Text(
                        text = "Hardware-locked feature activation",
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
                    contentDescription = "Close",
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
                    licenseKey = input.uppercase(java.util.Locale.US)
                    verifyState = "idle"
                    errorMessage = ""
                },
                singleLine = true,
                textStyle = typography.bodyLg.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
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
                val trimmedKey = licenseKey.trim().uppercase(java.util.Locale.US)
                if (trimmedKey.isEmpty()) {
                    verifyState = "error"
                    errorMessage = "Please enter your license key."
                    return@Button
                }
                if (!com.waenhancer.licensing.LicenseManager.isValidLicensePattern(trimmedKey)) {
                    verifyState = "error"
                    errorMessage = "Invalid key format. Expected: WAEX-XXXX-XXXX-XXXX"
                    return@Button
                }

                verifyState = "verifying"
                errorMessage = ""

                com.waenhancer.licensing.LicenseManager.verifyLicense(
                    context,
                    trimmedKey,
                    object : com.waenhancer.licensing.LicenseManager.LicenseCallback {
                        override fun onSuccess(planName: String?, expiresAtStr: String?, tgUsername: String?) {
                            verifyState = "success"
                            onActivated()
                        }

                        override fun onError(message: String?) {
                            verifyState = "error"
                            errorMessage = message ?: "Verification failed."
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
                CircularProgressIndicator(
                    color = colors.onPrimary,
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Verifying with Server…", style = typography.bodyMd, fontWeight = FontWeight.Bold)
            } else if (verifyState == "success") {
                Icon(
                    imageVector = WaexIcons.Success,
                    contentDescription = null,
                    tint = colors.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Pro License Activated!", style = typography.bodyMd, fontWeight = FontWeight.Bold)
            } else {
                Text(text = "Verify & Activate", style = typography.bodyMd, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Telegram Bot Button
        Surface(
            shape = radius.mdShape,
            color = colors.surfaceDim,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
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
                    text = "Get or manage license via @waenhancerx_bot",
                    style = typography.bodyMd.copy(color = colors.primary, fontWeight = FontWeight.Medium),
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
fun FileSizeSpooferModal(
    onDismiss: () -> Unit
) {
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var sizeValue by remember { mutableStateOf("500") }
    var sizeUnit by remember { mutableStateOf("MB") }
    var showUnitDropdown by remember { mutableStateOf(false) }
    var applied by remember { mutableStateOf(false) }

    val presets = listOf("100 MB", "500 MB", "1 GB", "2 GB", "5 GB", "10 GB")

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
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text(
                    text = "File Size Spoofer",
                    style = typography.headlineMd,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
                Text(
                    text = "Override upload limits",
                    style = typography.bodyMd,
                    color = colors.onSurfaceVariant
                )
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
                    contentDescription = "Close",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        // Large Numeric Input + Unit Select
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(140.dp)
                    .height(64.dp)
                    .clip(radius.lgShape)
                    .background(colors.surfaceDim)
                    .border(1.dp, colors.outlineVariant, radius.lgShape)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicTextField(
                    value = sizeValue,
                    onValueChange = {
                        sizeValue = it
                        applied = false
                    },
                    singleLine = true,
                    textStyle = typography.displayLgMobile.copy(
                        color = colors.onSurface,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    ),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Box {
                Box(
                    modifier = Modifier
                        .height(64.dp)
                        .clip(radius.lgShape)
                        .background(colors.surfaceDim)
                        .border(1.dp, colors.outlineVariant, radius.lgShape)
                        .clickable { showUnitDropdown = true }
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = sizeUnit,
                        style = typography.headlineMd,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                }
                DropdownMenu(
                    expanded = showUnitDropdown,
                    onDismissRequest = { showUnitDropdown = false }
                ) {
                    listOf("MB", "GB", "TB").forEach { unit ->
                        DropdownMenuItem(
                            text = { Text(text = unit, style = typography.bodyLg) },
                            onClick = {
                                sizeUnit = unit
                                showUnitDropdown = false
                                applied = false
                            }
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        // Preset Chips
        Text(
            text = "Presets",
            style = typography.labelSm,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { preset ->
                val isSelected = "$sizeValue $sizeUnit" == preset
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isSelected) colors.primary else colors.surfaceDim)
                        .border(1.dp, if (isSelected) colors.primary else colors.outlineVariant, CircleShape)
                        .clickable {
                            val parts = preset.split(" ")
                            sizeValue = parts[0]
                            sizeUnit = parts[1]
                            applied = false
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = preset,
                        style = typography.bodyMd,
                        fontWeight = FontWeight.Medium,
                        color = if (isSelected) colors.onPrimary else colors.onSurface
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(28.dp))

        // Apply Button
        Button(
            onClick = { applied = true },
            shape = radius.lgShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (applied) Color(0xFF4CAF50) else colors.primary,
                contentColor = colors.onPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                text = if (applied) "✓ Applied" else "Apply — $sizeValue $sizeUnit",
                style = typography.bodyLg,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun MessageBomberModal(
    onDismiss: () -> Unit
) {
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val coroutineScope = rememberCoroutineScope()

    var message by remember { mutableStateOf("") }
    var recipient by remember { mutableStateOf("") }
    var count by remember { mutableStateOf(50f) }
    var delayMs by remember { mutableStateOf(500f) }

    var showConfirm by remember { mutableStateOf(false) }
    var executing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.pageMargin)
            .verticalScroll(rememberScrollState())
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
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text(
                    text = "Message Bomber",
                    style = typography.headlineMd,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
                Text(
                    text = "Automated message delivery",
                    style = typography.bodyMd,
                    color = colors.onSurfaceVariant
                )
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
                    contentDescription = "Close",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        // Message Input
        Text(
            text = "Message",
            style = typography.labelSm,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(radius.mdShape)
                .background(colors.surfaceDim)
                .border(1.dp, colors.outlineVariant, radius.mdShape)
                .padding(12.dp)
        ) {
            if (message.isEmpty()) {
                Text(
                    text = "Enter the message to send...",
                    style = typography.bodyMd,
                    color = colors.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            BasicTextField(
                value = message,
                onValueChange = { message = it },
                textStyle = typography.bodyMd.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Recipient
        Text(
            text = "Recipient",
            style = typography.labelSm,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(radius.mdShape)
                .background(colors.surfaceDim)
                .border(1.dp, colors.outlineVariant, radius.mdShape)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (recipient.isEmpty()) {
                Text(
                    text = "+1 555-0192",
                    style = typography.bodyMd,
                    color = colors.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            BasicTextField(
                value = recipient,
                onValueChange = { recipient = it },
                singleLine = true,
                textStyle = typography.bodyMd.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Count Slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Message Count", style = typography.bodyMd, color = colors.onSurfaceVariant)
            Text(text = count.toInt().toString(), style = typography.bodyLg, fontWeight = FontWeight.Bold, color = colors.primary)
        }
        Slider(
            value = count,
            onValueChange = { count = it },
            valueRange = 1f..1000f,
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary,
                inactiveTrackColor = colors.outlineVariant
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "1", style = typography.labelSm, color = colors.onSurfaceVariant)
            Text(text = "1000", style = typography.labelSm, color = colors.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Delay Slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Delay Between Messages", style = typography.bodyMd, color = colors.onSurfaceVariant)
            Text(text = "${delayMs.toInt()}ms", style = typography.bodyLg, fontWeight = FontWeight.Bold, color = colors.primary)
        }
        Slider(
            value = delayMs,
            onValueChange = { delayMs = it },
            valueRange = 100f..5000f,
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary,
                inactiveTrackColor = colors.outlineVariant
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "100ms", style = typography.labelSm, color = colors.onSurfaceVariant)
            Text(text = "5000ms", style = typography.labelSm, color = colors.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Preview Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(radius.lgShape)
                .background(colors.surfaceDim)
                .border(1.dp, colors.outlineVariant, radius.lgShape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "Preview", style = typography.bodyLg, fontWeight = FontWeight.Bold, color = colors.onSurface)
            val rows = listOf(
                "Message" to (message.ifEmpty { "—" }),
                "Count" to "${count.toInt()} messages",
                "Delay" to "${delayMs.toInt()}ms between each",
                "Target" to (recipient.ifEmpty { "No recipient" })
            )
            rows.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = label, style = typography.bodyMd, color = colors.onSurfaceVariant)
                    Text(
                        text = value,
                        style = typography.bodyMd,
                        fontWeight = FontWeight.Medium,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(180.dp),
                        textAlign = TextAlign.End
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Progress
        if (executing) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Sending…", style = typography.bodyMd, color = colors.onSurfaceVariant)
                    Text(text = "$progress/${count.toInt()}", style = typography.bodyMd, fontWeight = FontWeight.Bold, color = colors.primary)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(colors.outlineVariant)
                ) {
                    val progressPct = if (count > 0) progress.toFloat() / count else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progressPct)
                            .clip(CircleShape)
                            .background(colors.primary)
                    )
                }
            }
        }

        // Danger Zone Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(radius.lgShape)
                .background(Color(0xFFFFF2F2))
                .border(1.dp, Color(0xFFFFCCCC), radius.lgShape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = WaexIcons.Warning,
                    contentDescription = null,
                    tint = Color(0xFFF44336),
                    modifier = Modifier.size(15.dp)
                )
                Text(text = "Danger Zone", style = typography.bodyMd, fontWeight = FontWeight.Bold, color = Color(0xFFF44336))
            }
            Button(
                onClick = { showConfirm = true },
                enabled = message.trim().isNotEmpty() && recipient.trim().isNotEmpty() && !executing,
                shape = radius.mdShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFFF44336),
                    disabledContainerColor = Color.White.copy(alpha = 0.5f)
                ),
                border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFFF8888)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Text(
                    text = if (executing) "Executing…" else "Execute Bombing",
                    style = typography.bodyMd,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }

    // Confirmation Modal Overlay
    if (showConfirm) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable { showConfirm = false },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = radius.lgShape,
                color = colors.surface,
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(16.dp)
                    .clickable(enabled = false) { }
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = WaexIcons.Warning,
                        contentDescription = null,
                        tint = Color(0xFFF44336),
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        text = "Confirm Execution",
                        style = typography.headlineMd,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    Text(
                        text = "Send ${count.toInt()} messages to $recipient with ${delayMs.toInt()}ms delay?",
                        style = typography.bodyMd,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { showConfirm = false },
                            shape = radius.mdShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.surfaceDim,
                                contentColor = colors.onSurfaceVariant
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "Cancel", style = typography.bodyMd)
                        }
                        Button(
                            onClick = {
                                showConfirm = false
                                executing = true
                                progress = 0
                                coroutineScope.launch {
                                    val step = Math.ceil(count / 20.0).toInt().coerceAtLeast(1)
                                    while (progress < count.toInt()) {
                                        delay(100)
                                        progress = (progress + step).coerceAtMost(count.toInt())
                                    }
                                    executing = false
                                }
                            },
                            shape = radius.mdShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFF44336),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "Execute", style = typography.bodyMd, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusVideoSplitterModal(
    onDismiss: () -> Unit
) {
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val coroutineScope = rememberCoroutineScope()

    var duration by remember { mutableStateOf("30") } // "30" | "60" | "90" | "custom"
    var customDuration by remember { mutableStateOf("45") }
    var processing by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var progressVal by remember { mutableStateOf(0) }

    val totalDuration = 187 // seconds
    val segDuration = if (duration == "custom") customDuration.toIntOrNull() ?: 30 else duration.toInt()
    val segments = Math.ceil(totalDuration.toDouble() / segDuration).toInt()
    val estSize = Math.round(128.0 / segments).toInt()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.pageMargin)
            .verticalScroll(rememberScrollState())
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
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text(
                    text = "Status Splitter",
                    style = typography.headlineMd,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
                Text(
                    text = "Split long video into status segments",
                    style = typography.bodyMd,
                    color = colors.onSurfaceVariant
                )
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
                    contentDescription = "Close",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        // Video Preview Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.77f)
                .clip(radius.lgShape)
                .background(colors.surfaceDim)
                .border(1.dp, colors.outlineVariant, radius.lgShape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.1f))
                        .clickable { },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = WaexIcons.Play,
                        contentDescription = "Play",
                        tint = colors.primary,
                        modifier = Modifier
                            .size(24.dp)
                            .padding(start = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "video_status.mp4", style = typography.bodyMd, fontWeight = FontWeight.Medium, color = colors.onSurface)
                Text(text = "3:07 · 128 MB", style = typography.labelSm, color = colors.onSurfaceVariant)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Duration Selection Chips
        Text(
            text = "Segment Duration",
            style = typography.labelSm,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("30", "60", "90", "custom").forEach { d ->
                val isSelected = duration == d
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isSelected) colors.primary else colors.surfaceDim)
                        .border(1.dp, if (isSelected) colors.primary else colors.outlineVariant, CircleShape)
                        .clickable {
                            duration = d
                            done = false
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (d == "custom") "Custom" else "${d}s",
                        style = typography.bodyMd,
                        fontWeight = FontWeight.Medium,
                        color = if (isSelected) colors.onPrimary else colors.onSurface
                    )
                }
            }
        }

        if (duration == "custom") {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(radius.mdShape)
                    .background(colors.surfaceDim)
                    .border(1.dp, colors.outlineVariant, radius.mdShape)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (customDuration.isEmpty()) {
                    Text(text = "Seconds", style = typography.bodyMd, color = colors.onSurfaceVariant.copy(alpha = 0.5f))
                }
                BasicTextField(
                    value = customDuration,
                    onValueChange = {
                        customDuration = it
                        done = false
                    },
                    singleLine = true,
                    textStyle = typography.bodyMd.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Output Preview Card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(radius.lgShape)
                .background(colors.surfaceDim)
                .border(1.dp, colors.outlineVariant, radius.lgShape)
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = segments.toString(), style = typography.headlineMd, fontWeight = FontWeight.Bold, color = colors.primary)
                Text(text = "Total Segments", style = typography.labelSm, color = colors.onSurfaceVariant)
            }
            Box(modifier = Modifier
                .width(1.dp)
                .height(40.dp)
                .background(colors.outlineVariant)
                .align(Alignment.CenterVertically))
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "${estSize}MB", style = typography.headlineMd, fontWeight = FontWeight.Bold, color = colors.onSurface)
                Text(text = "Est. Size Each", style = typography.labelSm, color = colors.onSurfaceVariant)
            }
            Box(modifier = Modifier
                .width(1.dp)
                .height(40.dp)
                .background(colors.outlineVariant)
                .align(Alignment.CenterVertically))
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "HD", style = typography.headlineMd, fontWeight = FontWeight.Bold, color = colors.onSurface)
                Text(text = "Output Quality", style = typography.labelSm, color = colors.onSurfaceVariant)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Progress
        if (processing || done) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = if (done) "Complete" else "Processing…", style = typography.bodyMd, color = colors.onSurfaceVariant)
                    Text(text = "$progressVal%", style = typography.bodyMd, fontWeight = FontWeight.Bold, color = colors.primary)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(colors.outlineVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progressVal.toFloat() / 100f)
                            .clip(CircleShape)
                            .background(colors.primary)
                    )
                }
                if (!done) {
                    val remaining = Math.round((100 - progressVal) * 0.08).toInt()
                    Text(text = "~${remaining}s remaining", style = typography.labelSm, color = colors.onSurfaceVariant)
                }
            }
        }

        // Action Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    processing = true
                    done = false
                    progressVal = 0
                    coroutineScope.launch {
                        while (progressVal < 100) {
                            delay(80)
                            progressVal += 5
                        }
                        processing = false
                        done = true
                    }
                },
                enabled = !processing,
                shape = radius.lgShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) {
                if (processing) {
                    CircularProgressIndicator(
                        color = colors.onPrimary,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Processing", style = typography.bodyMd)
                } else if (done) {
                    Text(text = "Generate Again", style = typography.bodyMd, fontWeight = FontWeight.Bold)
                } else {
                    Text(text = "Generate Segments", style = typography.bodyMd, fontWeight = FontWeight.Bold)
                }
            }
            Button(
                onClick = { },
                shape = radius.lgShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.surface,
                    contentColor = colors.onSurface
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier
                    .height(52.dp)
                    .padding(horizontal = 4.dp)
            ) {
                Icon(
                    imageVector = WaexIcons.Folder, // Draft folder
                    contentDescription = null,
                    tint = colors.onSurface,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Draft", style = typography.bodyMd)
            }
        }
    }
}
