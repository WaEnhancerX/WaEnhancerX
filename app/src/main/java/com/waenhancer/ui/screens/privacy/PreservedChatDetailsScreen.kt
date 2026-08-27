package com.waenhancer.ui.screens.privacy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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

    // Intercept system back button / gesture to return to conversations list
    BackHandler(enabled = true) {
        onBack()
    }

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
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = WaexIcons.Back,
                                contentDescription = "Back",
                                tint = colors.onSurface
                            )
                        }

                        // Avatar Circle
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (chat.isGroup) Color(0xFF008069) else colors.primary),
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
                                style = typography.headlineMd,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (chat.isGroup) "Group • ${chat.messages.size} preserved" else "${chat.messages.size} preserved messages",
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
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
    val isDark = isSystemInDarkTheme()

    val isSent = message.isFromMe

    // WhatsApp-like bubble colors
    val bubbleColor = if (isSent) {
        if (isDark) Color(0xFF005D4B) else Color(0xFFE7FFDB)
    } else {
        if (isDark) Color(0xFF1F2C34) else Color(0xFFFFFFFF)
    }

    val textColor = if (isSent) {
        if (isDark) Color(0xFFE9EDEF) else Color(0xFF111B21)
    } else {
        if (isDark) Color(0xFFE9EDEF) else Color(0xFF111B21)
    }

    val bubbleShape = if (isSent) {
        RoundedCornerShape(
            topStart = 14.dp,
            topEnd = 4.dp,
            bottomStart = 14.dp,
            bottomEnd = 14.dp
        )
    } else {
        RoundedCornerShape(
            topStart = 4.dp,
            topEnd = 14.dp,
            bottomStart = 14.dp,
            bottomEnd = 14.dp
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isSent) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            shadowElevation = 0.8.dp,
            modifier = Modifier.fillMaxWidth(0.82f)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // In group received messages, show sender name if available
                if (isGroup && !isSent) {
                    val authorName = message.senderName?.takeIf { it.isNotBlank() } ?: "Participant"
                    Text(
                        text = authorName,
                        style = typography.labelSm,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF25D366),
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
                        color = textColor.copy(alpha = 0.55f),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
