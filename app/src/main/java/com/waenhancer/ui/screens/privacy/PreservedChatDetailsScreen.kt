package com.waenhancer.ui.screens.privacy

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

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
    val isDark = colors.isDark

    val isSent = message.isFromMe

    // WhatsApp design spec: outgoing #D9FDD3 / #144D37, incoming white / #20272B.
    val bubbleColor = when {
        isSent -> if (isDark) Color(0xFF144D37) else Color(0xFFD9FDD3)
        else -> if (isDark) Color(0xFF20272B) else Color(0xFFFFFFFF)
    }
    val textColor = if (isDark) Color(0xFFF7F8FA) else Color(0xFF0A1014)
    val timestampColor = if (isDark) Color(0xFFA1B8AF) else Color(0xFF4E5F57)

    // 16dp bubble with a small pointed tail corner on the sender's side.
    val bubbleShape = if (isSent) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 2.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    } else {
        RoundedCornerShape(topStart = 2.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isSent) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            shadowElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(0.82f)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
