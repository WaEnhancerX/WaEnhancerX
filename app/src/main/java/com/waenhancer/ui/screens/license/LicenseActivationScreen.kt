package com.waenhancer.ui.screens.license

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
import com.waenhancer.ui.navigation.LocalWaexNavController

@Composable
fun LicenseActivationScreen() {
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var licenseKey by remember { mutableStateOf("") }
    var isActivated by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = "License Activation",
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
            WaexSectionHeader(
                title = "Activate License Key",
                subtitle = "Input your purchased key to verify your license."
            )

            // Key Input Box
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = radius.mdShape,
                color = colors.surfaceContainerLow,
                border = BorderStroke(1.dp, if (licenseKey.isNotEmpty()) colors.primaryContainer else colors.outlineVariant)
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
                        if (licenseKey.isEmpty()) {
                            Text(
                                text = "Enter Key: e.g. WAEX-XXXX-XXXX...",
                                style = typography.bodyLg,
                                color = colors.onSurfaceVariant
                            )
                        }
                        BasicTextField(
                            value = licenseKey,
                            onValueChange = { licenseKey = it },
                            singleLine = true,
                            textStyle = typography.bodyLg.copy(color = colors.onSurface),
                            cursorBrush = SolidColor(colors.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // CTA Button
            Button(
                onClick = { if (licenseKey.isNotEmpty()) isActivated = true },
                shape = radius.buttonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primaryContainer,
                    contentColor = colors.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = licenseKey.isNotEmpty()
            ) {
                Text(text = "Verify & Activate License", style = typography.bodyLg.copy(fontWeight = FontWeight.Bold))
            }

            // Info / Status Notification
            if (isActivated) {
                WaexInfoBanner(
                    message = "License key verified successfully. All premium Pro features have been unlocked on this device.",
                    bannerType = com.waenhancer.ui.components.BannerType.INFO,
                    title = "Activation Status: Pro Active"
                )
            } else {
                WaexInfoBanner(
                    message = "Your active license is currently marked as Free Plan. Buy a license key or unlock via standard paywall to bypass system limits.",
                    bannerType = com.waenhancer.ui.components.BannerType.WARNING,
                    title = "Activation Status: Inactive"
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
                                tint = if (isActivated) colors.primaryContainer else colors.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(spacing.stackSm))
                            Text(
                                text = benefit,
                                style = typography.bodyMd,
                                color = if (isActivated) colors.onSurface else colors.onSurfaceVariant
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
}

@Preview(showBackground = true)
@Composable
fun LicenseActivationScreenPreview() {
    WaexTheme {
        LicenseActivationScreen()
    }
}
