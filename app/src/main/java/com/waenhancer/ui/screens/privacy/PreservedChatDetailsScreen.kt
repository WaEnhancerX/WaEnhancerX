package com.waenhancer.ui.screens.privacy

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme

/**
 * Dedicated Screen for viewing Preserved "Delete For Me" messages for a specific Contact or Group.
 * Supports sent (outgoing, right-aligned) and received (incoming, left-aligned) message bubbles.
 */
@Composable
fun PreservedChatDetailsScreen(
    chat: PreservedChat,
    onBack: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val spacing = WaexTheme.spacing

    // Intercept system back button / gesture to return to conversations list
    BackHandler(enabled = true) {
        onBack()
    }

    Scaffold(
        topBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colors.surface,
                tonalElevation = 0.dp
            ) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = WaexIcons.Back,
                                contentDescription = UiText.get(R.string.action_back, "Back"),
                                tint = colors.onSurface
                            )
                        }

                        // Avatar Circle
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(colors.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            if (chat.isGroup) {
                                Icon(
                                    imageVector = WaexIcons.Folder,
                                    contentDescription = null,
                                    tint = colors.onPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Text(
                                    text = chat.name.firstOrNull()?.uppercase() ?: "?",
                                    style = typography.bodyLg,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onPrimary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = chat.name,
                                style = typography.headlineMd,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (chat.isGroup) UiText.get(R.string.waex_ui_group_1_s_preserved, "Group • %1\$s preserved", chat.messages.size) else UiText.get(R.string.waex_ui_1_s_preserved_messages, "%1\$s preserved messages", chat.messages.size),
                                style = typography.labelSm,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        containerColor = if (colors.isLight) Color(0xFFF5F1EB) else Color(0xFF0A1014) // chat wallpaper base
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(chat.messages, key = { it.id }) { message ->
                PreservedMessageBubble(message = message, isGroup = chat.isGroup)
            }
        }
    }
}

/**
 * Chat bubble supporting Sent (Right, Light Green) vs Received (Left, White/Gray).
 */
@Composable
private fun PreservedMessageBubble(
    message: PreservedMessage,
    isGroup: Boolean
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val isDark = colors.isDark

    val isSent = message.isFromMe

    // WhatsApp design spec: outgoing #D9FDD3 / #144D37, incoming white / #20272B.
    val bubbleColor = when {
        isSent -> if (isDark) Color(0xFF144D37) else Color(0xFFD9FDD3)
        else -> if (isDark) Color(0xFF20272B) else Color(0xFFFFFFFF)
    }
    val textColor = when {
        isSent -> if (isDark) Color(0xFFF7F8FA) else Color(0xFF0A1014)
        else -> if (isDark) Color(0xFFFFFFFF) else Color(0xFF0A1014)
    }
    val timestampColor = when {
        isSent -> if (isDark) Color(0xFFA1B8AF) else Color(0xFF4E5F57)
        else -> if (isDark) Color(0xFFA6A9AA) else Color(0xFF596064)
    }

    // Measured: outgoing radius ~20dp / tail top-right (10dp tall),
    // incoming radius ~16dp / tail top-left (13dp tall), tail protrudes ~6.5dp.
    val bubbleShape = remember(isSent) {
        BubbleTailShape(
            tailOnRight = isSent,
            cornerRadius = if (isSent) 20.dp else 16.dp,
            tailWidth = 6.5.dp,
            tailHeight = if (isSent) 10.dp else 13.dp
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isSent) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            shadowElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Column(
                modifier = Modifier.padding(
                    start = if (isSent) 12.dp else 12.dp + 6.5.dp,
                    end = if (isSent) 12.dp + 6.5.dp else 12.dp,
                    top = 8.dp,
                    bottom = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // In group received messages, show sender name if available
                if (isGroup && !isSent) {
                    val authorName = message.senderName?.takeIf { it.isNotBlank() } ?: UiText.get(R.string.waex_ui_participant, "Participant")
                    Text(
                        text = authorName,
                        style = typography.labelSm,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                        fontSize = 12.sp
                    )
                }

                // Message Text
                Text(
                    text = message.text,
                    style = typography.bodyMd,
                    color = textColor,
                    fontSize = 15.sp,
                    lineHeight = 20.sp
                )

                // Timestamp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message.timestamp,
                        style = typography.labelSm,
                        color = timestampColor,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

/**
 * WhatsApp-style bubble: three rounded corners plus a square top corner that extends
 * into a pointed tail. The tail lives inside the shape bounds, so callers pad the
 * content by [tailWidth] on the tail side.
 */
private class BubbleTailShape(
    private val tailOnRight: Boolean,
    private val cornerRadius: Dp,
    private val tailWidth: Dp,
    private val tailHeight: Dp
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { cornerRadius.toPx() }
            .coerceAtMost(minOf(size.width, size.height) / 2f)
        val t = with(density) { tailWidth.toPx() }
        val th = with(density) { tailHeight.toPx() }.coerceAtMost(size.height - r)
        val left = if (tailOnRight) 0f else t
        val right = if (tailOnRight) size.width - t else size.width
        val d = 2 * r
        val path = Path().apply {
            if (tailOnRight) {
                moveTo(left + r, 0f)
                lineTo(size.width, 0f)
                quadraticTo(right - t * 0.1f, th * 0.45f, right, th)
                lineTo(right, size.height - r)
                arcTo(Rect(right - d, size.height - d, right, size.height), 0f, 90f, false)
                lineTo(left + r, size.height)
                arcTo(Rect(left, size.height - d, left + d, size.height), 90f, 90f, false)
                lineTo(left, r)
                arcTo(Rect(left, 0f, left + d, d), 180f, 90f, false)
            } else {
                moveTo(0f, 0f)
                lineTo(right - r, 0f)
                arcTo(Rect(right - d, 0f, right, d), 270f, 90f, false)
                lineTo(right, size.height - r)
                arcTo(Rect(right - d, size.height - d, right, size.height), 0f, 90f, false)
                lineTo(left + r, size.height)
                arcTo(Rect(left, size.height - d, left + d, size.height), 90f, 90f, false)
                lineTo(left, th)
                quadraticTo(left + t * 0.1f, th * 0.45f, 0f, 0f)
            }
            close()
        }
        return Outline.Generic(path)
    }
}
