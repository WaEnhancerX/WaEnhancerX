package com.waenhancer.ui.screens.pro

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController

@Composable
fun ProUpgradePaywallScreen(
    onOpenModal: (String) -> Unit,
    onActivatePro: () -> Unit
) {
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var selectedPlan by remember { mutableStateOf("yearly") } // "monthly" | "yearly" | "lifetime"

    Scaffold(
        topBar = {
            WaexTopBar(
                title = "Pro Upgrade",
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


        // Featured Pro Tiles (Pitch Blue Cards)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.pageMargin),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val tiles = listOf(
                Pair("File Size Spoofer", "Override upload size limits up to 10 GB"),
                Pair("Message Bomber", "Automated message delivery with delay control"),
                Pair("Status Video Splitter", "Split long videos into 30s status segments"),
                Pair("Always Typing", "Maintain typing presence indefinitely")
            )
            tiles.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowItems.forEach { (title, desc) ->
                        Surface(
                            shape = radius.bentoCardShape,
                            color = colors.primary,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 120.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = WaexIcons.Premium, // Crown/Star icon analogue
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = title,
                                        style = typography.bodyMd.copy(fontWeight = FontWeight.Bold),
                                        color = colors.onPrimary
                                    )
                                    Text(
                                        text = desc,
                                        style = typography.labelSm,
                                        color = colors.onPrimary.copy(alpha = 0.7f),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Feature Comparison Table
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.pageMargin)
        ) {
            Text(
                text = "Free vs Pro Features",
                style = typography.labelSm,
                fontWeight = FontWeight.Bold,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceDim,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
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
                        Text(text = "Feature", style = typography.labelSm, fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant, modifier = Modifier.weight(1.5f))
                        Text(text = "Free", style = typography.labelSm, fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                        Text(text = "Pro", style = typography.labelSm, fontWeight = FontWeight.Bold, color = colors.primary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    }
                    HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)

                    val comparisonRows = listOf(
                        Triple("Always Typing", "✗", "✓"),
                        Triple("Custom Status View", "✗", "✓"),
                        Triple("Status Splitter", "✗", "✓"),
                        Triple("Voice Status Share", "✗", "✓"),
                        Triple("Delete Message File", "✗", "✓"),
                        Triple("Message Bomber", "✗", "✓"),
                        Triple("File Size Spoofer", "✗", "✓"),
                        Triple("Advanced Filters", "Basic", "Full"),
                        Triple("Unlimited Usage", "✗", "✓"),
                        Triple("Priority Updates", "✗", "✓"),
                        Triple("License Activation", "✗", "✓")
                    )

                    comparisonRows.forEachIndexed { idx, (feat, freeVal, proVal) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = feat, style = typography.bodyMd, color = colors.onSurface, modifier = Modifier.weight(1.5f))
                            Text(
                                text = freeVal,
                                style = typography.bodyMd,
                                fontWeight = if (freeVal == "✓") FontWeight.Bold else FontWeight.Medium,
                                color = if (freeVal == "✓") Color(0xFF4CAF50) else if (freeVal == "✗") colors.onSurfaceVariant.copy(alpha = 0.4f) else colors.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = proVal,
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

        // Choose Plan Card Selector list
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.pageMargin)
        ) {
            Text(
                text = "Choose Plan",
                style = typography.labelSm,
                fontWeight = FontWeight.Bold,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            val plans = listOf(
                Triple("monthly", Pair("Monthly", "/mo"), "$4.99"),
                Triple("yearly", Pair("Yearly", "/yr"), "$34.99"),
                Triple("lifetime", Pair("Lifetime", "once"), "$89.99")
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                plans.forEach { (planId, labelTuple, price) ->
                    val isSelected = selectedPlan == planId
                    val planBg = if (isSelected) colors.primaryContainer else colors.surfaceDim
                    val planBorderColor = if (isSelected) colors.primary else colors.outlineVariant

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(radius.lgShape)
                            .background(planBg)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = planBorderColor,
                                shape = radius.lgShape
                            )
                            .clickable { selectedPlan = planId }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Radio circle
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .border(2.dp, if (isSelected) colors.primary else colors.outlineVariant, CircleShape)
                                .padding(3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                        .background(colors.primary)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = labelTuple.first,
                                style = typography.bodyLg,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface
                            )
                            if (planId == "yearly") {
                                Text(
                                    text = "Save 42%",
                                    style = typography.labelSm,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.primary,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = price,
                                style = typography.headlineMd,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface
                            )
                            Text(
                                text = " ${labelTuple.second}",
                                style = typography.labelSm,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // CTA Buttons
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.pageMargin),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onActivatePro,
                shape = radius.lgShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(text = "Activate Pro", style = typography.bodyLg, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = { onOpenModal("license") },
                shape = radius.lgShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.surface,
                    contentColor = colors.onSurface
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(text = "I Have a License Key", style = typography.bodyMd, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Cancel anytime in settings. Terms and privacy conditions apply.",
                style = typography.labelSm,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                fontSize = 11.sp
            )
        }
        Spacer(modifier = Modifier.height(100.dp))
    }
}
}

@Preview(showBackground = true)
@Composable
fun ProUpgradePaywallScreenPreview() {
    WaexTheme {
        ProUpgradePaywallScreen(onOpenModal = {}, onActivatePro = {})
    }
}
