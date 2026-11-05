package com.waenhancer.ui.screens.calls

import com.waenhancer.utils.UiText

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.waenhancer.calls.CallRecordingStore
import com.waenhancer.ui.components.RequestRuntimePermissionOnUse
import com.waenhancer.ui.components.WaexConfirmationBottomSheet
import com.waenhancer.ui.components.WaexSearchBar
import com.waenhancer.ui.components.StitchSwitch
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.waenhancer.app.R

data class RecordedCallItem(
    val file: File,
    val name: String,
    val dateStr: String,
    val sizeStr: String,
    val durationStr: String,
    val contactName: String,
    val contactJid: String?,
    val direction: String,
    val targetPackage: String
)

@Composable
fun CallsHubScreen() {
    val navController = LocalWaexNavController.current
    val prefManager = LocalWaexPreferenceManager.current
    val context = LocalContext.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    var selectedTab by remember { mutableStateOf("configs") } // "configs" | "recordings"
    var canReadLegacyRecordings by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    RequestRuntimePermissionOnUse(
        permission = Manifest.permission.READ_EXTERNAL_STORAGE,
        enabled = selectedTab == "recordings" && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU,
        onResult = { canReadLegacyRecordings = it }
    )

    // Call Blocker & Privacy State
    var callPrivacyMode by remember {
        val raw = try {
            prefManager.getString("call_privacy", "0")
        } catch (_: Throwable) {
            "0"
        }
        mutableStateOf(if (raw == "true") "1" else if (raw == "false") "0" else (raw ?: "0"))
    }
    var callRejectType by remember(prefManager.targetEpoch) { mutableStateOf(prefManager.getString("call_type", "no_internet") ?: "no_internet") }
    var callInfoEnabled by remember(prefManager.targetEpoch) { mutableStateOf(prefManager.getBoolean("call_info", false)) }

    // Call Recording State
    var callRecordingEnabled by remember(prefManager.targetEpoch) { mutableStateOf(prefManager.getBoolean("call_recording_enabled", false)) }
    var preferCallAudio by remember(prefManager.targetEpoch) { mutableStateOf(prefManager.getBoolean("call_recording_use_root", false)) }
    var audioFormat by remember(prefManager.targetEpoch) { mutableStateOf(prefManager.getString("call_recording_format", "m4a") ?: "m4a") }

    // Recordings list state
    val recordingsList = remember { mutableStateListOf<RecordedCallItem>() }
    var isLoadingRecordings by remember { mutableStateOf(false) }
    var currentlyPlayingPath by remember { mutableStateOf<String?>(null) }
    var expandedRecordingPath by remember { mutableStateOf<String?>(null) }
    var isAudioPlaying by remember { mutableStateOf(false) }
    var playbackPosition by remember { mutableStateOf(0) }
    var playbackDuration by remember { mutableStateOf(0) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var selectedRecordingPaths by remember { mutableStateOf(emptySet<String>()) }
    var recordingsPendingDelete by remember { mutableStateOf<List<RecordedCallItem>>(emptyList()) }
    var recordingSearchQuery by remember { mutableStateOf("") }
    var recordingAppFilter by remember { mutableStateOf("all") }

    fun stopAudio() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Throwable) {}
        mediaPlayer = null
        currentlyPlayingPath = null
        isAudioPlaying = false
        playbackPosition = 0
        playbackDuration = 0
    }

    fun clearRecordingSelection() {
        selectedRecordingPaths = emptySet()
    }

    fun toggleRecordingSelection(item: RecordedCallItem) {
        selectedRecordingPaths = if (item.file.absolutePath in selectedRecordingPaths) {
            selectedRecordingPaths - item.file.absolutePath
        } else {
            selectedRecordingPaths + item.file.absolutePath
        }
    }

    fun shareSelectedRecordings() {
        val selected = recordingsList.filter { it.file.absolutePath in selectedRecordingPaths }
        if (selected.isEmpty()) return
        runCatching {
            val uris = ArrayList(selected.map {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it.file)
            })
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }.apply {
                type = "audio/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri(UiText.get(R.string.waex_ui_call_recording, "Call recording"), uris.first())
            }
            context.startActivity(Intent.createChooser(intent, UiText.get(R.string.waex_ui_share_call_recording, "Share call recording")))
            clearRecordingSelection()
        }.onFailure {
            Toast.makeText(context, UiText.get(R.string.waex_ui_couldn_t_share_the_selected_recording, "Couldn't share the selected recording"), Toast.LENGTH_SHORT).show()
        }
    }

    fun deleteRecordings(selected: List<RecordedCallItem>) {
        val selectedPaths = selected.mapTo(mutableSetOf()) { it.file.absolutePath }
        if (currentlyPlayingPath?.let { it in selectedPaths } == true) stopAudio()
        val store = CallRecordingStore.get(context)
        val removed = selected.filter { item ->
            runCatching { item.file.delete() }.getOrDefault(false).also { if (it) store.deleteByPath(item.file.absolutePath) }
        }
        recordingsList.removeAll(removed.toSet())
        clearRecordingSelection()
        recordingsPendingDelete = emptyList()
        Toast.makeText(context, UiText.quantity(R.plurals.waex_recordings_deleted, removed.size, "Deleted %d recording(s)", removed.size), Toast.LENGTH_SHORT).show()
    }

    DisposableEffect(Unit) {
        onDispose {
            stopAudio()
        }
    }

    fun loadRecordings() {
        isLoadingRecordings = true
        recordingsList.clear()

        val searchDirs = listOf(
            File(context.getExternalFilesDir(null), "CallRecordings"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS), "WAEX_Calls"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "WAEX_Calls"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "WaEnhancer/Recordings"),
            File(context.filesDir, "call_recordings")
        )

        val metadata = CallRecordingStore.get(context).all().associateBy { it.recordingPath }
        val foundFiles = mutableListOf<File>()
        for (dir in searchDirs) {
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && (file.name.endsWith(".m4a") || file.name.endsWith(".wav") || file.name.endsWith(".opus") || file.name.endsWith(".mp3") || file.name.endsWith(".aac"))) {
                        foundFiles.add(file)
                    }
                }
            }
        }

        foundFiles.sortByDescending { it.lastModified() }
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, UiText.displayLocale())

        for (file in foundFiles) {
            val sizeKb = file.length() / 1024
            val sizeStr = if (sizeKb > 1024) String.format(Locale.getDefault(), "%.1f MB", sizeKb / 1024f) else "$sizeKb KB"
            val record = metadata[file.absolutePath]
            val dateStr = dateFormat.format(Date(record?.startedAt ?: file.lastModified()))
            val durationStr = formatPlaybackTime(readAudioDuration(file))

            recordingsList.add(
                RecordedCallItem(
                    file = file,
                    name = file.nameWithoutExtension,
                    dateStr = dateStr,
                    sizeStr = sizeStr,
                    durationStr = durationStr,
                    contactName = record?.contactName ?: file.nameWithoutExtension,
                    contactJid = record?.contactJid,
                    direction = record?.direction ?: "unknown",
                    targetPackage = record?.targetPackage ?: "unknown"
                )
            )
        }
        isLoadingRecordings = false
    }

    LaunchedEffect(selectedTab, canReadLegacyRecordings) {
        if (selectedTab == "recordings" && canReadLegacyRecordings) {
            loadRecordings()
        } else {
            stopAudio()
        }
    }

    LaunchedEffect(currentlyPlayingPath, isAudioPlaying) {
        while (currentlyPlayingPath != null) {
            val player = mediaPlayer
            if (player != null) {
                playbackPosition = runCatching { player.currentPosition }.getOrDefault(0)
                playbackDuration = runCatching { player.duration }.getOrDefault(0)
                isAudioPlaying = runCatching { player.isPlaying }.getOrDefault(false)
            }
            delay(250)
        }
    }

    val visibleRecordings = recordingsList.filter { item ->
        val matchesApp = recordingAppFilter == "all" || item.targetPackage == recordingAppFilter
        val matchesName = recordingSearchQuery.isBlank() ||
            item.contactName.contains(recordingSearchQuery.trim(), ignoreCase = true)
        matchesApp && matchesName
    }
    val recordingAppFilters = recordingsList
        .map { it.targetPackage }
        .filter { it.isNotBlank() && it != "unknown" }
        .distinct()
        .map { packageName ->
            val installedName = runCatching {
                val pm = context.packageManager
                val launchIntent = pm.getLaunchIntentForPackage(packageName)
                val activityInfo = launchIntent?.resolveActivityInfo(pm, 0)
                activityInfo?.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
                    ?: pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: packageName
            packageName to installedName
        }
        .sortedBy { it.second.lowercase(Locale.getDefault()) }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = if (selectedRecordingPaths.isEmpty()) UiText.get(R.string.waex_ui_calls_recording, "Calls & Recording") else UiText.get(R.string.waex_ui_1_s_selected, "%1\$s selected", selectedRecordingPaths.size),
                onBackClick = if (selectedRecordingPaths.isEmpty()) {
                    { navController.popBack() }
                } else {
                    { clearRecordingSelection() }
                },
                actions = if (selectedRecordingPaths.isEmpty()) null else {
                    {
                        IconButton(onClick = { shareSelectedRecordings() }) {
                            Icon(WaexIcons.Share, contentDescription = UiText.get(R.string.waex_ui_share_selected, "Share selected"), tint = colors.onSurface)
                        }
                        IconButton(onClick = {
                            recordingsPendingDelete = recordingsList.filter {
                                it.file.absolutePath in selectedRecordingPaths
                            }
                        }) {
                            Icon(Icons.Rounded.Delete, contentDescription = UiText.get(R.string.waex_ui_delete_selected, "Delete selected"), tint = colors.error)
                        }
                    }
                }
            )
        },
        containerColor = colors.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ── Top Sub-Tab Navigation Bar ──────────────────────────────────────────
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.pageMargin, vertical = 12.dp)
                    .height(44.dp)
                    .clip(radius.defaultShape)
                    .background(colors.surfaceDim)
                    .padding(3.dp)
            ) {
                val totalWidth = maxWidth
                val tabWidth = totalWidth / 2
                val tabOffset = if (selectedTab == "configs") 0.dp else tabWidth

                val animatedOffset by animateDpAsState(
                    targetValue = tabOffset,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "tab_offset"
                )

                // Sliding highlight box
                Box(
                    modifier = Modifier
                        .offset(x = animatedOffset)
                        .width(tabWidth)
                        .fillMaxHeight()
                        .clip(radius.defaultShape)
                        .background(colors.surface)
                        .border(1.dp, colors.outlineVariant, radius.defaultShape)
                )

                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    val configsLabel = stringResource(R.string.title_call_configurations)
                    val recordingsLabel = if (recordingsList.isNotEmpty()) {
                        "${stringResource(R.string.title_call_recordings)} (${recordingsList.size})"
                    } else {
                        stringResource(R.string.title_call_recordings)
                    }
                    listOf(
                        "configs" to configsLabel,
                        "recordings" to recordingsLabel
                    ).forEach { (tabId, label) ->
                        val isSelected = selectedTab == tabId
                        val textColor by animateColorAsState(
                            targetValue = if (isSelected) colors.primary else colors.onSurfaceVariant,
                            animationSpec = tween(150),
                            label = "tab_text_color"
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(1f)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { selectedTab = tabId },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = typography.bodyMd,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = textColor
                            )
                        }
                    }
                }
            }

            // ── Tab Content ────────────────────────────────────────────────────────
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(150))
                },
                modifier = Modifier.fillMaxSize(),
                label = "calls_hub_tab_content"
            ) { tab ->
                if (tab == "configs") {
                    // ══════════════════════════════════════════════════════════════
                    // TAB 1: ALL CALL & RECORDING CONFIGURATIONS
                    // ══════════════════════════════════════════════════════════════
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = spacing.pageMargin)
                            .padding(bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // ── SECTION 1: WHO CAN CALL ME ─────────────────────────────────
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = UiText.get(R.string.waex_ui_who_can_call_me, "WHO CAN CALL ME"),
                                style = typography.labelSm,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp)
                            )

                            Surface(
                                shape = radius.bentoCardShape,
                                color = colors.surfaceDim,
                                border = BorderStroke(1.dp, colors.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    val blockerOptions = listOf(
                                        "0" to (UiText.get(R.string.waex_ui_everyone_no_block, "Everyone (No Block)") to UiText.get(R.string.waex_ui_allow_all_whatsapp_incoming_voice_vide, "Allow all WhatsApp incoming voice & video calls")),
                                        "1" to (UiText.get(R.string.waex_ui_block_all_calls, "Block All Calls") to UiText.get(R.string.waex_ui_automatically_drop_every_incoming_what, "Automatically drop every incoming WhatsApp call")),
                                        "2" to (UiText.get(R.string.waex_ui_unknown_contacts_only, "Unknown Contacts Only") to UiText.get(R.string.waex_ui_only_allow_numbers_saved_in_your_phone, "Only allow numbers saved in your phone address book"))
                                    )

                                    blockerOptions.forEachIndexed { index, (value, details) ->
                                        val isChosen = callPrivacyMode == value
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    callPrivacyMode = value
                                                    prefManager.putString("call_privacy", value)
                                                }
                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Compact Radio Indicator
                                            Box(
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .clip(CircleShape)
                                                    .border(
                                                        1.5.dp,
                                                        if (isChosen) colors.primary else colors.outlineVariant,
                                                        CircleShape
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (isChosen) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(9.dp)
                                                            .clip(CircleShape)
                                                            .background(colors.primary)
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.width(12.dp))

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = details.first,
                                                    style = typography.bodyMd,
                                                    fontWeight = if (isChosen) FontWeight.SemiBold else FontWeight.Medium,
                                                    color = if (isChosen) colors.primary else colors.onSurface,
                                                    fontSize = 13.sp
                                                )
                                                Text(
                                                    text = details.second,
                                                    style = typography.bodyMd,
                                                    color = colors.onSurfaceVariant,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }

                                        if (index < blockerOptions.size - 1) {
                                            HorizontalDivider(
                                                color = colors.outlineVariant.copy(alpha = 0.5f),
                                                thickness = 1.dp,
                                                modifier = Modifier.padding(horizontal = 16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // ── SECTION 2: REJECTION ACTION SIMULATION ─────────────────────
                        if (callPrivacyMode != "0") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = UiText.get(R.string.waex_ui_call_rejection_appearance, "CALL REJECTION APPEARANCE"),
                                    style = typography.labelSm,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp)
                                )

                                Surface(
                                    shape = radius.bentoCardShape,
                                    color = colors.surfaceDim,
                                    border = BorderStroke(1.dp, colors.outlineVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        val rejectOptions = listOf(
                                            "no_internet" to (UiText.get(R.string.waex_ui_no_internet_connection, "No Internet Connection") to UiText.get(R.string.waex_ui_caller_sees_calling_without_connection, "Caller sees 'Calling...' without connection tone")),
                                            "busy" to (UiText.get(R.string.waex_ui_user_busy, "User Busy") to UiText.get(R.string.waex_ui_simulates_immediate_line_busy_tone, "Simulates immediate line busy tone")),
                                            "declined" to (UiText.get(R.string.waex_ui_call_declined, "Call Declined") to UiText.get(R.string.waex_ui_instantly_rejects_and_shows_call_decli, "Instantly rejects and shows call declined")),
                                            "uncallable" to (UiText.get(R.string.waex_ui_not_available_title, "Not Available") to UiText.get(R.string.waex_ui_simulates_caller_not_reachable, "Simulates caller not reachable"))
                                        )

                                        rejectOptions.forEachIndexed { index, (typeVal, details) ->
                                            val isChosen = callRejectType == typeVal
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        callRejectType = typeVal
                                                        prefManager.putString("call_type", typeVal)
                                                    }
                                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(18.dp)
                                                        .clip(CircleShape)
                                                        .border(
                                                            1.5.dp,
                                                            if (isChosen) colors.primary else colors.outlineVariant,
                                                            CircleShape
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (isChosen) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(9.dp)
                                                                .clip(CircleShape)
                                                                .background(colors.primary)
                                                        )
                                                    }
                                                }

                                                Spacer(modifier = Modifier.width(12.dp))

                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = details.first,
                                                        style = typography.bodyMd,
                                                        fontWeight = if (isChosen) FontWeight.SemiBold else FontWeight.Medium,
                                                        color = if (isChosen) colors.primary else colors.onSurface,
                                                        fontSize = 13.sp
                                                    )
                                                    Text(
                                                        text = details.second,
                                                        style = typography.bodyMd,
                                                        color = colors.onSurfaceVariant,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            }

                                            if (index < rejectOptions.size - 1) {
                                                HorizontalDivider(
                                                    color = colors.outlineVariant.copy(alpha = 0.5f),
                                                    thickness = 1.dp,
                                                    modifier = Modifier.padding(horizontal = 16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ── SECTION 3: CALL CONTROLS & DIAGNOSTICS ─────────────────────
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = UiText.get(R.string.waex_ui_call_enhancements, "CALL ENHANCEMENTS"),
                                style = typography.labelSm,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp)
                            )

                            Surface(
                                shape = radius.bentoCardShape,
                                color = colors.surfaceDim,
                                border = BorderStroke(1.dp, colors.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    // Additional Call Information Row
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = UiText.get(R.string.waex_ui_additional_call_information, "Additional Call Information"),
                                                style = typography.bodyLg,
                                                fontWeight = FontWeight.SemiBold,
                                                color = colors.onSurface
                                            )
                                            Text(
                                                text = UiText.get(R.string.waex_ui_receive_detailed_notification_with_cal, "Receive detailed notification with caller IP, location, device & version after calls end"),
                                                style = typography.bodyMd,
                                                color = colors.onSurfaceVariant,
                                                fontSize = 12.sp
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        StitchSwitch(
                                            checked = callInfoEnabled,
                                            onCheckedChange = {
                                                callInfoEnabled = it
                                                prefManager.putBoolean("call_info", it)
                                            }
                                        )
                                    }

                                    HorizontalDivider(
                                        color = colors.outlineVariant.copy(alpha = 0.5f),
                                        thickness = 1.dp,
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )

                                    // Call Recording Master Row
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = UiText.get(R.string.waex_ui_record_whatsapp_calls, "Record WhatsApp Calls"),
                                                style = typography.bodyLg,
                                                fontWeight = FontWeight.SemiBold,
                                                color = colors.onSurface
                                            )
                                            Text(
                                                text = UiText.get(R.string.waex_ui_automatically_capture_incoming_outgoin, "Automatically capture incoming & outgoing voice calls"),
                                                style = typography.bodyMd,
                                                color = colors.onSurfaceVariant,
                                                fontSize = 12.sp
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        StitchSwitch(
                                            checked = callRecordingEnabled,
                                            onCheckedChange = {
                                                callRecordingEnabled = it
                                                prefManager.putBoolean("call_recording_enabled", it)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // ── SECTION 4: RECORDING CONFIGURATION (when recording enabled) ─
                        if (callRecordingEnabled) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = UiText.get(R.string.waex_ui_recording_configuration, "RECORDING CONFIGURATION"),
                                    style = typography.labelSm,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp)
                                )

                                Surface(
                                    shape = radius.bentoCardShape,
                                    color = colors.surfaceDim,
                                    border = BorderStroke(1.dp, colors.outlineVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        // Capture engine row
                                        Text(
                                            text = UiText.get(R.string.waex_ui_audio_capture_engine, "Audio Capture Engine"),
                                            style = typography.bodyMd,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.onSurface,
                                            fontSize = 13.sp
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            listOf(
                                                true to (UiText.get(R.string.waex_ui_call_audio_v36, "Call audio") to UiText.get(R.string.waex_ui_call_audio_if_supported_v36, "If supported")),
                                                false to (UiText.get(R.string.waex_ui_standard_audio_v36, "Standard audio") to UiText.get(R.string.waex_ui_communication_mic_v36, "Communication / mic"))
                                            ).forEach { (preferCall, labels) ->
                                                val active = preferCallAudio == preferCall
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(radius.defaultShape)
                                                        .background(if (active) colors.primary.copy(alpha = 0.12f) else colors.surface)
                                                        .border(
                                                            1.dp,
                                                            if (active) colors.primary else colors.outlineVariant,
                                                            radius.defaultShape
                                                        )
                                                        .clickable {
                                                            preferCallAudio = preferCall
                                                            prefManager.putBoolean("call_recording_use_root", preferCall)
                                                        }
                                                        .padding(vertical = 10.dp, horizontal = 12.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                        Text(
                                                            text = labels.first,
                                                            style = typography.bodyMd,
                                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                                            color = if (active) colors.primary else colors.onSurface,
                                                            fontSize = 12.sp
                                                        )
                                                        Text(
                                                            text = labels.second,
                                                            style = typography.bodySm,
                                                            color = colors.onSurfaceVariant,
                                                            fontSize = 10.sp
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(16.dp))
                                        HorizontalDivider(
                                            color = colors.outlineVariant.copy(alpha = 0.5f),
                                            thickness = 1.dp
                                        )
                                        Spacer(modifier = Modifier.height(14.dp))

                                        // Encoding Format Row
                                        Text(
                                            text = UiText.get(R.string.waex_ui_encoding_format, "Encoding Format"),
                                            style = typography.bodyMd,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.onSurface,
                                            fontSize = 13.sp
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            listOf(
                                                "m4a" to UiText.get(R.string.waex_ui_aac_m4a, "AAC (.m4a)"),
                                                "opus" to UiText.get(R.string.waex_ui_opus_opus, "Opus (.opus)")
                                            ).forEach { (formatKey, label) ->
                                                val active = audioFormat == formatKey
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(radius.defaultShape)
                                                        .background(if (active) colors.primary.copy(alpha = 0.12f) else colors.surface)
                                                        .border(
                                                            1.dp,
                                                            if (active) colors.primary else colors.outlineVariant,
                                                            radius.defaultShape
                                                        )
                                                        .clickable {
                                                            audioFormat = formatKey
                                                            prefManager.putString("call_recording_format", formatKey)
                                                        }
                                                        .padding(vertical = 8.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = label,
                                                        style = typography.bodyMd,
                                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (active) colors.primary else colors.onSurface,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // ══════════════════════════════════════════════════════════════
                    // TAB 2: CALL RECORDINGS LIST & PLAYER
                    // ══════════════════════════════════════════════════════════════
                    if (recordingsList.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(colors.primary.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = WaexIcons.Mic,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = UiText.get(R.string.waex_ui_no_call_recordings_yet, "No Call Recordings Yet"),
                                style = typography.headlineMd,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = UiText.get(R.string.waex_ui_when_whatsapp_calls_are_placed_or_rece, "When WhatsApp calls are placed or received with Call Recording enabled, the audio files will appear here automatically."),
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = spacing.pageMargin),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    WaexSearchBar(
                                        query = recordingSearchQuery,
                                        onQueryChange = { recordingSearchQuery = it },
                                        placeholder = "Search by contact name"
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        (listOf("all" to "All") + recordingAppFilters).forEach { (packageName, appName) ->
                                            val selected = recordingAppFilter == packageName
                                            val count = if (packageName == "all") recordingsList.size else recordingsList.count { it.targetPackage == packageName }
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(20.dp))
                                                    .background(if (selected) colors.primary.copy(alpha = 0.14f) else colors.surfaceDim)
                                                    .border(
                                                        1.dp,
                                                        if (selected) colors.primary else colors.outlineVariant,
                                                        RoundedCornerShape(20.dp)
                                                    )
                                                    .clickable { recordingAppFilter = packageName }
                                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "$appName ($count)",
                                                    style = typography.bodySm,
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (selected) colors.primary else colors.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            if (visibleRecordings.isEmpty()) {
                                item {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(WaexIcons.Search, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(28.dp))
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text("No matching recordings", style = typography.bodyLg, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                                        Text("Try another contact name or target app.", style = typography.bodySm, color = colors.onSurfaceVariant)
                                    }
                                }
                            }
                            items(visibleRecordings, key = { it.file.absolutePath }) { item ->
                                val isActive = currentlyPlayingPath == item.file.absolutePath
                                val isExpanded = expandedRecordingPath == item.file.absolutePath
                                val isSelected = item.file.absolutePath in selectedRecordingPaths
                                val appIcon = remember(item.targetPackage) {
                                    runCatching {
                                        context.packageManager.getApplicationIcon(item.targetPackage)
                                            .toBitmap(64, 64).asImageBitmap()
                                    }.getOrNull()
                                }
                                fun togglePlayback() {
                                    if (isActive && isAudioPlaying) {
                                        mediaPlayer?.pause()
                                        isAudioPlaying = false
                                    } else if (isActive) {
                                        mediaPlayer?.start()
                                        isAudioPlaying = true
                                    } else {
                                        stopAudio()
                                        try {
                                            val mp = MediaPlayer().apply {
                                                setDataSource(item.file.absolutePath)
                                                prepare()
                                                setOnCompletionListener { stopAudio() }
                                                start()
                                            }
                                            mediaPlayer = mp
                                            currentlyPlayingPath = item.file.absolutePath
                                            playbackDuration = mp.duration
                                            isAudioPlaying = true
                                        } catch (e: Exception) {
                                            Toast.makeText(
                                                context,
                                                UiText.get(R.string.waex_ui_playback_error_1_s, "Playback error: %1\$s", e.message),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            stopAudio()
                                        }
                                    }
                                }
                                Surface(
                                    shape = radius.bentoCardShape,
                                    color = if (isSelected || isActive) colors.primary.copy(alpha = 0.08f) else colors.surfaceDim,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected || isActive) colors.primary else colors.outlineVariant
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                if (selectedRecordingPaths.isNotEmpty()) toggleRecordingSelection(item)
                                                else expandedRecordingPath = if (isExpanded) null else item.file.absolutePath
                                            },
                                            onLongClick = { toggleRecordingSelection(item) }
                                        )
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Target App Icon with status indicator
                                            if (appIcon != null) {
                                                Box(contentAlignment = Alignment.BottomEnd) {
                                                    Image(
                                                        bitmap = appIcon,
                                                        contentDescription = item.targetPackage,
                                                        modifier = Modifier
                                                            .size(42.dp)
                                                            .clip(RoundedCornerShape(10.dp))
                                                    )
                                                    // Direction badge anchored on icon
                                                    Box(
                                                        modifier = Modifier
                                                            .offset(x = 4.dp, y = 4.dp)
                                                            .size(18.dp)
                                                            .clip(CircleShape)
                                                            .background(colors.surface)
                                                            .border(1.dp, colors.outlineVariant, CircleShape),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        val (dirIcon, dirTint) = when (item.direction) {
                                                            "outgoing" -> Icons.AutoMirrored.Rounded.CallMade to colors.primary
                                                            "incoming" -> Icons.AutoMirrored.Rounded.CallReceived to colors.primary
                                                            else -> Icons.Rounded.Call to colors.onSurfaceVariant
                                                        }
                                                        Icon(
                                                            imageVector = dirIcon,
                                                            contentDescription = item.direction,
                                                            tint = dirTint,
                                                            modifier = Modifier.size(11.dp)
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.width(14.dp))
                                            }

                                            // Center Info Column
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(3.dp)
                                            ) {
                                                // Contact Name (Bold, clean hierarchy)
                                                Text(
                                                    text = item.contactName,
                                                    style = typography.bodyLg,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = colors.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )

                                                // App Name + Call Direction Text
                                                val appTitle = remember(item.targetPackage) {
                                                    runCatching {
                                                        val pm = context.packageManager
                                                        val launchIntent = pm.getLaunchIntentForPackage(item.targetPackage)
                                                        val activityInfo = launchIntent?.resolveActivityInfo(pm, 0)
                                                        activityInfo?.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
                                                            ?: pm.getApplicationLabel(pm.getApplicationInfo(item.targetPackage, 0)).toString()
                                                    }.getOrNull()?.takeIf { it.isNotBlank() } ?: if (item.targetPackage == "com.whatsapp.w4b") "WA Business" else "WhatsApp"
                                                }
                                                val directionTitle = when (item.direction) {
                                                    "outgoing" -> "Outgoing"
                                                    "incoming" -> "Incoming"
                                                    else -> "Call"
                                                }
                                                Text(
                                                    text = "$directionTitle · $appTitle",
                                                    style = typography.bodySm,
                                                    color = colors.onSurfaceVariant,
                                                    fontSize = 12.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )

                                                // Metadata: Date & File Size
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(
                                                        text = item.dateStr,
                                                        style = typography.bodySm,
                                                        color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                                                        fontSize = 11.sp,
                                                        maxLines = 1
                                                    )
                                                    Text(
                                                        text = "·",
                                                        style = typography.bodySm,
                                                        color = colors.outlineVariant
                                                    )
                                                    Text(
                                                        text = item.sizeStr,
                                                        style = typography.bodySm,
                                                        color = colors.onSurfaceVariant,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        maxLines = 1
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.width(8.dp))

                                            // Right Column: Duration + Actions
                                            Column(
                                                horizontalAlignment = Alignment.End,
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                // Call duration in collapsed view
                                                Text(
                                                    text = item.durationStr,
                                                    style = typography.labelSm,
                                                    color = colors.primary,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp
                                                )

                                                // Action Buttons (Delete & Expand)
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                                ) {
                                                    if (isSelected) {
                                                        Icon(
                                                            imageVector = WaexIcons.Check,
                                                            contentDescription = UiText.get(R.string.waex_ui_selected, "Selected"),
                                                            tint = colors.primary,
                                                            modifier = Modifier.size(22.dp)
                                                        )
                                                    } else {
                                                        IconButton(
                                                            onClick = {
                                                                recordingsPendingDelete = listOf(item)
                                                            },
                                                            modifier = Modifier.size(32.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Rounded.Delete,
                                                                contentDescription = UiText.get(R.string.action_delete, "Delete"),
                                                                tint = colors.error.copy(alpha = 0.85f),
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }
                                                    }
                                                    IconButton(
                                                        onClick = {
                                                            expandedRecordingPath = if (isExpanded) null else item.file.absolutePath
                                                        },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                                            contentDescription = if (isExpanded) "Collapse player" else "Expand player",
                                                            tint = colors.onSurfaceVariant,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        // Expandable Audio Player
                                        AnimatedVisibility(
                                            visible = isExpanded,
                                            enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                                            exit = fadeOut(tween(150)) + shrinkVertically(tween(150))
                                        ) {
                                            Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                                                HorizontalDivider(
                                                    color = colors.outlineVariant.copy(alpha = 0.5f),
                                                    thickness = 1.dp
                                                )
                                                Spacer(modifier = Modifier.height(12.dp))
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    IconButton(
                                                        onClick = { togglePlayback() },
                                                        modifier = Modifier
                                                            .size(42.dp)
                                                            .clip(CircleShape)
                                                            .background(if (isActive && isAudioPlaying) colors.primary else colors.primary.copy(alpha = 0.12f))
                                                    ) {
                                                        Icon(
                                                            imageVector = if (isActive && isAudioPlaying) Icons.Rounded.Pause else WaexIcons.Play,
                                                            contentDescription = if (isActive && isAudioPlaying) "Pause" else UiText.get(R.string.waex_ui_play, "Play"),
                                                            tint = if (isActive && isAudioPlaying) Color.White else colors.primary,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Slider(
                                                            value = if (isActive) playbackPosition.toFloat() else 0f,
                                                            onValueChange = { if (isActive) mediaPlayer?.seekTo(it.toInt()) },
                                                            valueRange = 0f..maxOf(1, if (isActive) playbackDuration else 1).toFloat(),
                                                            modifier = Modifier.fillMaxWidth()
                                                        )
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween
                                                        ) {
                                                            Text(
                                                                text = formatPlaybackTime(if (isActive) playbackPosition else 0),
                                                                style = typography.bodySm,
                                                                color = colors.onSurfaceVariant,
                                                                fontSize = 11.sp
                                                            )
                                                            Text(
                                                                text = formatPlaybackTime(if (isActive) playbackDuration else readAudioDuration(item.file)),
                                                                style = typography.bodySm,
                                                                color = colors.onSurfaceVariant,
                                                                fontSize = 11.sp
                                                            )
                                                        }
                                                    }
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
            }
        }
    }

    if (recordingsPendingDelete.isNotEmpty()) {
        val count = recordingsPendingDelete.size
        WaexConfirmationBottomSheet(
            title = if (count == 1) "Delete recording?" else "Delete $count recordings?",
            message = if (count == 1) {
                "This will permanently delete the call recording for ${recordingsPendingDelete.first().contactName}."
            } else {
                "This will permanently delete the selected call recordings."
            },
            confirmLabel = if (count == 1) "Delete recording" else "Delete recordings",
            destructive = true,
            onDismiss = { recordingsPendingDelete = emptyList() },
            onConfirm = { deleteRecordings(recordingsPendingDelete) }
        )
    }
}

private fun readAudioDuration(file: File): Int {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
    } catch (_: Throwable) {
        0
    } finally {
        runCatching { retriever.release() }
    }
}

private fun formatPlaybackTime(milliseconds: Int): String {
    val seconds = (milliseconds.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(Locale.US, seconds / 60, seconds % 60)
}
