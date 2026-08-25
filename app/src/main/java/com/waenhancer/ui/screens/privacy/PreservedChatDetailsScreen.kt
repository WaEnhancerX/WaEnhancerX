package com.waenhancer.ui.screens.privacy

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

    Scaffold(
        topBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colors.surface,
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = WaexIcons.Back,
                                contentDescription = "Back",
                                tint = colors.onSurface
                            )
                        }

                        // TopBar Circular Avatar
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(colors.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            if (chat.isGroup) {
                                Icon(
                                    imageVector = WaexIcons.Folder,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Text(
                                    text = chat.name.firstOrNull()?.uppercase() ?: "?",
                                    style = typography.bodyLg,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = chat.name,
                                style = typography.bodyLg,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${chat.messages.size} preserved messages",
                                style = typography.labelSm,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        containerColor = colors.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = spacing.pageMargin,
                vertical = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(chat.messages, key = { it.id }) { msg ->
                PreservedMessageBubble(message = msg, isGroup = chat.isGroup)
            }
        }
    }
}

/**
 * Message Bubble representing a preserved "Delete For Me" message.
 * Formats sent messages (right-aligned) and received messages (left-aligned).
 */
@Composable
private fun PreservedMessageBubble(
    message: PreservedMessage,
    isGroup: Boolean
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val isFromMe = message.isFromMe

    val outgoingBg = colors.primary.copy(alpha = 0.18f)
    val incomingBg = colors.surface

    val bubbleShape = if (isFromMe) {
        RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 16.dp,
            bottomEnd = 3.dp
        )
    } else {
        RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 3.dp,
            bottomEnd = 16.dp
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isFromMe) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(bubbleShape),
            color = if (isFromMe) outgoingBg else incomingBg,
            shape = bubbleShape,
            tonalElevation = if (isFromMe) 0.dp else 1.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Sender name for group received messages
                if (isGroup && !isFromMe && !message.senderName.isNullOrEmpty()) {
                    Text(
                        text = message.senderName,
                        style = typography.labelSm,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                        fontSize = 12.sp
                    )
                }

                // Preserved Message Content
                Text(
                    text = message.text,
                    style = typography.bodyMd,
                    color = colors.onSurface,
                    lineHeight = 20.sp
                )

                // Timestamp (Right Aligned)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message.timestamp,
                        style = typography.labelSm,
                        color = colors.onSurfaceVariant,
                        fontSize = 11.sp
                    )

                    if (isFromMe) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = WaexIcons.Success,
                            contentDescription = "Delivered",
                            tint = colors.primary,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}
