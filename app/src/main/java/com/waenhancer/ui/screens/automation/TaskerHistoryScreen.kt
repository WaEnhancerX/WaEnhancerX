package com.waenhancer.ui.screens.automation

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
import androidx.compose.runtime.remember
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

data class TaskerHistoryItem(
    val id: String,
    val eventType: String, // "RECEIVED" | "SENT"
    val contactName: String,
    val details: String,
    val timestamp: String,
    val isSuccess: Boolean = true
)

@Composable
fun TaskerHistoryScreen() {
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    val historyItems = remember {
        listOf(
            TaskerHistoryItem(
                id = "1",
                eventType = "SENT",
                contactName = "+1 555-0192",
                details = "Auto-Reply: I am currently driving, will get back shortly.",
                timestamp = "Today, 2:15 PM"
            ),
            TaskerHistoryItem(
                id = "2",
                eventType = "RECEIVED",
                contactName = "Alex Thorne",
                details = "Triggered Macro: 'Forward to Telegram Bot'",
                timestamp = "Today, 1:40 PM"
            ),
            TaskerHistoryItem(
                id = "3",
                eventType = "SENT",
                contactName = "+971 50-0001",
                details = "Automated Morning Briefing Scheduled Dispatch",
                timestamp = "Today, 9:00 AM"
            )
        )
    }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = "Tasker Execution History",
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
            items(historyItems) { item ->
                Surface(
                    shape = radius.bentoCardShape,
                    color = colors.surfaceDim,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isSent = item.eventType == "SENT"
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (isSent) colors.primary.copy(alpha = 0.12f) else Color(0xFF22C55E).copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isSent) WaexIcons.Play else WaexIcons.Notifications,
                                contentDescription = null,
                                tint = if (isSent) colors.primary else Color(0xFF22C55E),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (isSent) "Action: Send Intent" else "Event: Message Received",
                                    style = typography.bodyLg,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSurface
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = item.timestamp,
                                    style = typography.labelSm,
                                    color = colors.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Target: ${item.contactName}",
                                style = typography.labelSm,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.primary,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = item.details,
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant,
                                fontSize = 12.sp
                            )
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
