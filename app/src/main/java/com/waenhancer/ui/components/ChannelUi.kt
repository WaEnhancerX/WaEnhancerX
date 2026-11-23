package com.waenhancer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.app.R
import com.waenhancer.update.UpdateChannel
import com.waenhancer.utils.UiText

fun channelLabel(channel: UpdateChannel): String = when (channel) {
    UpdateChannel.STABLE -> UiText.get(R.string.waex_ui_channel_stable, "Stable")
    UpdateChannel.BETA -> UiText.get(R.string.waex_ui_channel_beta, "Beta")
    UpdateChannel.NIGHTLY -> UiText.get(R.string.waex_ui_channel_nightly, "Nightly")
}

fun channelDescription(channel: UpdateChannel): String = when (channel) {
    UpdateChannel.STABLE -> UiText.get(R.string.waex_ui_channel_stable_desc, "Fully tested releases only")
    UpdateChannel.BETA -> UiText.get(R.string.waex_ui_channel_beta_desc, "Early builds before they reach stable")
    UpdateChannel.NIGHTLY -> UiText.get(R.string.waex_ui_channel_nightly_desc, "Every automated build, untested and may break")
}

/** Confirmation shown before a downgrade, a pre-release install or a move to a less stable channel. */
@Composable
fun RiskConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(UiText.get(R.string.waex_ui_cancel, "Cancel")) } },
    )
}

/** Warning text for installing [target] over [installed], or null when no warning is needed. */
fun installRiskMessage(isDowngrade: Boolean, target: UpdateChannel, installed: UpdateChannel): String? {
    val parts = buildList {
        if (isDowngrade) add(UiText.get(R.string.waex_ui_install_risk_downgrade, "This replaces your installed version with an older one. Data written by the newer version may not be readable by the older one."))
        if (target != UpdateChannel.STABLE && target.stabilityRank < installed.stabilityRank) {
            add(UiText.get(R.string.waex_ui_install_risk_prerelease, "Beta and nightly builds are not release-tested and may contain bugs."))
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

/** Small chip naming the release kind (Stable, Beta or Nightly) of a version name. */
@Composable
fun ReleaseKindChip(versionName: String, modifier: Modifier = Modifier) {
    val colors = WaexTheme.colors
    val channel = UpdateChannel.fromVersionName(versionName)
    val (background, content) = when (channel) {
        UpdateChannel.STABLE -> colors.surfaceDim to colors.onSurfaceVariant
        UpdateChannel.BETA -> colors.warningContainer to colors.onWarningContainer
        UpdateChannel.NIGHTLY -> colors.primaryContainer to colors.primary
    }
    Surface(
        shape = WaexTheme.radius.buttonShape,
        color = background,
        border = BorderStroke(1.dp, content.copy(alpha = 0.25f)),
        modifier = modifier
    ) {
        Text(
            text = channelLabel(channel),
            style = WaexTheme.typography.labelSm,
            fontWeight = FontWeight.Bold,
            color = content,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/** iOS-style segmented control: a sliding raised thumb behind equally sized labels. */
@Composable
fun WaexSegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    Surface(
        shape = radius.mdShape,
        color = colors.surfaceDim,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
        modifier = modifier.fillMaxWidth().height(44.dp)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
            val tabWidth = maxWidth / labels.size
            val offset by animateDpAsState(
                targetValue = tabWidth * selectedIndex,
                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
                label = "segment_offset"
            )
            Box(
                Modifier
                    .offset(x = offset)
                    .width(tabWidth)
                    .fillMaxHeight()
                    .clip(radius.defaultShape)
                    .background(colors.surface)
                    .border(1.dp, colors.outlineVariant, radius.defaultShape)
            )
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceAround) {
                labels.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    val textColor by animateColorAsState(
                        targetValue = if (selected) colors.primary else colors.onSurfaceVariant,
                        animationSpec = tween(150),
                        label = "segment_text"
                    )
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .weight(1f)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = typography.bodyMd,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = textColor
                        )
                    }
                }
            }
        }
    }
}
