package com.waenhancer.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme

@Composable
fun SystemHealthScreen() {
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    val totalLoaded = 247
    val totalFailed = 5
    val totalRetried = 8
    val successRate = 98.0f // 247 / (247 + 5) * 100

    var animationTriggered by remember { mutableStateOf(false) }
    val animatedSuccessRate by animateFloatAsState(
        targetValue = if (animationTriggered) successRate else 0f,
        animationSpec = tween(durationMillis = 1200),
        label = "success_rate_anim"
    )
    val navController = com.waenhancer.ui.navigation.LocalWaexNavController.current

    Scaffold(
        topBar = {
            com.waenhancer.ui.components.WaexTopBar(
                title = "System Health",
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
                .padding(vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {



        // Compatibility Overview Card
        Surface(
            shape = radius.bentoCardShape,
            color = colors.surfaceDim,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.pageMargin)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Icon(
                        imageVector = WaexIcons.Refresh, // Telemetry Cpu analogue
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Compatibility Overview",
                        style = typography.bodyLg,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                }

                val compatibilityData = listOf(
                    "WhatsApp Version" to "2.24.25.17",
                    "Architecture" to "arm64-v8a",
                    "ABI" to "armeabi-v7a",
                    "NDK Version" to "r26b",
                    "Min SDK" to "SDK 28+",
                    "Target SDK" to "34",
                    "Compile SDK" to "36",
                    "Java" to "Java 17",
                    "Module Version" to "v3.4.1"
                )

                val chunks = compatibilityData.chunked(2)
                chunks.forEachIndexed { index, rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        rowItems.forEach { (label, value) ->
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    text = label,
                                    style = typography.labelSm,
                                    color = colors.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = value,
                                    style = typography.bodyLg,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSurface
                                )
                            }
                        }
                        // Handle odd item in last row by adding a blank weight placeholder
                        if (rowItems.size < 2) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                    if (index < chunks.lastIndex) {
                        HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)
                    }
                }
            }
        }

        // Hook Telemetry Card
        Surface(
            shape = radius.bentoCardShape,
            color = colors.surfaceDim,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.pageMargin)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 20.dp)
                ) {
                    Icon(
                        imageVector = WaexIcons.Refresh,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Hook Telemetry",
                        style = typography.bodyLg,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                }

                // Success rate indicator & stats list
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Circular Progress gauge
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(90.dp)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawCircle(
                                color = Color(0xFFE5E5E5),
                                style = Stroke(width = 7.dp.toPx())
                            )
                            drawArc(
                                color = colors.primary,
                                startAngle = -90f,
                                sweepAngle = (animatedSuccessRate / 100f) * 360f,
                                useCenter = false,
                                style = Stroke(
                                    width = 7.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${animatedSuccessRate.toInt()}%",
                                style = typography.bodyLg,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface
                            )
                            Text(
                                text = "Success",
                                style = typography.labelSm,
                                color = colors.onSurfaceVariant,
                                fontSize = 9.sp
                            )
                        }
                    }

                    // Telemetry counts list
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Hooks Loaded", style = typography.bodyMd, color = colors.onSurfaceVariant)
                            Text(text = totalLoaded.toString(), style = typography.bodyMd, fontWeight = FontWeight.Bold, color = colors.onSurface)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Hooks Failed", style = typography.bodyMd, color = colors.onSurfaceVariant)
                            Text(text = totalFailed.toString(), style = typography.bodyMd, fontWeight = FontWeight.Bold, color = Color(0xFFF44336))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Hooks Retried", style = typography.bodyMd, color = colors.onSurfaceVariant)
                            Text(text = totalRetried.toString(), style = typography.bodyMd, fontWeight = FontWeight.Bold, color = Color(0xFFFFB300))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))

                // Hook Categories lists & progress loaders
                val hookCategories = listOf(
                    Triple("Privacy Hooks", 82, 84),
                    Triple("Media Hooks", 61, 62),
                    Triple("UI Hooks", 74, 76),
                    Triple("Automation Hooks", 30, 30)
                )

                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    hookCategories.forEach { (label, loaded, total) ->
                        val ratio = loaded.toFloat() / total.toFloat()
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = label, style = typography.bodyMd, color = colors.onSurfaceVariant)
                                Text(text = "$loaded/$total", style = typography.bodyMd, fontWeight = FontWeight.Bold, color = colors.onSurface)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE5E5E5))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(ratio)
                                        .clip(CircleShape)
                                        .background(colors.primary)
                                )
                            }
                        }
                    }
                }
            }
        }


        // System Validation Card
        Surface(
            shape = radius.bentoCardShape,
            color = colors.surfaceDim,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.pageMargin)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "System Validation",
                    style = typography.bodyLg,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                val checks = listOf(
                    "DexKit Loaded",
                    "Native Libraries Loaded",
                    "Database Available",
                    "IPC Available",
                    "Preferences Synced",
                    "Hook Registry Healthy"
                )

                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    checks.forEach { check ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = WaexIcons.Success,
                                contentDescription = "OK",
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(text = check, style = typography.bodyMd, color = colors.onSurface)
                        }
                    }
                }
            }
        }
    }
}
}

@Preview(showBackground = true)
@Composable
fun SystemHealthScreenPreview() {
    WaexTheme {
        SystemHealthScreen()
    }
}

