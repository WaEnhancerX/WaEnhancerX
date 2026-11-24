package com.waenhancer.ui.screens.settings

import android.graphics.Color as AndroidColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.luminance
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
import com.waenhancer.xposed.features.conversation.ChatBubbleColorsHook

/** Which side of the conversation a color applies to. */
private enum class BubbleSide(val prefKey: String) {
    INCOMING(ChatBubbleColorsHook.PREF_INCOMING),
    OUTGOING(ChatBubbleColorsHook.PREF_OUTGOING)
}

// Approximations of WhatsApp's stock bubbles, only used to preview the "Default" state.
private val STOCK_INCOMING_LIGHT = Color(0xFFFFFFFF)
private val STOCK_INCOMING_DARK = Color(0xFF202C33)
private val STOCK_OUTGOING_LIGHT = Color(0xFFD9FDD3)
private val STOCK_OUTGOING_DARK = Color(0xFF005C4B)
private const val PICKER_SEED_COLOR = 0xFF00A884.toInt()

/** Toggles the bubble tint feature and edits the incoming/outgoing bubble colors. */
@Composable
fun BubbleColorsScreen() {
    val navController = LocalWaexNavController.current
    val preferenceManager = LocalWaexPreferenceManager.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing

    var enabled by remember {
        mutableStateOf(
            preferenceManager.getBoolean(
                ChatBubbleColorsHook.PREF_ENABLED,
                preferenceManager.getBoolean("bubble_colors", false)
            )
        )
    }
    var incoming by remember { mutableIntStateOf(preferenceManager.getInt(BubbleSide.INCOMING.prefKey, 0)) }
    var outgoing by remember { mutableIntStateOf(preferenceManager.getInt(BubbleSide.OUTGOING.prefKey, 0)) }
    var editing by remember { mutableStateOf<BubbleSide?>(null) }

    fun colorOf(side: BubbleSide) = if (side == BubbleSide.INCOMING) incoming else outgoing
    fun store(side: BubbleSide, value: Int) {
        if (side == BubbleSide.INCOMING) incoming = value else outgoing = value
    }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = UiText.get(R.string.waex_ui_bubble_colors, "Bubble Colors"),
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
            FeatureToggleCard(
                enabled = enabled,
                onToggle = { isChecked ->
                    if (preferenceManager.putBooleans(mapOf(ChatBubbleColorsHook.PREF_ENABLED to isChecked))) {
                        enabled = isChecked
                    }
                }
            )

            AnimatedVisibility(visible = enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.stackLg)) {
                    WaexSectionHeader(
                        title = UiText.get(R.string.waex_ui_bubble_preview, "Preview"),
                        subtitle = UiText.get(
                            R.string.waex_ui_bubble_restart_hint,
                            "Restart WhatsApp to apply changes"
                        )
                    )
                    ChatPreview(incoming = incoming, outgoing = outgoing)

                    WaexSectionHeader(
                        title = UiText.get(R.string.waex_ui_native_floating_navigation_colors, "Colors"),
                        subtitle = UiText.get(
                            R.string.waex_ui_native_floating_navigation_colors_hint,
                            "Leave on default to keep WhatsApp's theme colors"
                        )
                    )
                    ColorChoicesCard(
                        incoming = incoming,
                        outgoing = outgoing,
                        onEdit = { editing = it }
                    )

                    if (incoming != 0 || outgoing != 0) {
                        OutlinedButton(
                            onClick = {
                                BubbleSide.entries.forEach { side ->
                                    preferenceManager.remove(side.prefKey)
                                    store(side, 0)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(UiText.get(R.string.waex_ui_bubble_reset, "Reset to WhatsApp colors"))
                        }
                    }
                }
            }
        }
    }

    val side = editing ?: return
    val current = colorOf(side)
    WaexColorPickerDialog(
        initialColorHex = String.format("#%08X", if (current == 0) PICKER_SEED_COLOR else current),
        onColorSelected = { hex ->
            val picked = runCatching { AndroidColor.parseColor(hex) }.getOrNull()
            if (picked != null) {
                // 0 is reserved for "WhatsApp default"; nudge a fully transparent pick off it.
                val stored = if (picked == 0) 1 else picked
                preferenceManager.putInt(side.prefKey, stored)
                store(side, stored)
            }
            editing = null
        },
        onDismiss = { editing = null },
        enableOpacity = true,
        onUseDefault = {
            preferenceManager.remove(side.prefKey)
            store(side, 0)
            editing = null
        }
    )
}

