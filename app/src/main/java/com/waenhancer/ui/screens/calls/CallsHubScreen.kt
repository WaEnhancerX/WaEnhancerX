package com.waenhancer.ui.screens.calls

import com.waenhancer.utils.UiText

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.waenhancer.ui.components.RequestRuntimePermissionOnUse
import com.waenhancer.ui.components.StitchSwitch
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val durationStr: String
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
    val lifecycleOwner = LocalLifecycleOwner.current

    var selectedTab by remember { mutableStateOf("configs") } // "configs" | "recordings"
    val legacyRecordingPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var canReadLegacyRecordings by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, legacyRecordingPermission) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var recordingsRefresh by remember { mutableStateOf(0) }
    RequestRuntimePermissionOnUse(
        permission = legacyRecordingPermission,
        enabled = selectedTab == "recordings",
        onResult = { canReadLegacyRecordings = it }
    )
    DisposableEffect(lifecycleOwner, context, legacyRecordingPermission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canReadLegacyRecordings = ContextCompat.checkSelfPermission(context, legacyRecordingPermission) ==
                    PackageManager.PERMISSION_GRANTED
                recordingsRefresh++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Call Blocker & Privacy State
    var callPrivacyMode by remember {
        val raw = try {
            prefManager.getString("call_privacy", "0")
        } catch (_: Throwable) {
            "0"
        }
        mutableStateOf(if (raw == "true") "1" else if (raw == "false") "0" else (raw ?: "0"))
    }
    var callRejectType by remember { mutableStateOf(prefManager.getString("call_type", "no_internet") ?: "no_internet") }
    var callInfoEnabled by remember { mutableStateOf(prefManager.getBoolean("call_info", false)) }

    // Call Recording State
    var callRecordingEnabled by remember { mutableStateOf(prefManager.getBoolean("call_recording_enabled", false)) }
    var preferCallAudio by remember { mutableStateOf(prefManager.getBoolean("call_recording_use_root", false)) }
    var allowMicrophoneFallback by remember {
        mutableStateOf(prefManager.getBoolean("call_recording_allow_mic_fallback", true))
    }
    var audioFormat by remember { mutableStateOf(prefManager.getString("call_recording_format", "m4a") ?: "m4a") }

    // Recordings list state
    val recordingsList = remember { mutableStateListOf<RecordedCallItem>() }
    var isLoadingRecordings by remember { mutableStateOf(false) }
    var currentlyPlayingPath by remember { mutableStateOf<String?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var selectedRecordingPaths by remember { mutableStateOf(emptySet<String>()) }

    fun stopAudio() {
        val player = mediaPlayer
        mediaPlayer = null
        currentlyPlayingPath = null
        try {
            player?.stop()
        } catch (_: RuntimeException) {
            // A failed prepare/start may leave the player in a non-stoppable state.
        } finally {
            try {
                player?.release()
            } catch (_: RuntimeException) {}
        }
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
                clipData = ClipData.newRawUri(UiText.get(R.string.waex_ui_call_recording_bb8d55c9, "Call recording"), uris.first())
            }
            context.startActivity(Intent.createChooser(intent, UiText.get(R.string.waex_ui_share_call_recording_692216ae, "Share call recording")))
            clearRecordingSelection()
        }.onFailure {
            Toast.makeText(context, UiText.get(R.string.waex_ui_couldn_t_share_the_selected_recording_d260dfd9, "Couldn't share the selected recording"), Toast.LENGTH_SHORT).show()
        }
    }

    fun deleteSelectedRecordings() {
        val selected = recordingsList.filter { it.file.absolutePath in selectedRecordingPaths }
        if (currentlyPlayingPath in selectedRecordingPaths) stopAudio()
        val removed = selected.filter { runCatching { it.file.delete() }.getOrDefault(false) }
        recordingsList.removeAll(removed.toSet())
        clearRecordingSelection()
        Toast.makeText(context, UiText.quantity(R.plurals.waex_recordings_deleted, removed.size, "Deleted %d recording(s)", removed.size), Toast.LENGTH_SHORT).show()
    }

    DisposableEffect(Unit) {
        onDispose {
            stopAudio()
        }
    }

    suspend fun loadRecordings() {
        isLoadingRecordings = true
        val includeLegacy = canReadLegacyRecordings
        try {
            val loaded = withContext(Dispatchers.IO) {
                // Own recordings remain available when shared-storage permission is denied.
                // Keep both internal names: the provider fallback and the older recorder.
                val searchDirs = mutableListOf(
                    File(context.filesDir, "CallRecordings"),
                    File(context.filesDir, "call_recordings")
                )
                context.getExternalFilesDir(null)?.let { searchDirs.add(File(it, "CallRecordings")) }
                if (includeLegacy) {
                    // DIRECTORY_RECORDINGS was introduced in API 31; retain the same
                    // legacy folder on older devices without resolving that field.
                    val recordingsDirectory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS)
                    } else {
                        File(Environment.getExternalStorageDirectory(), "Recordings")
                    }
                    searchDirs.add(File(recordingsDirectory, "WAEX_Calls"))
                    searchDirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "WAEX_Calls"))
                    searchDirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "WaEnhancer/Recordings"))
                }
                val foundFiles = mutableListOf<File>()
                val extensions = setOf("m4a", "wav", "opus", "mp3", "aac")
                for (dir in searchDirs.distinctBy { it.absolutePath }) {
                    // A removed volume or revoked legacy permission cannot hide readable own files.
                    try {
                        dir.listFiles()?.forEach { file ->
                            if (file.isFile && file.extension.lowercase(Locale.ROOT) in extensions) {
                                foundFiles.add(file)
                            }
                        }
                    } catch (_: SecurityException) {}
                }
                foundFiles.sortByDescending { it.lastModified() }
                val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, UiText.displayLocale())
                foundFiles.map { file ->
                    val sizeKb = file.length() / 1024
                    RecordedCallItem(
                        file = file,
                        name = file.nameWithoutExtension,
                        dateStr = dateFormat.format(Date(file.lastModified())),
                        sizeStr = if (sizeKb > 1024) String.format(Locale.getDefault(), "%.1f MB", sizeKb / 1024f) else "$sizeKb KB",
                        durationStr = UiText.get(R.string.waex_ui_audio_call_edd60bff, "Audio Call")
                    )
                }
            }
            recordingsList.clear()
            recordingsList.addAll(loaded)
            selectedRecordingPaths = selectedRecordingPaths.intersect(loaded.map { it.file.absolutePath }.toSet())
        } finally {
            isLoadingRecordings = false
        }
    }

    LaunchedEffect(selectedTab, canReadLegacyRecordings, recordingsRefresh) {
        if (selectedTab == "recordings") {
            loadRecordings()
        } else {
            stopAudio()
        }
    }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = if (selectedRecordingPaths.isEmpty()) UiText.get(R.string.waex_ui_calls_recording_6042880c, "Calls & Recording") else UiText.get(R.string.waex_ui_1_s_selected_5f9d5da8, "%1\$s selected", selectedRecordingPaths.size),
                onBackClick = if (selectedRecordingPaths.isEmpty()) {
                    { navController.popBack() }
                } else {
                    { clearRecordingSelection() }
                },
                actions = if (selectedRecordingPaths.isEmpty()) null else {
                    {
                        IconButton(onClick = { shareSelectedRecordings() }) {
                            Icon(WaexIcons.Share, contentDescription = UiText.get(R.string.waex_ui_share_selected_e3040dd9, "Share selected"), tint = colors.onSurface)
                        }
                        IconButton(onClick = { deleteSelectedRecordings() }) {
                            Icon(WaexIcons.Clear, contentDescription = UiText.get(R.string.waex_ui_delete_selected_d2ab5d46, "Delete selected"), tint = colors.error)
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
                                text = UiText.get(R.string.waex_ui_who_can_call_me_14ebef24, "WHO CAN CALL ME"),
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
                                        "0" to (UiText.get(R.string.waex_ui_everyone_no_block_f8505494, "Everyone (No Block)") to UiText.get(R.string.waex_ui_allow_all_whatsapp_incoming_voice_vide_3f8889bd, "Allow all WhatsApp incoming voice & video calls")),
                                        "1" to (UiText.get(R.string.waex_ui_block_all_calls_f74be201, "Block All Calls") to UiText.get(R.string.waex_ui_automatically_drop_every_incoming_what_52316223, "Automatically drop every incoming WhatsApp call")),
                                        "2" to (UiText.get(R.string.waex_ui_unknown_contacts_only_0adb8d96, "Unknown Contacts Only") to UiText.get(R.string.waex_ui_only_allow_numbers_saved_in_your_phone_504e2e53, "Only allow numbers saved in your phone address book"))
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
                                    text = UiText.get(R.string.waex_ui_call_rejection_appearance_4b21bb01, "CALL REJECTION APPEARANCE"),
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
                                            "no_internet" to (UiText.get(R.string.waex_ui_no_internet_connection_13a21216, "No Internet Connection") to UiText.get(R.string.waex_ui_caller_sees_calling_without_connection_78bae289, "Caller sees 'Calling...' without connection tone")),
                                            "busy" to (UiText.get(R.string.waex_ui_user_busy_0ee28857, "User Busy") to UiText.get(R.string.waex_ui_simulates_immediate_line_busy_tone_983e3462, "Simulates immediate line busy tone")),
                                            "declined" to (UiText.get(R.string.waex_ui_call_declined_f695a2f2, "Call Declined") to UiText.get(R.string.waex_ui_instantly_rejects_and_shows_call_decli_f15c55b2, "Instantly rejects and shows call declined")),
                                            "uncallable" to (UiText.get(R.string.waex_ui_not_available_2ab64517, "Not Available") to UiText.get(R.string.waex_ui_simulates_caller_not_reachable_fd5240bd, "Simulates caller not reachable"))
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
                                text = UiText.get(R.string.waex_ui_call_enhancements_2ffe1444, "CALL ENHANCEMENTS"),
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
                                                text = UiText.get(R.string.waex_ui_additional_call_information_c9b77d50, "Additional Call Information"),
                                                style = typography.bodyLg,
                                                fontWeight = FontWeight.SemiBold,
                                                color = colors.onSurface
                                            )
                                            Text(
                                                text = UiText.get(R.string.waex_ui_receive_detailed_notification_with_cal_e55e211d, "Receive detailed notification with caller IP, location, device & version after calls end"),
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
                                                text = UiText.get(R.string.waex_ui_record_whatsapp_calls_edeb6ac1, "Record WhatsApp Calls"),
                                                style = typography.bodyLg,
                                                fontWeight = FontWeight.SemiBold,
                                                color = colors.onSurface
                                            )
                                            Text(
                                                text = UiText.get(R.string.waex_ui_automatically_capture_incoming_outgoin_892737f9, "Automatically capture incoming & outgoing voice calls"),
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
                                    text = UiText.get(R.string.waex_ui_recording_configuration_40230390, "RECORDING CONFIGURATION"),
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
                                            text = UiText.get(R.string.waex_ui_audio_capture_engine_2219d349, "Audio Capture Engine"),
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

                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = stringResource(R.string.call_recording_capture_limitations),
                                            style = typography.bodySm,
                                            color = colors.onSurfaceVariant
                                        )
                                        if (preferCallAudio) {
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = stringResource(R.string.call_recording_allow_mic_fallback_title),
                                                        style = typography.bodyMd,
                                                        color = colors.onSurface
                                                    )
                                                    Text(
                                                        text = stringResource(R.string.call_recording_allow_mic_fallback_description),
                                                        style = typography.bodySm,
                                                        color = colors.onSurfaceVariant
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(10.dp))
                                                StitchSwitch(
                                                    checked = allowMicrophoneFallback,
                                                    onCheckedChange = { enabled ->
                                                        if (prefManager.putBooleans(mapOf("call_recording_allow_mic_fallback" to enabled))) {
                                                            allowMicrophoneFallback = enabled
                                                        } else {
                                                            Toast.makeText(
                                                                context,
                                                                UiText.get(R.string.call_recording_setting_save_failed, "Could not save the recording setting"),
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    }
                                                )
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
                                            text = UiText.get(R.string.waex_ui_encoding_format_414693ab, "Encoding Format"),
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
                                                "m4a" to UiText.get(R.string.waex_ui_aac_m4a_512c810e, "AAC (.m4a)"),
                                                "opus" to UiText.get(R.string.waex_ui_opus_opus_f43733be, "Opus (.opus)")
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
                                text = UiText.get(R.string.waex_ui_no_call_recordings_yet_1a95417c, "No Call Recordings Yet"),
                                style = typography.headlineMd,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = UiText.get(R.string.waex_ui_when_whatsapp_calls_are_placed_or_rece_a10b5a50, "When WhatsApp calls are placed or received with Call Recording enabled, the audio files will appear here automatically."),
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
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            items(recordingsList, key = { it.file.absolutePath }) { item ->
                                val isPlaying = currentlyPlayingPath == item.file.absolutePath
                                val isSelected = item.file.absolutePath in selectedRecordingPaths
                                Surface(
                                    shape = radius.bentoCardShape,
                                    color = if (isSelected || isPlaying) colors.primary.copy(alpha = 0.08f) else colors.surfaceDim,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected || isPlaying) colors.primary else colors.outlineVariant
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                if (selectedRecordingPaths.isNotEmpty()) toggleRecordingSelection(item)
                                            },
                                            onLongClick = { toggleRecordingSelection(item) }
                                        )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Play / Pause Action Button
                                        IconButton(
                                            onClick = {
                                                if (isPlaying) {
                                                    stopAudio()
                                                } else {
                                                    stopAudio()
                                                    try {
                                                        val mp = MediaPlayer()
                                                        mediaPlayer = mp
                                                        mp.setDataSource(item.file.absolutePath)
                                                        mp.prepare()
                                                        mp.setOnCompletionListener {
                                                            if (mediaPlayer === mp) stopAudio()
                                                        }
                                                        mp.start()
                                                        if (mediaPlayer === mp) {
                                                            currentlyPlayingPath = item.file.absolutePath
                                                        }
                                                    } catch (e: Exception) {
                                                        stopAudio()
                                                        Toast.makeText(context, UiText.get(R.string.waex_ui_playback_error_1_s_158f296a, "Playback error: %1\$s", e.message), Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(CircleShape)
                                                .background(if (isPlaying) colors.primary else colors.primary.copy(alpha = 0.12f))
                                        ) {
                                            Icon(
                                                imageVector = if (isPlaying) WaexIcons.Clear else WaexIcons.Play,
                                                contentDescription = if (isPlaying) UiText.get(R.string.waex_ui_stop_cae7d57b, "Stop") else UiText.get(R.string.waex_ui_play_436e6101, "Play"),
                                                tint = if (isPlaying) Color.White else colors.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(14.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.name,
                                                style = typography.bodyLg,
                                                fontWeight = FontWeight.SemiBold,
                                                color = colors.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = item.dateStr,
                                                    style = typography.bodyMd,
                                                    color = colors.onSurfaceVariant,
                                                    fontSize = 11.sp
                                                )
                                                Text(
                                                    text = "•",
                                                    style = typography.bodyMd,
                                                    color = colors.outlineVariant
                                                )
                                                Text(
                                                    text = item.sizeStr,
                                                    style = typography.bodyMd,
                                                    color = colors.primary,
                                                    fontWeight = FontWeight.Medium,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }

                                        if (isSelected) {
                                            Icon(
                                                imageVector = WaexIcons.Check,
                                                contentDescription = UiText.get(R.string.waex_ui_selected_57fd7a0c, "Selected"),
                                                tint = colors.primary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        } else IconButton(
                                            onClick = {
                                                if (currentlyPlayingPath == item.file.absolutePath) {
                                                    stopAudio()
                                                }
                                                val deleted = try {
                                                    item.file.delete()
                                                } catch (_: Exception) {
                                                    false
                                                }
                                                if (deleted) {
                                                    recordingsList.remove(item)
                                                    Toast.makeText(context, UiText.get(R.string.waex_ui_recording_deleted_ded881bb, "Recording deleted"), Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, UiText.get(R.string.call_recording_delete_failed, "Couldn't delete this recording"), Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = WaexIcons.Clear,
                                                contentDescription = UiText.get(R.string.action_delete, "Delete"),
                                                tint = colors.error.copy(alpha = 0.8f),
                                                modifier = Modifier.size(16.dp)
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
            }
        }
    }
}
