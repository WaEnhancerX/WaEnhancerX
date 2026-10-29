package com.waenhancer.ui.components

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.update.ChangelogParser
import com.waenhancer.update.ChangelogSection
import com.waenhancer.update.WaexRelease
import androidx.compose.foundation.BorderStroke
import com.waenhancer.utils.InternalLabels

/**
 * Modern In-App Update Modal Bottom Sheet for WaEnhancerX.
 * Clean native-styled expandable sections matching the WhatsApp Design System.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateAvailableBottomSheet(
    release: WaexRelease,
    onUpdateNow: () -> Unit,
    onViewDetails: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val sections = remember(release.body) {
        ChangelogParser.parse(release.body)
    }

    val expandedStates = remember(sections) {
        mutableStateMapOf<String, Boolean>().apply {
            sections.forEach { this[it.title] = true }
        }
    }

    WaexModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = radius.bottomSheetShape,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(colors.outlineVariant, RoundedCornerShape(2.dp))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.primary.copy(alpha = 0.12f),
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = WaexIcons.SystemUpdate,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.size(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = UiText.get(R.string.waex_ui_update_available, "Update Available"),
                        style = typography.titleLg,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    Text(
                        text = UiText.get(R.string.waex_ui_version_1_s, "Version %1\$s", release.version),
                        style = typography.bodyMd,
                        color = colors.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Clean Native-Styled Changelog Sections Container
            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceDim,
                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 280.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (sections.isEmpty()) {
                        Text(
                            text = UiText.get(R.string.waex_ui_general_stability_improvements_and_bug, "General stability improvements and bug fixes."),
                            style = typography.bodyMd,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        sections.forEachIndexed { index, section ->
                            if (index > 0) {
                                HorizontalDivider(
                                    thickness = 1.dp,
                                    color = colors.outlineVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                            val isExpanded = expandedStates[section.title] ?: true
                            NativeChangelogSectionRow(
                                section = section,
                                isExpanded = isExpanded,
                                onToggle = {
                                    expandedStates[section.title] = !isExpanded
                                }
                            )
                        }
                    }
                }
            }

            // Action Buttons
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onUpdateNow,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                ) {
                    Text(
                        text = UiText.get(R.string.waex_ui_update_now, "Update Now"),
                        fontWeight = FontWeight.Bold,
                        color = colors.onPrimary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onViewDetails,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(UiText.get(R.string.waex_ui_view_details, "View Details"))
                    }
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(UiText.get(R.string.waex_ui_later, "Later"))
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeChangelogSectionRow(
    section: ChangelogSection,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val chevronRotation by animateFloatAsState(targetValue = if (isExpanded) 90f else 0f, label = "chevron")

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 10.dp)
        ) {
            Text(
                text = InternalLabels.text(section.title),
                style = typography.titleMd,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = "(${section.items.size})",
                style = typography.bodySm,
                color = colors.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )

            Icon(
                imageVector = WaexIcons.ChevronRight,
                contentDescription = if (isExpanded) UiText.get(R.string.waex_ui_collapse, "Collapse") else UiText.get(R.string.waex_ui_expand, "Expand"),
                tint = colors.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(chevronRotation)
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                section.items.forEachIndexed { itemIndex, item ->
                    val level = section.itemLevels.getOrElse(itemIndex) { 0 }
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (level * 18).dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .size(if (level == 0) 4.dp else 3.dp)
                                .background(colors.onSurfaceVariant, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = item,
                            style = typography.bodyMd,
                            color = colors.onSurface,
                            fontSize = 13.5.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}