@Composable
private fun FeatureToggleCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    Surface(
        shape = WaexTheme.radius.bentoCardShape,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle(!enabled) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = UiText.get(R.string.feat_bubble_colors_title, "Change Bubble Colors"),
                    style = typography.bodyLg,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
                Text(
                    text = UiText.get(R.string.feat_bubble_colors_desc, "Change Bubble Color on Conversation Screen"),
                    style = typography.bodyMd,
                    color = colors.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            StitchSwitch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun ChatPreview(incoming: Int, outgoing: Int) {
    val colors = WaexTheme.colors
    val dark = colors.isDark
    val incomingColor by animateColorAsState(
        if (incoming == 0) (if (dark) STOCK_INCOMING_DARK else STOCK_INCOMING_LIGHT) else Color(incoming),
        label = "incomingBubble"
    )
    val outgoingColor by animateColorAsState(
        if (outgoing == 0) (if (dark) STOCK_OUTGOING_DARK else STOCK_OUTGOING_LIGHT) else Color(outgoing),
        label = "outgoingBubble"
    )
    Surface(
        shape = WaexTheme.radius.bentoCardShape,
        color = colors.surfaceDim,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PreviewBubble(
                text = UiText.get(R.string.waex_ui_bubble_preview_incoming, "Hey! Did you try the new colors?"),
                time = "10:41",
                fill = incomingColor,
                outgoing = false
            )
            PreviewBubble(
                text = UiText.get(R.string.waex_ui_bubble_preview_outgoing, "Yes, looks great 😄"),
                time = "10:42",
                fill = outgoingColor,
                outgoing = true
            )
        }
    }
}

@Composable
private fun PreviewBubble(text: String, time: String, fill: Color, outgoing: Boolean) {
    val typography = WaexTheme.typography
    // Pick readable text from the bubble's own brightness, like WhatsApp does on custom wallpapers.
    val ink = if (fill.luminance() > 0.5f) Color(0xFF111B21) else Color(0xFFE9EDEF)
    val shape = if (outgoing) {
        RoundedCornerShape(topStart = 12.dp, topEnd = 2.dp, bottomEnd = 12.dp, bottomStart = 12.dp)
    } else {
        RoundedCornerShape(topStart = 2.dp, topEnd = 12.dp, bottomEnd = 12.dp, bottomStart = 12.dp)
    }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 260.dp)
                .clip(shape)
                .background(fill)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(text = text, style = typography.bodyMd, color = ink)
            Text(
                text = if (outgoing) "$time ✓✓" else time,
                style = typography.labelSm,
                color = ink.copy(alpha = 0.6f),
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun ColorChoicesCard(incoming: Int, outgoing: Int, onEdit: (BubbleSide) -> Unit) {
    val colors = WaexTheme.colors
    Surface(
        shape = WaexTheme.radius.bentoCardShape,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            BubbleColorRow(
                title = UiText.get(R.string.waex_ui_bubble_incoming, "Received messages"),
                color = incoming,
                onClick = { onEdit(BubbleSide.INCOMING) }
            )
            HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.5f))
            BubbleColorRow(
                title = UiText.get(R.string.waex_ui_bubble_outgoing, "Sent messages"),
                color = outgoing,
                onClick = { onEdit(BubbleSide.OUTGOING) }
            )
        }
    }
}

@Composable
private fun BubbleColorRow(title: String, color: Int, onClick: () -> Unit) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val isDefault = color == 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WaexTheme.radius.smShape)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = typography.bodyMd, fontWeight = FontWeight.Medium, color = colors.onSurface)
            Text(
                text = if (isDefault) UiText.get(R.string.waex_ui_default, "Default") else String.format("#%08X", color),
                style = typography.labelSm,
                color = colors.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (isDefault) colors.surfaceDim else Color(color))
                .border(1.dp, colors.outlineVariant, CircleShape)
        )
    }
}
