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
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController

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
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var selectedTab by remember { mutableStateOf("individuals") } // "individuals" | "groups"

    val sampleMessages = remember {
        listOf(
            DeletedMessageItem(
                id = "1",
                senderName = "Sarah Connor",
                senderJid = "+1 555-0199@s.whatsapp.net",
                messageText = "Hey! Let's meet at 5 PM at the central station instead.",
                timestamp = "Today, 1:45 PM",
                isGroup = false
            ),
            DeletedMessageItem(
                id = "2",
                senderName = "David Kim",
                senderJid = "+82 10-1234-5678@s.whatsapp.net",
                messageText = "Here is the project proposal draft before review.",
                timestamp = "Today, 11:20 AM",
                isGroup = false,
                mediaType = "image"
            ),
            DeletedMessageItem(
                id = "3",
                senderName = "Alex Thorne",
                senderJid = "+44 7700 900077@s.whatsapp.net",
                messageText = "Please ignore my previous voice note, it was meant for someone else.",
                timestamp = "Yesterday, 8:12 PM",
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
                title = "Deleted Messages Vault",
                onBackClick = { navController.popBack() }
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

            // Anti-Revoke Notice Banner
            item {
                Surface(
                    shape = radius.bentoCardShape,
                    color = colors.primary.copy(alpha = 0.08f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.primary.copy(alpha = 0.2f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = WaexIcons.Security,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Anti-Revoke intercepts revoked message packets locally and preserves their text, media previews, and timestamps.",
                            style = typography.bodyMd,
                            color = colors.onSurface,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            // Message Cards
            items(filteredList) { item ->
                Surface(
                    shape = radius.bentoCardShape,
                    color = colors.surfaceDim,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(colors.primary.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = item.senderName.first().toString(),
                                    style = typography.bodyMd,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.primary
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = item.senderName,
                                        style = typography.bodyLg,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface
                                    )
                                    if (item.isGroup && item.groupName != null) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "in ${item.groupName}",
                                            style = typography.labelSm,
                                            color = colors.primary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                                Text(
                                    text = item.timestamp,
                                    style = typography.labelSm,
                                    color = colors.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color(0xFFEF4444).copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "Revoked",
                                    style = typography.labelSm,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFEF4444),
                                    fontSize = 10.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = item.messageText,
                            style = typography.bodyMd,
                            color = colors.onSurface,
                            lineHeight = 20.sp
                        )

                        if (item.mediaType != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .clip(radius.defaultShape)
                                    .background(colors.surface)
                                    .border(1.dp, colors.outlineVariant, radius.defaultShape)
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = WaexIcons.Image,
                                        contentDescription = null,
                                        tint = colors.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Preserved Media Attachment",
                                        style = typography.labelSm,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.onSurface,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
