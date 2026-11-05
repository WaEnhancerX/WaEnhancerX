package com.waenhancer.ui.screens.media

import com.waenhancer.utils.UiText

import com.waenhancer.app.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import kotlin.math.roundToInt
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
import android.widget.Toast
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.waenhancer.config.FeatureCatalog
import com.waenhancer.config.FeatureItem
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.utils.LocalizationHelper
import kotlinx.coroutines.delay
import androidx.compose.foundation.BorderStroke
import com.waenhancer.ui.navigation.LocalIsPro
import com.waenhancer.ui.navigation.LocalOnActivatePro
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import com.waenhancer.ui.navigation.Screen


@Composable
fun MediaStatusHubScreen(targetEpoch: Int = 0) {
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

    val preferenceManager = LocalWaexPreferenceManager.current
    val mediaKeys = remember {
        listOf(
            "statusdowload", "status_downloader", "videoquality", "imagequality",
            "media_upload_quality", "send_audio_as_voice_status", "status_video_splitter", "file_size_spoofer",
            "download_profile", "download_video_note",
            "downloadviewonce", "download_view_once", "media_preview", "enable_media_preview",
            "parallel_downloading"
        )
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    fun readMediaKey(key: String): Boolean {
        // The UI must reflect the settings consumed by hooks, not stale legacy aliases.
        if (key == "media_upload_quality"
            || key == "downloadviewonce" || key == "download_view_once") {
            return MediaQuickSettingPolicy.isEnabled(key) { storedKey ->
                preferenceManager.getBoolean(storedKey, false)
            }
        }
        val fallback = if (key == "send_audio_as_voice_status") {
            preferenceManager.getBoolean("voice_status_enhancement", false)
        } else false
        return preferenceManager.getBoolean(key, fallback)
    }

    val settingsState = remember(preferenceManager, targetEpoch) {
        mutableStateMapOf<String, Boolean>().apply {
            mediaKeys.forEach { key -> put(key, readMediaKey(key)) }
        }
    }
    var saveProfileDuplicates by remember(preferenceManager, targetEpoch) {
        mutableStateOf(preferenceManager.getBoolean("download_profile_save_duplicates", true))
    }
    var saveStatusDuplicates by remember(preferenceManager, targetEpoch) {
        mutableStateOf(preferenceManager.getBoolean("status_download_save_duplicates", true))
    }
    var parallelConnections by remember(preferenceManager, targetEpoch) {
        mutableStateOf(preferenceManager.getInt("parallel_downloading_connections", 4))
    }

    // Refresh after returning from another settings screen (e.g. the Calls hub).
    // Never auto-migrate a legacy call_recording=true value into the recorder key:
    // that could enable audio recording without a new user action.
    DisposableEffect(lifecycleOwner, preferenceManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                mediaKeys.forEach { key -> settingsState[key] = readMediaKey(key) }
                saveProfileDuplicates = preferenceManager.getBoolean("download_profile_save_duplicates", true)
                saveStatusDuplicates = preferenceManager.getBoolean("status_download_save_duplicates", true)
                parallelConnections = preferenceManager.getInt("parallel_downloading_connections", 4)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val updatePreference: (String, Boolean) -> Unit = { key, value ->
        val changes = MediaQuickSettingPolicy.changesFor(key, value)
        if (preferenceManager.putBooleans(changes)) {
            // Read back committed canonical values. Both switches and row click
            // handlers use settingsState, so never show a failed save as successful.
            mediaKeys.forEach { mediaKey -> settingsState[mediaKey] = readMediaKey(mediaKey) }
        } else {
            Toast.makeText(context, UiText.get(R.string.waex_ui_unable_to_save_setting, "Unable to save setting"), Toast.LENGTH_SHORT).show()
        }
    }

    val isPro = LocalIsPro.current
    val onActivatePro = LocalOnActivatePro.current

    val mediaGroups = remember {
        FeatureCatalog.groupsFor(2, null).map { group ->
            MediaGroup(group.id, if (group.id == "media_quality_status") WaexIcons.Image else WaexIcons.Folder, group.items())
        }
    }


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
            border = BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.pageMargin)
                .clickable { navController.navigateTo(Screen.CallRecordingSettings) }
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
                        text = stringResource(R.string.call_recording_config_title),
                        style = typography.bodyLg,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    Text(
                        text = stringResource(R.string.call_recording_config_desc),
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
                        text = LocalizationHelper.getLocalizedSectionTitle(context, group.title),
                        style = typography.labelSm,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurfaceVariant
                    )
                }

                Surface(
                    shape = radius.bentoCardShape,
                    color = colors.surfaceDim,
                    border = BorderStroke(1.dp, colors.outlineVariant),
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
                            val locTitle = LocalizationHelper.getTitle(context, item)
                            val locDesc = LocalizationHelper.getDesc(context, item)
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
                                            text = locTitle,
                                            style = typography.bodyLg,
                                            fontWeight = FontWeight.Medium,
                                            color = colors.onSurface
                                        )
                                        val reqPro = stringResource(R.string.requires_pro_label)
                                        Text(
                                            text = if (isLocked) "$locDesc • $reqPro" else locDesc,
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
                            if (item.key == "download_profile" && (settingsState[item.key] ?: false)) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = colors.surfaceDim.copy(alpha = 0.6f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = UiText.get(R.string.profile_photo_saving,
                                                "PROFILE PHOTO SAVING"),
                                            style = typography.labelSm,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.primary
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(end = 8.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.save_profile_duplicates),
                                                    style = typography.bodyMd,
                                                    fontWeight = FontWeight.Medium,
                                                    color = colors.onSurface
                                                )
                                                Text(
                                                    text = stringResource(R.string.save_profile_duplicates_desc),
                                                    style = typography.labelSm,
                                                    color = colors.onSurfaceVariant
                                                )
                                            }
                                            StitchSwitch(
                                                checked = saveProfileDuplicates,
                                                onCheckedChange = { checked ->
                                                    preferenceManager.putBoolean("download_profile_save_duplicates", checked)
                                                    saveProfileDuplicates = preferenceManager.getBoolean(
                                                        "download_profile_save_duplicates", true
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            if (item.key == "status_downloader" && (settingsState[item.key] ?: false)) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = colors.surfaceDim.copy(alpha = 0.6f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = UiText.get(R.string.status_download_saving,
                                                "STATUS DOWNLOAD SAVING"),
                                            style = typography.labelSm,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.primary
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(end = 8.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.save_status_duplicates),
                                                    style = typography.bodyMd,
                                                    fontWeight = FontWeight.Medium,
                                                    color = colors.onSurface
                                                )
                                                Text(
                                                    text = stringResource(R.string.save_status_duplicates_desc),
                                                    style = typography.labelSm,
                                                    color = colors.onSurfaceVariant
                                                )
                                            }
                                            StitchSwitch(
                                                checked = saveStatusDuplicates,
                                                onCheckedChange = { checked ->
                                                    preferenceManager.putBoolean("status_download_save_duplicates", checked)
                                                    saveStatusDuplicates = preferenceManager.getBoolean(
                                                        "status_download_save_duplicates", true
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            if (item.key == "parallel_downloading" && (settingsState[item.key] ?: false)) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = colors.surfaceDim.copy(alpha = 0.6f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = stringResource(R.string.parallel_downloading_connections_title),
                                                style = typography.labelSm,
                                                fontWeight = FontWeight.Bold,
                                                color = colors.primary
                                            )
                                            Text(
                                                text = if (parallelConnections == 4) {
                                                    "$parallelConnections connections (Recommended)"
                                                } else {
                                                    "$parallelConnections connections"
                                                },
                                                style = typography.labelSm,
                                                fontWeight = FontWeight.SemiBold,
                                                color = colors.primary
                                            )
                                        }
                                        Text(
                                            text = stringResource(R.string.parallel_downloading_connections_desc),
                                            style = typography.labelSm,
                                            color = colors.onSurfaceVariant
                                        )

                                        var sliderValue by remember(parallelConnections) {
                                            mutableStateOf(parallelConnections.toFloat())
                                        }

                                        Slider(
                                            value = sliderValue,
                                            onValueChange = { sliderValue = it },
                                            onValueChangeFinished = {
                                                val count = sliderValue.roundToInt().coerceIn(2, 8)
                                                sliderValue = count.toFloat()
                                                preferenceManager.putInt("parallel_downloading_connections", count)
                                                parallelConnections = count
                                            },
                                            valueRange = 2f..8f,
                                            steps = 5,
                                            colors = SliderDefaults.colors(
                                                thumbColor = colors.primary,
                                                activeTrackColor = colors.primary,
                                                inactiveTrackColor = colors.outlineVariant
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "2 (Min)",
                                                style = typography.labelSm,
                                                color = colors.onSurfaceVariant
                                            )
                                            Text(
                                                text = "4 (Recommended)",
                                                style = typography.labelSm,
                                                color = if (parallelConnections == 4) colors.primary else colors.onSurfaceVariant,
                                                fontWeight = if (parallelConnections == 4) FontWeight.Bold else FontWeight.Normal
                                            )
                                            Text(
                                                text = "8 (Max)",
                                                style = typography.labelSm,
                                                color = colors.onSurfaceVariant
                                            )
                                        }
                                    }
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
    val items: List<FeatureItem>
)

@Preview(showBackground = true)
@Composable
fun MediaStatusHubScreenPreview() {
    WaexTheme {
        MediaStatusHubScreen()
    }
}
