package com.waenhancer.ui.screens.settings

import android.graphics.Color as AndroidColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.app.R
import com.waenhancer.ui.components.StitchSwitch
import com.waenhancer.ui.components.WaexColorPickerDialog
import com.waenhancer.ui.components.WaexSectionHeader
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import com.waenhancer.utils.UiText

private const val PREF_BACKGROUND_COLOR = "native_floating_navigation_bg_color"
private const val PREF_SELECTION_COLOR = "native_floating_navigation_selection_color"
private const val DEFAULT_PICKER_COLOR = 0xFF00A884.toInt()

/** Enables WhatsApp's native floating bottom bar and its optional colors. */
@Composable
fun NativeFloatingNavigationScreen() {
    val navController = LocalWaexNavController.current
    val preferenceManager = LocalWaexPreferenceManager.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var enabled by remember {
        mutableStateOf(preferenceManager.getBoolean(FloatingNavigationExclusivity.NATIVE_BAR, false))
    }
    var backgroundColor by remember { mutableIntStateOf(preferenceManager.getInt(PREF_BACKGROUND_COLOR, 0)) }
    var selectionColor by remember { mutableIntStateOf(preferenceManager.getInt(PREF_SELECTION_COLOR, 0)) }
    var editingKey by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = UiText.get(R.string.feat_native_floating_navigation_title, "Native Floating Navigation"),
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
            Surface(
                shape = radius.bentoCardShape,
                color = colors.surface,
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = UiText.get(R.string.feat_native_floating_navigation_title, "Native Floating Navigation"),
                            style = typography.bodyLg,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                        Text(
                            text = UiText.get(
                                R.string.waex_ui_native_floating_navigation_exclusive,
                                "Uses WhatsApp's own floating bar. Turning it on turns off the module's Floating Bottom Bar."
                            ),
                            style = typography.bodyMd,
                            color = colors.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                    StitchSwitch(
                        checked = enabled,
                        onCheckedChange = { isChecked ->
                            val saved = if (isChecked) {
                                preferenceManager.putBooleans(
                                    mapOf(
                                        FloatingNavigationExclusivity.NATIVE_BAR to true,
                                        FloatingNavigationExclusivity.MODULE_BAR to false
                                    )
                                )
                            } else {
                                preferenceManager.putBooleans(mapOf(FloatingNavigationExclusivity.NATIVE_BAR to false))
                            }
                            if (saved) enabled = isChecked
                        }
                    )
                }
            }

            AnimatedVisibility(visible = enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.stackLg)) {
                    WaexSectionHeader(
                        title = UiText.get(R.string.waex_ui_native_floating_navigation_colors, "Colors"),
                        subtitle = UiText.get(
                            R.string.waex_ui_native_floating_navigation_colors_hint,
                            "Leave on default to keep WhatsApp's theme colors"
                        )
                    )
                    Surface(
                        shape = radius.bentoCardShape,
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            NativeColorRow(
                                title = UiText.get(R.string.waex_ui_native_floating_navigation_bg_color, "Background color"),
                                color = backgroundColor,
                                onClick = { editingKey = PREF_BACKGROUND_COLOR }
                            )
                            HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.5f))
                            NativeColorRow(
                                title = UiText.get(R.string.waex_ui_native_floating_navigation_selection_color, "Selection color"),
                                color = selectionColor,
                                onClick = { editingKey = PREF_SELECTION_COLOR }
                            )
                        }
                    }
                }
            }
        }
    }

    val key = editingKey
    if (key != null) {
        val current = if (key == PREF_BACKGROUND_COLOR) backgroundColor else selectionColor
        WaexColorPickerDialog(
            initialColorHex = String.format("#%08X", if (current == 0) DEFAULT_PICKER_COLOR else current),
            onColorSelected = { hex ->
                try {
                    // 0 means "WhatsApp default", so a fully transparent black is stored as 1.
                    val picked = AndroidColor.parseColor(hex).let { if (it == 0) 1 else it }
                    preferenceManager.putInt(key, picked)
                    if (key == PREF_BACKGROUND_COLOR) backgroundColor = picked else selectionColor = picked
                } catch (_: IllegalArgumentException) {
                }
                editingKey = null
            },
            onDismiss = { editingKey = null },
            enableOpacity = true,
            onUseDefault = {
                preferenceManager.remove(key)
                if (key == PREF_BACKGROUND_COLOR) backgroundColor = 0 else selectionColor = 0
                editingKey = null
            }
        )
    }
}

@Composable
private fun NativeColorRow(title: String, color: Int, onClick: () -> Unit) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(radius.smShape)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = typography.bodyMd, fontWeight = FontWeight.Medium, color = colors.onSurface)
            Text(
                text = if (color == 0) {
                    UiText.get(R.string.waex_ui_default, "Default")
                } else {
                    String.format("#%08X", color)
                },
                style = typography.labelSm,
                color = colors.onSurfaceVariant
            )
        }
        if (color == 0) {
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
                    .background(Color(color))
                    .border(1.dp, colors.outlineVariant, CircleShape)
            )
        }
    }
}
