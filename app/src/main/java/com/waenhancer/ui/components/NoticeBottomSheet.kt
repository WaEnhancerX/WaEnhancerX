package com.waenhancer.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.util.LinkifyCompat
import androidx.core.util.PatternsCompat
import com.waenhancer.notices.NoticeAction
import com.waenhancer.notices.NoticeItem
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import io.noties.markwon.Markwon
import io.noties.markwon.html.HtmlPlugin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticeBottomSheet(
    notice: NoticeItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { notice.dismissible }
    )

    val severityColor = when (notice.severityRank) {
        3 -> colors.error
        2 -> Color(0xFFF59E0B) // Amber/Warning
        else -> colors.primary
    }

    val severityIcon = when (notice.severityRank) {
        3, 2 -> WaexIcons.Warning
        else -> WaexIcons.Info
    }

    val severityLabel = when (notice.severityRank) {
        3 -> "URGENT"
        2 -> "IMPORTANT"
        else -> "ANNOUNCEMENT"
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (notice.dismissible) {
                onDismiss()
            }
        },
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = radius.bottomSheetShape,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(colors.outlineVariant.copy(alpha = 0.6f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header Row: Icon Badge + Category + Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(severityColor.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = severityIcon,
                            contentDescription = null,
                            tint = severityColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(severityColor.copy(alpha = 0.10f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = severityLabel,
                            style = typography.labelSm,
                            color = severityColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                if (notice.dismissible) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(colors.surfaceDim)
                            .border(1.dp, colors.outlineVariant.copy(alpha = 0.5f), CircleShape)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = WaexIcons.Clear,
                            contentDescription = "Dismiss",
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Notice Title
            Text(
                text = notice.title,
                style = typography.headlineMd,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                lineHeight = 26.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Message Body (Markdown / Plain Text)
            if ("markdown".equals(notice.format, ignoreCase = true)) {
                NoticeMarkdownText(
                    markdown = notice.message,
                    textColor = colors.onSurfaceVariant.toArgb()
                )
            } else {
                Text(
                    text = notice.message,
                    style = typography.bodyMd,
                    color = colors.onSurfaceVariant,
                    lineHeight = 22.sp
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Actions
            val primaryAction = notice.primaryAction
            val secondaryAction = notice.secondaryAction
            val dismissAction = notice.dismissAction

            val hasPrimary = primaryAction != null && primaryAction.label.isNotBlank()
            val hasSecondary = secondaryAction != null && secondaryAction.label.isNotBlank()

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (hasPrimary) {
                    Button(
                        onClick = {
                            onDismiss()
                            handleAction(context, primaryAction!!)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = radius.fullShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primary,
                            contentColor = colors.onPrimary
                        )
                    ) {
                        Text(
                            text = primaryAction!!.label,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }

                if (hasSecondary) {
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            handleAction(context, secondaryAction!!)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = radius.fullShape,
                        border = BorderStroke(1.dp, colors.outlineVariant),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = colors.onSurface
                        )
                    ) {
                        Text(
                            text = secondaryAction!!.label,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        )
                    }
                }

                if (!hasPrimary && !hasSecondary && notice.dismissible) {
                    val dismissLabel = dismissAction?.label?.takeIf { it.isNotBlank() } ?: "Dismiss"
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = radius.fullShape,
                        border = BorderStroke(1.dp, colors.outlineVariant),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = colors.onSurfaceVariant
                        )
                    ) {
                        Text(
                            text = dismissLabel,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.5.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeMarkdownText(markdown: String, textColor: Int) {
    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(textColor)
                textSize = 13.5f
                setLineSpacing(0f, 1.2f)
                tag = Markwon.builder(ctx).usePlugin(HtmlPlugin.create()).build()
            }
        },
        update = { view ->
            view.setTextColor(textColor)
            (view.tag as Markwon).setMarkdown(view, markdown.trim())
            LinkifyCompat.addLinks(view, PatternsCompat.WEB_URL, null)
        },
        modifier = Modifier.fillMaxWidth()
    )
}

private fun handleAction(context: android.content.Context, action: NoticeAction) {
    if ("url".equals(action.type, ignoreCase = true) && !action.url.isNullOrBlank()) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(action.url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}

