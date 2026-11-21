package com.waenhancer.ui.screens.automation

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import androidx.compose.foundation.BorderStroke

@Composable
fun TaskerGuideScreen() {
    val context = LocalContext.current
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, UiText.get(R.string.waex_ui_copied_1_s_to_clipboard, "Copied %1\$s to clipboard", label), Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = UiText.get(R.string.title_tasker_guide, "Tasker Integration Guide"),
                onBackClick = { navController.popBack() }
            )
        },
        containerColor = colors.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.pageMargin, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Overview Banner
            Surface(
                shape = radius.bentoCardShape,
                color = colors.primary.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = WaexIcons.AutoAwesome,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = UiText.get(R.string.waex_ui_automate_whatsapp_workflows_using_inte, "Automate WhatsApp workflows using Intent broadcasts in Tasker, MacroDroid, and Automate."),
                        style = typography.bodyMd,
                        color = colors.onSurface,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            // Event 1: Message Received
            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceDim,
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = UiText.get(R.string.waex_ui_event_incoming_message, "EVENT: INCOMING MESSAGE"),
                        style = typography.labelSm,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = UiText.get(R.string.waex_ui_trigger_automation_whenever_a_new_mess, "Trigger automation whenever a new message is received in WhatsApp."),
                        style = typography.bodyMd,
                        color = colors.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Code snippet box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(radius.defaultShape)
                            .background(colors.surface)
                            .border(1.dp, colors.outlineVariant, radius.defaultShape)
                            .clickable { copyToClipboard(UiText.get(R.string.waex_ui_action, "Action"), "com.waenhancer.MESSAGE_RECEIVED") }
                            .padding(14.dp)
                    ) {
                        Column {
                            Text(text = UiText.get(R.string.waex_ui_action_intent, "Action Intent:"), style = typography.labelSm, color = colors.onSurfaceVariant, fontSize = 11.sp)
                            Text(
                                text = "com.waenhancer.MESSAGE_RECEIVED",
                                style = typography.bodyMd,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = colors.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = UiText.get(R.string.waex_ui_available_extras, "Available Extras:"), style = typography.labelSm, color = colors.onSurfaceVariant, fontSize = 11.sp)
                            Text(
                                text = UiText.get(R.string.waex_ui_name_sender_name_number_sender_jid_pho, "%name (Sender Name)\n%number (Sender JID / Phone)\n%message (Text Content)"),
                                style = typography.bodyMd,
                                fontFamily = FontFamily.Monospace,
                                color = colors.onSurface,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }

            // Action 2: Send Message Intent
            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceDim,
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = UiText.get(R.string.waex_ui_action_send_message_automatically, "ACTION: SEND MESSAGE AUTOMATICALLY"),
                        style = typography.labelSm,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = UiText.get(R.string.waex_ui_broadcast_intent_to_send_a_message_wit, "Broadcast intent to send a message without opening the WhatsApp interface."),
                        style = typography.bodyMd,
                        color = colors.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(radius.defaultShape)
                            .background(colors.surface)
                            .border(1.dp, colors.outlineVariant, radius.defaultShape)
                            .clickable { copyToClipboard(UiText.get(R.string.waex_ui_action, "Action"), "com.waenhancer.MESSAGE_SENT") }
                            .padding(14.dp)
                    ) {
                        Column {
                            Text(text = UiText.get(R.string.waex_ui_action_intent, "Action Intent:"), style = typography.labelSm, color = colors.onSurfaceVariant, fontSize = 11.sp)
                            Text(
                                text = "com.waenhancer.MESSAGE_SENT",
                                style = typography.bodyMd,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = colors.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = UiText.get(R.string.waex_ui_required_extras, "Required Extras:"), style = typography.labelSm, color = colors.onSurfaceVariant, fontSize = 11.sp)
                            Text(
                                text = UiText.get(R.string.waex_ui_extra_number_1234567890_extra_message, "Extra: number:1234567890\nExtra: message:Your automated reply here"),
                                style = typography.bodyMd,
                                fontFamily = FontFamily.Monospace,
                                color = colors.onSurface,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
