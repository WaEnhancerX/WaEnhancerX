package com.waenhancer.ui.screens.settings

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.components.StitchSwitch
import com.waenhancer.ui.components.WaexCard
import com.waenhancer.ui.components.WaexProChip
import com.waenhancer.ui.components.WaexSectionHeader
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.components.WaexColorPickerDialog
import com.waenhancer.ui.components.WaexModalBottomSheet
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalIsPro
import com.waenhancer.ui.navigation.LocalOnActivatePro
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import androidx.compose.ui.text.style.TextAlign
import android.graphics.Color as AndroidColor

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
        mutableStateOf(preferenceManager.getBoolean("floating_bottom_bar", false))
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
    var shadowEnabled by remember {
        mutableStateOf(preferenceManager.getBoolean("floating_bottom_bar_shadow", true))
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
    var paddingHorizontal by remember {
        mutableFloatStateOf(preferenceManager.getInt("floating_bottom_bar_padding_horizontal", 0).toFloat())
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
    var iconsOnly by remember {
        mutableStateOf(preferenceManager.getBoolean("floating_bottom_bar_icons_only", false))
    }
    var selectorType by remember {
        mutableStateOf(preferenceManager.getString("floating_bottom_bar_selector_type", "default") ?: "default")
    }
    var selectorColor by remember {
        mutableIntStateOf(preferenceManager.getInt("floating_bottom_bar_selector_color", 0))
    }
    var selectorColorEnabled by remember {
        mutableStateOf(preferenceManager.getBoolean("floating_bottom_bar_selector_color_enabled", selectorColor != 0))
    }
    var showSelectorColorPicker by remember { mutableStateOf(false) }
    var selectedIconColor by remember {
        mutableIntStateOf(preferenceManager.getInt("floating_bottom_bar_selected_icon_color", 0))
    }
    var selectedIconColorEnabled by remember {
        mutableStateOf(preferenceManager.getBoolean("floating_bottom_bar_selected_icon_color_enabled", selectedIconColor != 0))
    }
    var unselectedIconColor by remember {
        mutableIntStateOf(preferenceManager.getInt("floating_bottom_bar_unselected_icon_color", 0))
    }
    var unselectedIconColorEnabled by remember {
        mutableStateOf(preferenceManager.getBoolean("floating_bottom_bar_unselected_icon_color_enabled", unselectedIconColor != 0))
    }
    var showSelectedIconColorPicker by remember { mutableStateOf(false) }
    var showUnselectedIconColorPicker by remember { mutableStateOf(false) }
    var scrollHideEnabled by remember {
        mutableStateOf(preferenceManager.getBoolean("floating_bottom_bar_scroll_hide", true))
    }
    var scrollHideMode by remember {
        mutableStateOf(preferenceManager.getString("floating_bottom_bar_scroll_hide_mode", "downward") ?: "downward")
    }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = UiText.get(R.string.feat_floating_bottom_bar_title, "Floating Bottom Bar"),
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
                title = UiText.get(R.string.waex_ui_live_preview, "Live Preview"),
                subtitle = UiText.get(R.string.waex_ui_real_time_preview_of_how_whatsapp_bott, "Real-time preview of how WhatsApp bottom navigation pill looks")
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
                    val isIosGlass = pillDesign == "ios_glass"
                    val isRefined = pillDesign == "pro"
                    val previewPillShape = if (isIosGlass) {
                        RoundedCornerShape(50)
                    } else {
                        RoundedCornerShape((cornerRadius * 0.8f).dp)
                    }
                    val previewUsesGlass = glassEnabled || isIosGlass
                    val previewBgColor = if (previewUsesGlass) {
                        val alphaVal = (glassOpacity / 100f).coerceIn(0.15f, 0.9f)
                        Color(0xFF1F2C34).copy(alpha = alphaVal)
                    } else {
                        Color(0xFF1F2C34)
                    }

                    Surface(
                        shape = previewPillShape,
                        color = previewBgColor,
                        border = BorderStroke(1.dp, if (previewUsesGlass) Color.White.copy(alpha = 0.32f) else Color(0x18FFFFFF)),
                        shadowElevation = if (shadowEnabled) {
                            if (isRefined) 12.dp else 8.dp
                        } else 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(bottom = (marginBottom * 0.5f).coerceAtLeast(4f).dp)
                            .clip(previewPillShape)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    vertical = (paddingVertical * 0.8f).coerceAtLeast(4f).dp,
                                    horizontal = paddingHorizontal.coerceAtLeast(0f).dp
                                ),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Tab 1: Chats (Active)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = if (!iconsOnly) Arrangement.spacedBy((iconLabelSpacing * 0.6f).dp) else Arrangement.Center
                            ) {
                                val activeShape = RoundedCornerShape(50)
                                val defaultActiveFill = when {
                                    isIosGlass -> Color.White.copy(alpha = 0.16f)
                                    isRefined -> Color.White.copy(alpha = 0.12f)
                                    else -> Color(0xFF00A884).copy(alpha = 0.2f)
                                }
                                val activeFill = if (selectorColorEnabled) Color(selectorColor) else defaultActiveFill
                                val activeTint = if (selectedIconColorEnabled) {
                                    Color(selectedIconColor)
                                } else if (isIosGlass || isRefined) Color.White else Color(0xFF00A884)
                                Box(
                                    modifier = Modifier
                                        .clip(activeShape)
                                        .background(activeFill)
                                        .then(
                                            if (isIosGlass || isRefined) {
                                                Modifier.border(1.dp, Color.White.copy(alpha = 0.32f), activeShape)
                                            } else Modifier
                                        )
                                        .then(
                                            if (selectorType == "oval") {
                                                Modifier.size(width = 60.dp, height = 42.dp)
                                            } else if (iconsOnly) {
                                                Modifier.size(width = 48.dp, height = 36.dp)
                                            } else {
                                                Modifier.padding(
                                                    horizontal = if (isIosGlass || isRefined) 13.dp else 10.dp,
                                                    vertical = if (isIosGlass || isRefined) 5.dp else 2.dp
                                                )
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = WaexIcons.GridView,
                                        contentDescription = null,
                                        tint = activeTint,
                                        modifier = Modifier.size((iconSize * 0.8f).coerceIn(16f, 24f).dp)
                                    )
                                }
                                if (!iconsOnly) {
                                    Text(
                                        text = UiText.get(R.string.waex_ui_chats, "Chats"),
                                        fontSize = (textSize * 0.85f).coerceIn(9f, 13f).sp,
                                        fontWeight = FontWeight.Bold,
                                        color = activeTint
                                    )
                                }
                            }

                            // Tab 2: Updates
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = if (!iconsOnly) Arrangement.spacedBy((iconLabelSpacing * 0.6f).dp) else Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = WaexIcons.AutoAwesome,
                                    contentDescription = null,
                                    tint = if (unselectedIconColorEnabled) Color(unselectedIconColor) else Color(0xFF8696A0),
                                    modifier = Modifier.size((iconSize * 0.8f).coerceIn(16f, 24f).dp)
                                )
                                if (!iconsOnly) {
                                    Text(
                                        text = UiText.get(R.string.waex_ui_updates, "Updates"),
                                        fontSize = (textSize * 0.85f).coerceIn(9f, 13f).sp,
                                        color = Color(0xFF8696A0)
                                    )
                                }
                            }

                            // Tab 3: Communities
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = if (!iconsOnly) Arrangement.spacedBy((iconLabelSpacing * 0.6f).dp) else Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = WaexIcons.Contacts,
                                    contentDescription = null,
                                    tint = if (unselectedIconColorEnabled) Color(unselectedIconColor) else Color(0xFF8696A0),
                                    modifier = Modifier.size((iconSize * 0.8f).coerceIn(16f, 24f).dp)
                                )
                                if (!iconsOnly) {
                                    Text(
                                        text = UiText.get(R.string.waex_ui_communities, "Communities"),
                                        fontSize = (textSize * 0.85f).coerceIn(9f, 13f).sp,
                                        color = Color(0xFF8696A0)
                                    )
                                }
                            }

                            // Tab 4: Calls
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = if (!iconsOnly) Arrangement.spacedBy((iconLabelSpacing * 0.6f).dp) else Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = WaexIcons.Smartphone,
                                    contentDescription = null,
                                    tint = if (unselectedIconColorEnabled) Color(unselectedIconColor) else Color(0xFF8696A0),
                                    modifier = Modifier.size((iconSize * 0.8f).coerceIn(16f, 24f).dp)
                                )
                                if (!iconsOnly) {
                                    Text(
                                        text = UiText.get(R.string.waex_ui_calls, "Calls"),
                                        fontSize = (textSize * 0.85f).coerceIn(9f, 13f).sp,
                                        color = Color(0xFF8696A0)
                                    )
                                }
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
                            text = UiText.get(R.string.waex_ui_enable_floating_bottom_bar, "Enable Floating Bottom Bar"),
                            style = typography.bodyLg,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                        Text(
                            text = UiText.get(R.string.waex_ui_transforms_the_default_navigation_bar, "Transforms the default navigation bar into a modern floating pill"),
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
                        title = UiText.get(R.string.waex_ui_pill_design_preset, "Pill Design Preset"),
                        subtitle = UiText.get(R.string.waex_ui_select_overall_aesthetic_theme_for_the, "Select overall aesthetic theme for the floating bar")
                    )

                    Surface(
                        shape = radius.bentoCardShape,
                        color = colors.surfaceDim,
                        border = BorderStroke(1.dp, colors.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                Triple("regular", UiText.get(R.string.waex_ui_classic_regular, "Classic (Regular)"), UiText.get(R.string.waex_ui_solid_themed_floating_pill_with_sleek, "Solid themed floating pill with sleek outline")),
                                Triple("pro", UiText.get(R.string.waex_ui_refined_pro, "Refined (Pro)"), UiText.get(R.string.waex_ui_enhanced_adaptive_shadows_and_refined, "Enhanced adaptive shadows and refined padding")),
                                Triple("ios_glass", UiText.get(R.string.waex_ui_ios_frosted_glass, "iOS Frosted Glass"), UiText.get(R.string.waex_ui_translucent_glassmorphism_with_refract, "Translucent glassmorphism with refraction border"))
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
                        title = UiText.get(R.string.waex_ui_geometry_spacing, "Geometry & Spacing"),
                        subtitle = UiText.get(R.string.waex_ui_adjust_corner_curves_margins_and_sizin, "Adjust corner curves, margins, and sizing")
                    )

                    WaexCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Corner Radius
                            SliderItem(
                                label = UiText.get(R.string.waex_ui_corner_radius, "Corner Radius"),
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
                                label = UiText.get(R.string.waex_ui_bottom_margin, "Bottom Margin"),
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
                                label = UiText.get(R.string.waex_ui_horizontal_margin, "Horizontal Margin"),
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
                                label = UiText.get(R.string.waex_ui_vertical_inner_padding, "Vertical Inner Padding"),
                                value = paddingVertical,
                                valueRange = 2f..14f,
                                unit = "dp",
                                onValueChange = {
                                    paddingVertical = it
                                    preferenceManager.putInt("floating_bottom_bar_padding_vertical", it.toInt())
                                }
                            )

                            HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.5f))

                            SliderItem(
                                label = UiText.get(R.string.waex_ui_horizontal_inner_padding, "Horizontal Inner Padding"),
                                value = paddingHorizontal,
                                valueRange = 0f..32f,
                                unit = "dp",
                                onValueChange = {
                                    paddingHorizontal = it
                                    preferenceManager.putInt("floating_bottom_bar_padding_horizontal", it.toInt())
                                }
                            )

                            HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.5f))

                            // Icon Size
                            SliderItem(
                                label = UiText.get(R.string.waex_ui_icon_size, "Icon Size"),
                                value = iconSize,
                                valueRange = 18f..30f,
                                unit = "dp",
                                onValueChange = {
                                    iconSize = it
                                    preferenceManager.putInt("floating_bottom_bar_icon_size", it.toInt())
                                }
                            )

                            HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.5f))

                            Text(
                                text = UiText.get(R.string.waex_ui_selector_type, "Selector Type"),
                                style = typography.bodyMd,
                                fontWeight = FontWeight.Medium,
                                color = colors.onSurface
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                listOf(
                                    "default" to UiText.get(R.string.waex_ui_default, "Default"),
                                    "oval" to UiText.get(R.string.waex_ui_oval, "Oval")
                                ).forEach { (typeKey, title) ->
                                    val isSelected = selectorType == typeKey
                                    Surface(
                                        shape = radius.smShape,
                                        color = if (isSelected) colors.primary.copy(alpha = 0.15f) else colors.surfaceDim,
                                        border = BorderStroke(1.dp, if (isSelected) colors.primary else colors.outlineVariant),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(radius.smShape)
                                            .clickable {
                                                selectorType = typeKey
                                                preferenceManager.putString("floating_bottom_bar_selector_type", typeKey)
                                            }
                                            .padding(vertical = 10.dp)
                                    ) {
                                        Text(
                                            text = title,
                                            style = typography.labelSm,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) colors.primary else colors.onSurfaceVariant,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.5f))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(radius.smShape)
                                    .clickable { showSelectorColorPicker = true }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = UiText.get(R.string.waex_ui_selector_color, "Selector Color"),
                                        style = typography.bodyMd,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = if (!selectorColorEnabled) {
                                            UiText.get(R.string.waex_ui_use_default_selector_color, "Using WhatsApp's default selector color")
                                        } else String.format("#%08X", selectorColor),
                                        style = typography.labelSm,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                if (!selectorColorEnabled) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = colors.surfaceDim,
                                        border = BorderStroke(1.dp, colors.outlineVariant)
                                    ) {
                                        Text(
                                            text = UiText.get(R.string.waex_ui_default, "Default"),
                                            style = typography.labelSm,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(Color(selectorColor))
                                            .border(1.dp, colors.outlineVariant, CircleShape)
                                    )
                                }
                            }

                            HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.5f))

                            IconColorSettingRow(
                                title = UiText.get(R.string.waex_ui_selected_icon_color, "Selected Icon Color"),
                                isEnabled = selectedIconColorEnabled,
                                color = selectedIconColor,
                                onClick = { showSelectedIconColorPicker = true }
                            )

                            HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.5f))

                            IconColorSettingRow(
                                title = UiText.get(R.string.waex_ui_unselected_icon_color, "Unselected Icon Color"),
                                isEnabled = unselectedIconColorEnabled,
                                color = unselectedIconColor,
                                onClick = { showUnselectedIconColorPicker = true }
                            )

                            HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.5f))

                            // Icons Only switch
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = UiText.get(R.string.waex_ui_icons_only, "Icons Only"),
                                        style = typography.bodyMd,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = UiText.get(R.string.waex_ui_icons_only_desc, "Hide tab labels and show icons only"),
                                        style = typography.labelSm,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                StitchSwitch(
                                    checked = iconsOnly,
                                    onCheckedChange = { isChecked ->
                                        iconsOnly = isChecked
                                        preferenceManager.putBoolean("floating_bottom_bar_icons_only", isChecked)
                                    }
                                )
                            }

                            if (!iconsOnly) {
                                HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.5f))

                                // Text Size
                                SliderItem(
                                    label = UiText.get(R.string.waex_ui_text_size, "Text Size"),
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
                                    label = UiText.get(R.string.waex_ui_icon_label_spacing, "Icon & Label Spacing"),
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
                    }

                    // Glassmorphism & Scroll Hide
                    WaexSectionHeader(
                        title = UiText.get(R.string.waex_ui_behavior_translucency, "Behavior & Translucency"),
                        subtitle = UiText.get(R.string.waex_ui_configure_scroll_hide_dynamics_and_bac, "Configure scroll hide dynamics and backdrop opacity")
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
                                        text = UiText.get(R.string.waex_ui_frosted_glass_effect, "Frosted Glass Effect"),
                                        style = typography.bodyMd,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = UiText.get(R.string.waex_ui_translucent_background_with_blurred_ba, "Translucent background with blurred backdrop"),
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
                                    label = UiText.get(R.string.waex_ui_glass_opacity, "Glass Opacity"),
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

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = UiText.get(R.string.waex_ui_bottom_bar_shadow, "Shadow"),
                                        style = typography.bodyMd,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = UiText.get(R.string.waex_ui_bottom_bar_shadow_desc, "Show a shadow beneath the floating bar"),
                                        style = typography.labelSm,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                StitchSwitch(
                                    checked = shadowEnabled,
                                    onCheckedChange = { isChecked ->
                                        shadowEnabled = isChecked
                                        preferenceManager.putBoolean("floating_bottom_bar_shadow", isChecked)
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
                                        text = UiText.get(R.string.waex_ui_hide_on_scroll, "Hide on Scroll"),
                                        style = typography.bodyMd,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = UiText.get(R.string.waex_ui_automatically_hides_pill_when_scrollin, "Automatically hides pill when scrolling chat lists"),
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
                                    listOf("downward" to UiText.get(R.string.waex_ui_slide_down, "Slide Down"), "invisible" to UiText.get(R.string.waex_ui_fade_out, "Fade Out")).forEach { (modeKey, title) ->
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
                                                textAlign = TextAlign.Center
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

    if (showSelectorColorPicker) {
        WaexColorPickerDialog(
            initialColorHex = String.format("#%08X", if (!selectorColorEnabled) 0xFF00A884.toInt() else selectorColor),
            onColorSelected = { hex ->
                try {
                    selectorColor = AndroidColor.parseColor(hex)
                    preferenceManager.putInt("floating_bottom_bar_selector_color", selectorColor)
                    selectorColorEnabled = true
                    preferenceManager.putBoolean("floating_bottom_bar_selector_color_enabled", true)
                } catch (_: IllegalArgumentException) {}
                showSelectorColorPicker = false
            },
            onDismiss = { showSelectorColorPicker = false },
            enableOpacity = true,
            onUseDefault = {
                selectorColorEnabled = false
                preferenceManager.remove("floating_bottom_bar_selector_color")
                preferenceManager.remove("floating_bottom_bar_selector_color_enabled")
            }
        )
    }

    if (showSelectedIconColorPicker) {
        WaexColorPickerDialog(
            initialColorHex = String.format("#%08X", if (selectedIconColorEnabled) selectedIconColor else 0xFF00A884.toInt()),
            onColorSelected = { hex ->
                try {
                    selectedIconColor = AndroidColor.parseColor(hex)
                    preferenceManager.putInt("floating_bottom_bar_selected_icon_color", selectedIconColor)
                    selectedIconColorEnabled = true
                    preferenceManager.putBoolean("floating_bottom_bar_selected_icon_color_enabled", true)
                } catch (_: IllegalArgumentException) {}
                showSelectedIconColorPicker = false
            },
            onDismiss = { showSelectedIconColorPicker = false },
            enableOpacity = true,
            onUseDefault = {
                selectedIconColorEnabled = false
                preferenceManager.remove("floating_bottom_bar_selected_icon_color")
                preferenceManager.remove("floating_bottom_bar_selected_icon_color_enabled")
            }
        )
    }

    if (showUnselectedIconColorPicker) {
        WaexColorPickerDialog(
            initialColorHex = String.format("#%08X", if (unselectedIconColorEnabled) unselectedIconColor else 0xFF8696A0.toInt()),
            onColorSelected = { hex ->
                try {
                    unselectedIconColor = AndroidColor.parseColor(hex)
                    preferenceManager.putInt("floating_bottom_bar_unselected_icon_color", unselectedIconColor)
                    unselectedIconColorEnabled = true
                    preferenceManager.putBoolean("floating_bottom_bar_unselected_icon_color_enabled", true)
                } catch (_: IllegalArgumentException) {}
                showUnselectedIconColorPicker = false
            },
            onDismiss = { showUnselectedIconColorPicker = false },
            enableOpacity = true,
            onUseDefault = {
                unselectedIconColorEnabled = false
                preferenceManager.remove("floating_bottom_bar_unselected_icon_color")
                preferenceManager.remove("floating_bottom_bar_unselected_icon_color_enabled")
            }
        )
    }
}

@Composable
private fun IconColorSettingRow(
    title: String,
    isEnabled: Boolean,
    color: Int,
    onClick: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(radius.smShape)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = typography.bodyMd,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface
            )
            Text(
                text = if (isEnabled) String.format("#%08X", color)
                else UiText.get(R.string.waex_ui_using_app_default_color, "Using app default color"),
                style = typography.labelSm,
                color = colors.onSurfaceVariant
            )
        }
        if (isEnabled) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(1.dp, colors.outlineVariant, CircleShape)
            )
        } else {
            Surface(
                shape = RoundedCornerShape(50),
                color = colors.surfaceDim,
                border = BorderStroke(1.dp, colors.outlineVariant)
            ) {
                Text(
                    text = UiText.get(R.string.waex_ui_default, "Default"),
                    style = typography.labelSm,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    var showValueEditor by remember { mutableStateOf(false) }
    var inputValue by remember { mutableStateOf("") }
    var hasInputError by remember { mutableStateOf(false) }

    fun openValueEditor() {
        inputValue = value.toInt().toString()
        hasInputError = false
        showValueEditor = true
    }

    fun applyInputValue() {
        val parsed = inputValue.trim().toFloatOrNull()
        if (parsed == null || !parsed.isFinite()) {
            hasInputError = true
            return
        }
        onValueChange(parsed)
        showValueEditor = false
    }

    if (showValueEditor) {
        WaexModalBottomSheet(
            onDismissRequest = { showValueEditor = false },
            containerColor = colors.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = label,
                    style = typography.bodyLg,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
                OutlinedTextField(
                    value = inputValue,
                    onValueChange = {
                        inputValue = it
                        hasInputError = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Custom value ($unit)") },
                    supportingText = if (hasInputError) {
                        { Text(UiText.get(R.string.waex_ui_invalid_value, "Invalid value")) }
                    } else null,
                    isError = hasInputError,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { applyInputValue() })
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showValueEditor = false }) {
                        Text("Cancel")
                    }
                    TextButton(onClick = { applyInputValue() }) {
                        Text("Apply")
                    }
                }
            }
        }
    }

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
                color = colors.primary,
                modifier = Modifier.pointerInput(value) {
                    detectTapGestures(onDoubleTap = { openValueEditor() })
                }
            )
        }
        Slider(
            // Keep custom negative values intact; only the visual slider position becomes zero.
            value = value.coerceAtLeast(0f).coerceAtMost(valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = 0f..valueRange.endInclusive,
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary,
                inactiveTrackColor = colors.outlineVariant
            )
        )
    }
}
