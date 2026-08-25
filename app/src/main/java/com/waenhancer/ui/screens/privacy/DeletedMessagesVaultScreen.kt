package com.waenhancer.ui.screens.privacy

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.components.StitchSwitch
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager

data class PreservedMessage(
    val id: String,
    val text: String,
    val timestamp: String,
    val isFromMe: Boolean = false,
    val senderName: String? = null
)

data class PreservedChat(
    val id: String,
    val jid: String,
    val name: String,
    val isGroup: Boolean,
    val messages: List<PreservedMessage>
) {
    val lastMessage: PreservedMessage?
        get() = messages.lastOrNull()
}

@Composable
fun DeletedMessagesVaultScreen() {
    val navController = LocalWaexNavController.current
    val preferenceManager = LocalWaexPreferenceManager.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var isFeatureEnabled by remember {
        mutableStateOf(preferenceManager.getBoolean("preserve_delete_for_me", false))
    }

    var selectedTab by remember { mutableStateOf("individuals") } // "individuals" | "groups"
    var activeChatDetails by remember { mutableStateOf<PreservedChat?>(null) }

    // Sample preserved "Delete for me" chats with sent & received messages
    val sampleChats = remember {
        listOf(
            PreservedChat(
                id = "chat_1",
                jid = "+1 555 019 2834@s.whatsapp.net",
                name = "Alex Rivera",
                isGroup = false,
                messages = listOf(
                    PreservedMessage("1a", "Can you send the draft files before noon?", "10:15 AM", isFromMe = false),
                    PreservedMessage("1b", "Sure, I am wrapping up the final review now.", "10:28 AM", isFromMe = true),
                    PreservedMessage("1c", "Hey, did you review the project proposal I sent this morning?", "10:42 AM", isFromMe = false),
                    PreservedMessage("1d", "Yes, just checked it out. Looks solid!", "10:48 AM", isFromMe = true)
                )
            ),
            PreservedChat(
                id = "chat_2",
                jid = "+1 555 018 7392@s.whatsapp.net",
                name = "Sarah Chen",
                isGroup = false,
                messages = listOf(
                    PreservedMessage("2a", "Are we still on for the 2 PM design review?", "9:02 AM", isFromMe = true),
                    PreservedMessage("2b", "Let's postpone the call to 4 PM instead.", "9:15 AM", isFromMe = false),
                    PreservedMessage("2c", "Perfect, see you at 4 PM then.", "9:18 AM", isFromMe = true)
                )
            ),
            PreservedChat(
                id = "chat_3",
                jid = "120363024881@g.us",
                name = "Core Engineering",
                isGroup = true,
                messages = listOf(
                    PreservedMessage("3a", "The staging environment is upgraded to v2.4", "Yesterday, 4:20 PM", isFromMe = false, senderName = "David Kim"),
                    PreservedMessage("3b", "All CI/CD regression suites passed.", "Yesterday, 4:45 PM", isFromMe = true),
                    PreservedMessage("3c", "The deployment is scheduled for 8 PM UTC tonight.", "Yesterday, 6:05 PM", isFromMe = false, senderName = "Marcus Vance")
                )
            ),
            PreservedChat(
                id = "chat_4",
                jid = "120363098124@g.us",
                name = "Marketing Sync",
                isGroup = true,
                messages = listOf(
                    PreservedMessage("4a", "Please note the meeting room has been moved to Floor 4.", "Yesterday, 3:30 PM", isFromMe = false, senderName = "Maria Garcia"),
                    PreservedMessage("4b", "Got it, heading over now.", "Yesterday, 3:35 PM", isFromMe = true)
                )
            )
        )
    }

    val filteredChats = if (selectedTab == "individuals") {
        sampleChats.filter { !it.isGroup }
    } else {
        sampleChats.filter { it.isGroup }
    }

    AnimatedContent(
        targetState = activeChatDetails,
        transitionSpec = {
            if (targetState != null) {
                slideInHorizontally { width -> width } + fadeIn() togetherWith
                    slideOutHorizontally { width -> -width } + fadeOut()
            } else {
                slideInHorizontally { width -> -width } + fadeIn() togetherWith
                    slideOutHorizontally { width -> width } + fadeOut()
            }
        },
        label = "ChatListToDetailsTransition"
    ) { currentChat ->
        if (currentChat != null) {
            // ─── Chat Messages Conversation Screen ────────────────────────────
            PreservedChatDetailsScreen(
                chat = currentChat,
                onBack = { activeChatDetails = null }
            )
        } else {
            // ─── WhatsApp Style Main Chat List View ───────────────────────────
            Scaffold(
                topBar = {
                    WaexTopBar(
                        title = "\"Delete For Me\" Messages",
                        onBackClick = { navController.popBack() },
                        actions = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(end = spacing.stackSm)
                            ) {
                                StitchSwitch(
                                    checked = isFeatureEnabled,
                                    onCheckedChange = { checked ->
                                        isFeatureEnabled = checked
                                        preferenceManager.putBoolean("preserve_delete_for_me", checked)
                                    }
                                )
                            }
                        }
                    )
                },
                containerColor = colors.background
            ) { paddingValues ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    // Filter Selector (Individuals vs Groups)
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = spacing.pageMargin, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            listOf("individuals" to "Individuals", "groups" to "Groups").forEach { (tabId, label) ->
                                val active = selectedTab == tabId
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(radius.defaultShape)
                                        .background(if (active) colors.primary.copy(alpha = 0.12f) else colors.surfaceDim)
                                        .border(
                                            1.dp,
                                            if (active) colors.primary else colors.outlineVariant,
                                            radius.defaultShape
                                        )
                                        .clickable { selectedTab = tabId }
                                        .padding(vertical = 11.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        style = typography.bodyMd,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                        color = if (active) colors.primary else colors.onSurface
                                    )
                                }
                            }
                        }
                    }

                    // Main WhatsApp Chat List Items
                    if (filteredChats.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 64.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No preserved messages found",
                                    style = typography.bodyLg,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        items(filteredChats, key = { it.id }) { chat ->
                            WhatsAppChatListItem(
                                chat = chat,
                                onClick = { activeChatDetails = chat }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * WhatsApp Main Screen Style Chat Row Item
 */
@Composable
private fun WhatsAppChatListItem(
    chat: PreservedChat,
    onClick: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val lastMsg = chat.lastMessage

    val avatarGradient = Brush.linearGradient(
        colors = if (chat.isGroup) {
            listOf(Color(0xFF008069), Color(0xFF21C063))
        } else {
            listOf(colors.primary, colors.secondary)
        }
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Circular Avatar (WhatsApp style)
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(avatarGradient),
                contentAlignment = Alignment.Center
            ) {
                if (chat.isGroup) {
                    Icon(
                        imageVector = WaexIcons.Folder,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Text(
                        text = chat.name.firstOrNull()?.uppercase() ?: "?",
                        style = typography.headlineMd,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 20.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Contact Name & Last Message Column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // Top Row: Contact Name + Timestamp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = chat.name,
                        style = typography.bodyLg,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = lastMsg?.timestamp ?: "",
                        style = typography.labelSm,
                        color = colors.onSurfaceVariant
                    )
                }

                // Bottom Row: Last Message Snippet + Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val prefix = if (lastMsg?.isFromMe == true) "You: " else ""
                    Text(
                        text = "$prefix${lastMsg?.text ?: "No messages"}",
                        style = typography.bodyMd,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (chat.messages.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFEF4444).copy(alpha = 0.12f))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${chat.messages.size}",
                                style = typography.labelSm,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFEF4444)
                            )
                        }
                    }
                }
            }
        }

        // WhatsApp-like Inset Divider
        HorizontalDivider(
            modifier = Modifier.padding(start = 78.dp, end = 16.dp),
            thickness = 0.6.dp,
            color = colors.outlineVariant.copy(alpha = 0.4f)
        )
    }
}
