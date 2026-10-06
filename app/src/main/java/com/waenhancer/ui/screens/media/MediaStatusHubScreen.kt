package com.waenhancer.ui.screens.media

import com.waenhancer.ui.components.WaexProChip

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.components.StitchSwitch
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.waenhancer.ui.navigation.LocalWaexNavController
import kotlinx.coroutines.delay


@Composable
fun MediaStatusHubScreen() {
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    val navController = LocalWaexNavController.current
    val scrollState = rememberScrollState()
    val itemCoordinates = remember { mutableStateMapOf<String, Float>() }
    var containerY by remember { mutableStateOf(0f) }

    LaunchedEffect(navController.scrollToTargetKey, itemCoordinates.keys.toList()) {
        val target = navController.scrollToTargetKey
        if (target != null && itemCoordinates.containsKey(target)) {
            val yOffset = itemCoordinates[target] ?: 0f
            scrollState.animateScrollTo(yOffset.toInt())
            navController.scrollToTargetKey = null
        }
    }

    LaunchedEffect(navController.highlightTargetKey) {
        val target = navController.highlightTargetKey
        if (target != null) {
            delay(2000)
            if (navController.highlightTargetKey == target) {
                navController.highlightTargetKey = null
            }
        }
    }

    val preferenceManager = com.waenhancer.ui.navigation.LocalWaexPreferenceManager.current
    val mediaKeys = remember {
        listOf(
            "statusdowload", "status_downloader", "videoquality", "imagequality",
            "media_upload_quality", "send_audio_as_voice_status",
            "call_recording", "download_profile", "download_video_note",
            "downloadviewonce", "download_view_once", "media_preview", "enable_media_preview"
        )
    }

    val settingsState = remember {
        mutableStateMapOf<String, Boolean>().apply {
            mediaKeys.forEach { key ->
                val defaultValue = if (key == "send_audio_as_voice_status") {
                    preferenceManager.getBoolean("voice_status_enhancement", false)
                } else false
                put(key, preferenceManager.getBoolean(key, defaultValue))
            }
        }
    }

    val updatePreference: (String, Boolean) -> Unit = { key, value ->
        settingsState[key] = value
        preferenceManager.putBoolean(key, value)
        if (key == "status_downloader" || key == "statusdowload") {
            preferenceManager.putBoolean("statusdowload", value)
            preferenceManager.putBoolean("status_downloader", value)
            settingsState["statusdowload"] = value
            settingsState["status_downloader"] = value
        } else if (key == "download_view_once" || key == "downloadviewonce") {
            preferenceManager.putBoolean("downloadviewonce", value)
            preferenceManager.putBoolean("download_view_once", value)
            settingsState["downloadviewonce"] = value
            settingsState["download_view_once"] = value
        } else if (key == "media_preview" || key == "enable_media_preview") {
            preferenceManager.putBoolean("enable_media_preview", value)
            preferenceManager.putBoolean("media_preview", value)
            settingsState["enable_media_preview"] = value
            settingsState["media_preview"] = value
        } else if (key == "send_audio_as_voice_status") {
            // Keep older installations compatible while the canonical key is migrated.
            preferenceManager.putBoolean("voice_status_enhancement", value)
        }
    }


    val isPro = com.waenhancer.ui.navigation.LocalIsPro.current
    val onActivatePro = com.waenhancer.ui.navigation.LocalOnActivatePro.current

    val mediaGroups = listOf(
        MediaGroup(
            "Media Quality & Status",
            WaexIcons.Image,
            listOf(
                MediaItem("Download and Share Status", "Shows two buttons to share and download status", "statusdowload"),
                MediaItem("HD Quality Images & Videos", "Send images and videos in HD quality by default", "media_upload_quality"),
                MediaItem("Audio to Voice Status", "Pick a local audio file and publish it as a voice status", "send_audio_as_voice_status", isPro = true)
            )
        ),
        MediaGroup(
            "Media Utility & Downloader",
            WaexIcons.Folder,
            listOf(
                MediaItem("Call Recording", "Record incoming and outgoing calls (Voice & Video) as audio", "call_recording"),
                MediaItem("Download View Once", "Show button to download view once media", "downloadviewonce"),
                MediaItem("Enable Media Preview", "Add a button to preview media in a temporary file", "media_preview"),
                MediaItem("Download Profile Photo", "Download full-resolution profile pictures directly", "download_profile")
            )
        )
    )


    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .onGloballyPositioned { containerCoordinates ->
                containerY = containerCoordinates.positionInRoot().y
            }
            .padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Quick Card for Advanced Call Recording
        Surface(
            shape = radius.bentoCardShape,
            color = colors.surfaceDim,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.pageMargin)
                .clickable { navController.navigateTo(com.waenhancer.ui.navigation.Screen.CallRecordingSettings) }
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = WaexIcons.Mic,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Call Recording Configuration",
                        style = typography.bodyLg,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    Text(
                        text = "Configure root direct audio pipeline, formats & bitrate",
                        style = typography.bodyMd,
                        color = colors.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = WaexIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }



        // Media setting cards
        mediaGroups.forEach { group ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.pageMargin)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                ) {
                    Icon(
                        imageVector = group.icon,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = group.title,
                        style = typography.labelSm,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurfaceVariant
                    )
                }

                Surface(
                    shape = radius.bentoCardShape,
                    color = colors.surfaceDim,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        group.items.forEachIndexed { index, item ->
                            val isLocked = item.isPro && !isPro
                            val isHighlighted = navController.highlightTargetKey == item.key
                            val highlightBgColor by animateColorAsState(
                                targetValue = if (isHighlighted) colors.primary.copy(alpha = 0.15f) else Color.Transparent,
                                animationSpec = tween(durationMillis = 300),
                                label = "highlight_bg"
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(highlightBgColor)
                                    .onGloballyPositioned { coordinates ->
                                        val y = coordinates.positionInRoot().y - containerY + scrollState.value
                                        itemCoordinates[item.key] = y
                                    }
                                    .clickable {
                                        if (isLocked) {
                                            onActivatePro()
                                        } else {
                                            val currentVal = settingsState[item.key] ?: false
                                            updatePreference(item.key, !currentVal)
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp)
                                        .then(if (isLocked) Modifier.alpha(0.6f) else Modifier),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = item.label,
                                            style = typography.bodyLg,
                                            fontWeight = FontWeight.Medium,
                                            color = colors.onSurface
                                        )
                                        Text(
                                            text = if (isLocked) "${item.sublabel} • Requires Pro" else item.sublabel,
                                            style = typography.bodyMd,
                                            color = colors.onSurfaceVariant,
                                            fontSize = 12.sp
                                        )
                                    }
                                    StitchSwitch(
                                        checked = if (isLocked) false else (settingsState[item.key] ?: false),
                                        onCheckedChange = if (isLocked) null else { isChecked -> updatePreference(item.key, isChecked) },
                                        enabled = !isLocked
                                    )
                                }
                                if (item.isPro) {
                                    WaexProChip(
                                        isUnlocked = isPro,
                                        modifier = Modifier.align(Alignment.TopEnd)
                                    )
                                }
                            }
                            if (index < group.items.lastIndex) {
                                HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(100.dp))
    }
}

private data class MediaGroup(
    val title: String,
    val icon: ImageVector,
    val items: List<MediaItem>
)

private data class MediaItem(
    val label: String,
    val sublabel: String,
    val key: String,
    val isPro: Boolean = false
)

@Preview(showBackground = true)
@Composable
fun MediaStatusHubScreenPreview() {
    WaexTheme {
        MediaStatusHubScreen()
    }
}
