package com.waenhancer.ui.screens.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.waenhancer.ui.components.WaexCard
import com.waenhancer.ui.components.WaexSectionHeader
import com.waenhancer.ui.components.WaexSwitchPreference
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController

@Composable
fun ConversationEnhancementsScreen() {
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing

    var alwaysOnlineChecked by remember { mutableStateOf(false) }
    var customStatusViewChecked by remember { mutableStateOf(true) }
    var animatedEmojisChecked by remember { mutableStateOf(true) }
    var customAvatarChecked by remember { mutableStateOf(false) }
    var customMenusChecked by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = "Chat Enhancements",
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
                .padding(spacing.pageMargin),
            verticalArrangement = Arrangement.spacedBy(spacing.stackLg)
        ) {
            WaexSectionHeader(
                title = "Presence & Customization",
                subtitle = "Modify chat visibility and menu layout items"
            )

            WaexCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    WaexSwitchPreference(
                        title = "Always Online",
                        description = "Force your connection status to show as Online constantly inside WhatsApp.",
                        checked = alwaysOnlineChecked,
                        onCheckedChange = { alwaysOnlineChecked = it },
                        icon = WaexIcons.Lock,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = "Custom Status View Layout",
                        description = "Display full-sized preview bars for WhatsApp status updates.",
                        checked = customStatusViewChecked,
                        onCheckedChange = { customStatusViewChecked = it },
                        icon = WaexIcons.Folder,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = "Animated Emojis",
                        description = "Enable animated rendering of stickers and support emojis.",
                        checked = animatedEmojisChecked,
                        onCheckedChange = { animatedEmojisChecked = it },
                        icon = WaexIcons.Settings,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = "Custom Avatars Mode",
                        description = "Replace standard group contact avatars with local custom overrides.",
                        checked = customAvatarChecked,
                        onCheckedChange = { customAvatarChecked = it },
                        icon = WaexIcons.Info,
                        showDivider = true
                    )
                    WaexSwitchPreference(
                        title = "Custom Action Menus",
                        description = "Add quick-action icons directly inside chat bubble menus.",
                        checked = customMenusChecked,
                        onCheckedChange = { customMenusChecked = it },
                        icon = WaexIcons.Settings,
                        showDivider = false
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ConversationEnhancementsScreenPreview() {
    WaexTheme {
        ConversationEnhancementsScreen()
    }
}
