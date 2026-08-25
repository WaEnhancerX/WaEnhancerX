package com.waenhancer.ui.screens.privacy

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.components.StitchSwitch
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager

data class DeletedMessageItem(
    val id: String,
    val senderName: String,
    val senderJid: String,
    val messageText: String,
    val timestamp: String,
    val isGroup: Boolean = false,
    val groupName: String? = null,
    val mediaType: String? = null // "image" | "audio" | "video" | null
)

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
    var selectedMessageForDetails by remember { mutableStateOf<DeletedMessageItem?>(null) }

    // Preserved "Delete for me" messages sample
    val sampleMessages = remember {
        listOf(
            DeletedMessageItem(
                id = "1",
                senderName = "Alex Rivera",
                senderJid = "+1 555 019 2834@s.whatsapp.net",
                messageText = "Hey, did you review the project proposal I sent this morning?",
                timestamp = "Today, 10:42 AM",
                isGroup = false
            ),
            DeletedMessageItem(
                id = "2",
                senderName = "Sarah Chen",
                senderJid = "+1 555 018 7392@s.whatsapp.net",
                messageText = "Let's postpone the call to 4 PM instead.",
                timestamp = "Today, 9:15 AM",
                isGroup = false
            ),
            DeletedMessageItem(
                id = "3",
                senderName = "David Kim",
                senderJid = "+1 555 014 9921@s.whatsapp.net",
                messageText = "The deployment is scheduled for 8 PM UTC tonight.",
                timestamp = "Yesterday, 6:05 PM",
                isGroup = true,
                groupName = "Core Engineering"
            ),
            DeletedMessageItem(
                id = "4",
                senderName = "Maria Garcia",
                senderJid = "+34 600 000 000@s.whatsapp.net",
                messageText = "The meeting room has been moved to Floor 4.",
                timestamp = "Yesterday, 3:30 PM",
                isGroup = true,
                groupName = "Marketing Sync"
            )
        )
    }

    val filteredList = if (selectedTab == "individuals") {
        sampleMessages.filter { !it.isGroup }
    } else {
        sampleMessages.filter { it.isGroup }
    }

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
                .padding(paddingValues),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = spacing.pageMargin,
                vertical = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Filter Selector
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                                .padding(vertical = 12.dp),
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

            // Message Cards List
            if (filteredList.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No preserved messages yet",
                            style = typography.bodyLg,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(filteredList, key = { it.id }) { message ->
                    DeletedMessageCard(
                        message = message,
                        onClick = { selectedMessageForDetails = message }
                    )
                }
            }
        }
    }
}

@Composable
private fun DeletedMessageCard(
    message: DeletedMessageItem,
    onClick: () -> Unit
) {
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(radius.defaultShape)
            .clickable(onClick = onClick),
        color = colors.surface,
        shape = radius.defaultShape,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Avatar/Icon + Sender + Timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEF4444).copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = WaexIcons.Folder,
                        contentDescription = null,
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (message.isGroup && message.groupName != null) {
                            "${message.senderName} (${message.groupName})"
                        } else {
                            message.senderName
                        },
                        style = typography.bodyLg,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    Text(
                        text = message.senderJid,
                        style = typography.labelSm,
                        color = colors.onSurfaceVariant
                    )
                }

                Text(
                    text = message.timestamp,
                    style = typography.labelSm,
                    color = colors.onSurfaceVariant
                )
            }

            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))

            // Body: Preserved Message Content
            Text(
                text = message.messageText,
                style = typography.bodyMd,
                color = colors.onSurface
            )

            // Badge / Tag
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFEF4444).copy(alpha = 0.10f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Preserved",
                        style = typography.labelSm,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFEF4444)
                    )
                }
            }
        }
    }
}
