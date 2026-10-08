package com.waenhancer.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.components.StitchSwitch
import com.waenhancer.ui.components.WaexCard
import com.waenhancer.ui.components.WaexProChip
import com.waenhancer.ui.components.WaexSectionHeader
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalIsPro
import com.waenhancer.ui.navigation.LocalOnActivatePro
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloatingBottomBarCustomizerScreen() {
    val navController = LocalWaexNavController.current
    val preferenceManager = LocalWaexPreferenceManager.current
    val isPro = LocalIsPro.current
    val onActivatePro = LocalOnActivatePro.current

    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var enabled by remember {
        mutableStateOf(preferenceManager.getBoolean("floating_bottom_bar", true))
    }
    var pillDesign by remember {
        mutableStateOf(preferenceManager.getString("floating_bottom_bar_pill_design", "regular") ?: "regular")
    }
    var glassEnabled by remember {
        mutableStateOf(preferenceManager.getBoolean("floating_bottom_bar_glass", false))
    }
    var glassOpacity by remember {
        mutableFloatStateOf(preferenceManager.getFloat("floating_bottom_bar_glass_opacity", 35f))
    }
    var cornerRadius by remember {
        mutableFloatStateOf(preferenceManager.getInt("floating_bottom_bar_radius", 28).toFloat())
    }
    var marginBottom by remember {
        mutableFloatStateOf(preferenceManager.getInt("floating_bottom_bar_margin_bottom", 22).toFloat())
    }
    var marginHorizontal by remember {
        mutableFloatStateOf(preferenceManager.getInt("floating_bottom_bar_margin_horizontal", 16).toFloat())
    }
    var paddingVertical by remember {
        mutableFloatStateOf(preferenceManager.getInt("floating_bottom_bar_padding_vertical", 6).toFloat())
    }
    var iconSize by remember {
        mutableFloatStateOf(preferenceManager.getInt("floating_bottom_bar_icon_size", 24).toFloat())
    }
    var textSize by remember {
        mutableFloatStateOf(preferenceManager.getInt("floating_bottom_bar_text_size", 12).toFloat())
    }
    var iconLabelSpacing by remember {
        mutableFloatStateOf(preferenceManager.getInt("floating_bottom_bar_icon_label_spacing", 2).toFloat())
    }
    var scrollHideEnabled by remember {
        mutableStateOf(preferenceManager.getBoolean("floating_bottom_bar_scroll_hide", true))
    }
    var scrollHideMode by remember {
        mutableStateOf(preferenceManager.getString("floating_bottom_bar_scroll_hide_mode", "downward") ?: "downward")
    }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = "Floating Bottom Bar",
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
            // Live Interactive Preview Card
            WaexSectionHeader(
                title = "Live Preview",
                subtitle = "Real-time preview of how WhatsApp bottom navigation pill looks"
            )

            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceDim,
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0B141A)) // WhatsApp dark preview background
                        .padding(horizontal = (marginHorizontal * 0.7f).dp, vertical = 12.dp)
                ) {
                    // Mock Chat List items in background
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        repeat(3) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF1F2C34))
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(width = 100.dp, height = 10.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(Color(0xFF2A3942))
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(width = 160.dp, height = 8.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(Color(0xFF1F2C34))
                                    )
                                }
                            }
                        }
                    }

                    // Floating Pill Mock
                    val previewPillShape = RoundedCornerShape((cornerRadius * 0.8f).dp)
                    val previewBgColor = if (glassEnabled) {
                        val alphaVal = (glassOpacity / 100f).coerceIn(0.15f, 0.9f)
                        Color(0xFF1F2C34).copy(alpha = alphaVal)
                    } else {
                        Color(0xFF1F2C34)
                    }

                    Surface(
                        shape = previewPillShape,
                        color = previewBgColor,
                        border = BorderStroke(1.dp, if (glassEnabled) Color.White.copy(alpha = 0.2f) else Color(0x18FFFFFF)),
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(bottom = (marginBottom * 0.5f).coerceAtLeast(4f).dp)
                            .clip(previewPillShape)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = (paddingVertical * 0.8f).coerceAtLeast(4f).dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Tab 1: Chats (Active)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy((iconLabelSpacing * 0.6f).dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(Color(0xFF00A884).copy(alpha = 0.2f))
                                        .padding(horizontal = 10.dp, vertical = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = WaexIcons.GridView,
                                        contentDescription = null,
                                        tint = Color(0xFF00A884),
                                        modifier = Modifier.size((iconSize * 0.8f).coerceIn(16f, 24f).dp)
                                    )
                                }
                                Text(
                                    text = "Chats",
                                    fontSize = (textSize * 0.85f).coerceIn(9f, 13f).sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00A884)
                                )
                            }

                            // Tab 2: Updates
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy((iconLabelSpacing * 0.6f).dp)
                            ) {
                                Icon(
                                    imageVector = WaexIcons.AutoAwesome,
                                    contentDescription = null,
                                    tint = Color(0xFF8696A0),
                                    modifier = Modifier.size((iconSize * 0.8f).coerceIn(16f, 24f).dp)
                                )
                                Text(
                                    text = "Updates",
                                    fontSize = (textSize * 0.85f).coerceIn(9f, 13f).sp,
                                    color = Color(0xFF8696A0)
                                )
                            }

                            // Tab 3: Communities
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy((iconLabelSpacing * 0.6f).dp)
                            ) {
                                Icon(
                                    imageVector = WaexIcons.Contacts,
                                    contentDescription = null,
                                    tint = Color(0xFF8696A0),
                                    modifier = Modifier.size((iconSize * 0.8f).coerceIn(16f, 24f).dp)
                                )
                                Text(
                                    text = "Communities",
                                    fontSize = (textSize * 0.85f).coerceIn(9f, 13f).sp,
                                    color = Color(0xFF8696A0)
                                )
                            }

                            // Tab 4: Calls
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy((iconLabelSpacing * 0.6f).dp)
                            ) {
                                Icon(
                                    imageVector = WaexIcons.Smartphone,
                                    contentDescription = null,
                                    tint = Color(0xFF8696A0),
                                    modifier = Modifier.size((iconSize * 0.8f).coerceIn(16f, 24f).dp)
                                )
                                Text(
                                    text = "Calls",
                                    fontSize = (textSize * 0.85f).coerceIn(9f, 13f).sp,
                                    color = Color(0xFF8696A0)
                                )
                            }
                        }
                    }
                }
            }

            // Master Switch
            WaexCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Enable Floating Bottom Bar",
                            style = typography.bodyLg,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                        Text(
                            text = "Transforms the default navigation bar into a modern floating pill",
                            style = typography.bodyMd,
                            color = colors.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                    StitchSwitch(
                        checked = enabled,
                        onCheckedChange = { isChecked ->
                            enabled = isChecked
                            preferenceManager.putBoolean("floating_bottom_bar", isChecked)
                        }
                    )
                }
            }

            AnimatedVisibility(visible = enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.stackLg)) {

                    // Pill Style Selection
                    WaexSectionHeader(
                        title = "Pill Design Preset",
                        subtitle = "Select overall aesthetic theme for the floating bar"
                    )

                    Surface(
                        shape = radius.bentoCardShape,
                        color = colors.surfaceDim,
                        border = BorderStroke(1.dp, colors.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                Triple("regular", "Classic (Regular)", "Solid themed floating pill with sleek outline"),
                                Triple("pro", "Refined (Pro)", "Enhanced adaptive shadows and refined padding"),
                                Triple("ios_glass", "iOS Frosted Glass", "Translucent glassmorphism with refraction border")
                            ).forEach { (styleKey, title, desc) ->
                                val isSelected = pillDesign == styleKey
                                val isLocked = styleKey != "regular" && !isPro

                                Surface(
                                    shape = radius.mdShape,
                                    color = if (isSelected) colors.primary.copy(alpha = 0.12f) else colors.surface,
                                    border = BorderStroke(1.dp, if (isSelected) colors.primary else colors.outlineVariant),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(radius.mdShape)
                                        .clickable {
                                            if (isLocked) {
                                                onActivatePro()
                                            } else {
                                                pillDesign = styleKey
                                                preferenceManager.putString("floating_bottom_bar_pill_design", styleKey)
                                                if (styleKey == "ios_glass") {
                                                    glassEnabled = true
                                                    preferenceManager.putBoolean("floating_bottom_bar_glass", true)
                                                }
                                            }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = null,
                                            colors = RadioButtonDefaults.colors(selectedColor = colors.primary)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = title,
                                                    style = typography.bodyMd,
                                                    fontWeight = FontWeight.Bold,
                                                    color = colors.onSurface
                                                )
                                                if (styleKey != "regular") {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    WaexProChip(isUnlocked = isPro)
                                                }
                                            }
                                            Text(
                                                text = desc,
                                                style = typography.labelSm,
                                                color = colors.onSurfaceVariant,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Geometry & Dimensions Controls
                    WaexSectionHeader(
                        title = "Geometry & Spacing",
                        subtitle = "Adjust corner curves, margins, and sizing"
                    )

                    WaexCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Corner Radius
                            SliderItem(
                                label = "Corner Radius",
                                value = cornerRadius,
                                valueRange = 12f..36f,
                                unit = "dp",
                                onValueChange = {
                                    cornerRadius = it
                                    preferenceManager.putInt("floating_bottom_bar_radius", it.toInt())
                                }
                            )

                            HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.5f))

                            // Bottom Margin
                            SliderItem(
                                label = "Bottom Margin",
                                value = marginBottom,
                                valueRange = 8f..36f,
                                unit = "dp",
                                onValueChange = {
                                    marginBottom = it
                                    preferenceManager.putInt("floating_bottom_bar_margin_bottom", it.toInt())
                                }
                            )

                            HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.5f))

                            // Side Margin
                            SliderItem(
                                label = "Horizontal Margin",
                                value = marginHorizontal,
                                valueRange = 4f..32f,
                                unit = "dp",
                                onValueChange = {
                                    marginHorizontal = it
                                    preferenceManager.putInt("floating_bottom_bar_margin_horizontal", it.toInt())
                                }
                            )

                            HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.5f))

                            // Vertical Padding
                            SliderItem(
                                label = "Vertical Inner Padding",
                                value = paddingVertical,
                                valueRange = 2f..14f,
                                unit = "dp",
                                onValueChange = {
                                    paddingVertical = it
                                    preferenceManager.putInt("floating_bottom_bar_padding_vertical", it.toInt())
                                }
                            )

                            HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.5f))

                            // Icon Size
                            SliderItem(
                                label = "Icon Size",
                                value = iconSize,
                                valueRange = 18f..30f,
                                unit = "dp",
                                onValueChange = {
                                    iconSize = it
                                    preferenceManager.putInt("floating_bottom_bar_icon_size", it.toInt())
                                }
                            )

                            HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.5f))

                            // Text Size
                            SliderItem(
                                label = "Text Size",
                                value = textSize,
                                valueRange = 9f..15f,
                                unit = "sp",
                                onValueChange = {
                                    textSize = it
                                    preferenceManager.putInt("floating_bottom_bar_text_size", it.toInt())
                                }
                            )

                            HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.5f))

                            SliderItem(
                                label = "Icon & Label Spacing",
                                value = iconLabelSpacing,
                                valueRange = 0f..8f,
                                unit = "dp",
                                onValueChange = {
                                    iconLabelSpacing = it
                                    preferenceManager.putInt("floating_bottom_bar_icon_label_spacing", it.toInt())
                                }
                            )
                        }
                    }

                    // Glassmorphism & Scroll Hide
                    WaexSectionHeader(
                        title = "Behavior & Translucency",
                        subtitle = "Configure scroll hide dynamics and backdrop opacity"
                    )

                    WaexCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Glassmorphism switch
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "Frosted Glass Effect",
                                        style = typography.bodyMd,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = "Translucent background with blurred backdrop",
                                        style = typography.labelSm,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                StitchSwitch(
                                    checked = glassEnabled,
                                    onCheckedChange = { isChecked ->
                                        glassEnabled = isChecked
                                        preferenceManager.putBoolean("floating_bottom_bar_glass", isChecked)
                                    }
                                )
                            }

                            if (glassEnabled) {
                                SliderItem(
                                    label = "Glass Opacity",
                                    value = glassOpacity,
                                    valueRange = 10f..90f,
                                    unit = "%",
                                    onValueChange = {
                                        glassOpacity = it
                                        preferenceManager.putFloat("floating_bottom_bar_glass_opacity", it)
                                    }
                                )
                            }

                            HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.5f))

                            // Scroll Hide switch
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "Hide on Scroll",
                                        style = typography.bodyMd,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = "Automatically hides pill when scrolling chat lists",
                                        style = typography.labelSm,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                StitchSwitch(
                                    checked = scrollHideEnabled,
                                    onCheckedChange = { isChecked ->
                                        scrollHideEnabled = isChecked
                                        preferenceManager.putBoolean("floating_bottom_bar_scroll_hide", isChecked)
                                    }
                                )
                            }

                            if (scrollHideEnabled) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    listOf("downward" to "Slide Down", "invisible" to "Fade Out").forEach { (modeKey, title) ->
                                        val isSelected = scrollHideMode == modeKey
                                        Surface(
                                            shape = radius.smShape,
                                            color = if (isSelected) colors.primary.copy(alpha = 0.15f) else colors.surfaceDim,
                                            border = BorderStroke(1.dp, if (isSelected) colors.primary else colors.outlineVariant),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(radius.smShape)
                                                .clickable {
                                                    scrollHideMode = modeKey
                                                    preferenceManager.putString("floating_bottom_bar_scroll_hide_mode", modeKey)
                                                }
                                                .padding(vertical = 10.dp),
                                        ) {
                                            Text(
                                                text = title,
                                                style = typography.labelSm,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) colors.primary else colors.onSurfaceVariant,
                                                modifier = Modifier.fillMaxWidth(),
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
private fun SliderItem(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    unit: String,
    onValueChange: (Float) -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = typography.bodyMd,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface
            )
            Text(
                text = "${value.toInt()} $unit",
                style = typography.labelSm,
                fontWeight = FontWeight.Bold,
                color = colors.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary,
                inactiveTrackColor = colors.outlineVariant
            )
        )
    }
}
