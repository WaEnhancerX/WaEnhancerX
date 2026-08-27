package com.waenhancer.ui.screens.privacy

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.waenhancer.xposed.core.db.PreservedMessageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

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
    val context = LocalContext.current
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
    var isLoading by remember { mutableStateOf(true) }
    val preservedChats = remember { mutableStateListOf<PreservedChat>() }

    // Load real preserved chats from database
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val loadedList = loadRealPreservedChats(context)
            withContext(Dispatchers.Main) {
                preservedChats.clear()
                preservedChats.addAll(loadedList)
                isLoading = false
            }
        }
    }

    val filteredChats = if (selectedTab == "individuals") {
        preservedChats.filter { !it.isGroup }
    } else {
        preservedChats.filter { it.isGroup }
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
                    if (!isLoading && filteredChats.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 80.dp, horizontal = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(colors.surfaceDim),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = WaexIcons.Folder,
                                        contentDescription = null,
                                        tint = colors.onSurfaceVariant,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                Text(
                                    text = if (selectedTab == "individuals") "No preserved individual chats" else "No preserved group chats",
                                    style = typography.headlineMd,
                                    color = colors.onSurface
                                )
                                Text(
                                    text = "When messages are deleted via 'Delete For Me', they will be preserved and listed here.",
                                    style = typography.bodyMd,
                                    color = colors.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
 * Loads real preserved messages grouped by chat from the SQLite store.
 */
private fun loadRealPreservedChats(context: Context): List<PreservedChat> {
    val store = PreservedMessageStore.getInstance(context)
    val records = store.getAllPreservedMessages()
    if (records.isEmpty()) return emptyList()

    val timeFormatter = DateFormat.getTimeInstance(DateFormat.SHORT)
    val chatsMap = mutableMapOf<String, MutableList<PreservedMessage>>()
    val chatNamesMap = mutableMapOf<String, String>()
    val chatGroupMap = mutableMapOf<String, Boolean>()

    for (rec in records) {
        val jid = if (rec.jid.isNullOrEmpty()) "Unknown" else rec.jid
        val timeStr = if (rec.timestamp > 0) timeFormatter.format(Date(rec.timestamp)) else "Preserved"
        val msg = PreservedMessage(
            id = rec.id,
            text = rec.text.ifBlank { "Preserved message" },
            timestamp = timeStr,
            isFromMe = rec.isFromMe,
            senderName = if (rec.isGroup && !rec.isFromMe && rec.senderName.isNotBlank()) rec.senderName else null
        )
        chatsMap.computeIfAbsent(jid) { mutableListOf() }.add(msg)

        if (rec.chatName.isNotBlank() &&
            rec.chatName != "Unknown" &&
            !rec.chatName.endsWith("@s.whatsapp.net") &&
            !rec.chatName.endsWith("@lid") &&
            !rec.chatName.all { it.isDigit() }
        ) {
            chatNamesMap[jid] = rec.chatName
        }
        chatGroupMap[jid] = rec.isGroup
    }

    return chatsMap.map { (jid, msgs) ->
        val isGroup = chatGroupMap[jid] ?: (jid.contains("@g.us") || jid.contains("-"))
        val storedName = chatNamesMap[jid]

        val displayName = when {
            !storedName.isNullOrBlank() -> storedName
            isGroup -> jid.substringBefore("@")
            else -> {
                // Try resolving via system Contacts provider
                val userPart = jid.substringBefore("@")
                val contactName = resolveSystemContactName(context, userPart)
                if (!contactName.isNullOrBlank()) {
                    contactName
                } else if (userPart.all { it.isDigit() } && userPart.length in 10..15) {
                    "+$userPart"
                } else {
                    userPart
                }
            }
        }
        PreservedChat(
            id = jid,
            jid = jid,
            name = displayName,
            isGroup = isGroup,
            messages = msgs
        )
    }
}

/**
 * Resolves contact display name from Android System Contacts provider.
 */
private fun resolveSystemContactName(context: Context, number: String): String? {
    val cleanNumber = number.replace(Regex("[^0-9]"), "")
    if (cleanNumber.length < 7) return null
    try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(cleanNumber))
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0)
                if (!name.isNullOrBlank()) return name
            }
        }
    } catch (ignored: Throwable) {}
    return null
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
