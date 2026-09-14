package com.waenhancer.ui.screens.calls

import android.content.Context
import android.media.MediaPlayer
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

    var selectedTab by remember { mutableStateOf("configs") } // "configs" | "recordings"

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
    var callRecordingEnabled by remember { mutableStateOf(prefManager.getBoolean("call_recording_enabled", true)) }
    var useRootStream by remember { mutableStateOf(prefManager.getBoolean("call_recording_use_root", false)) }
    var audioFormat by remember { mutableStateOf(prefManager.getString("call_recording_format", "m4a") ?: "m4a") }

    // Recordings list state
    val recordingsList = remember { mutableStateListOf<RecordedCallItem>() }
    var isLoadingRecordings by remember { mutableStateOf(false) }
    var currentlyPlayingPath by remember { mutableStateOf<String?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    fun stopAudio() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Throwable) {}
        mediaPlayer = null
        currentlyPlayingPath = null
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
            File(context.filesDir, "call_recordings")
        )

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
        val dateFormat = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault())

        for (file in foundFiles) {
            val sizeKb = file.length() / 1024
            val sizeStr = if (sizeKb > 1024) String.format(Locale.getDefault(), "%.1f MB", sizeKb / 1024f) else "$sizeKb KB"
            val dateStr = dateFormat.format(Date(file.lastModified()))

            recordingsList.add(
                RecordedCallItem(
                    file = file,
                    name = file.nameWithoutExtension,
                    dateStr = dateStr,
                    sizeStr = sizeStr,
                    durationStr = "Audio Call"
                )
            )
        }
        isLoadingRecordings = false
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == "recordings") {
            loadRecordings()
        } else {
            stopAudio()
        }
    }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = "Calls & Recording",
                onBackClick = { navController.popBack() }
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
                    listOf(
                        "configs" to "Call Configurations",
                        "recordings" to if (recordingsList.isNotEmpty()) "Recordings (${recordingsList.size})" else "Recordings"
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
                        // ── SECTION 1: CALL PRIVACY & BLOCKER ─────────────────────────
                        Text(
                            text = "CALL PRIVACY & BLOCKER",
                            style = typography.labelSm,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary,
                            letterSpacing = 0.5.sp
                        )

                        Surface(
                            shape = radius.bentoCardShape,
                            color = colors.surfaceDim,
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = "Who Can Call Me",
                                    style = typography.headlineMd,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSurface
                                )
                                Text(
                                    text = "Control which WhatsApp callers are permitted to ring your device",
                                    style = typography.bodyMd,
                                    color = colors.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.height(14.dp))

                                val blockerOptions = listOf(
                                    "0" to ("Everyone (No Block)" to "Allow all WhatsApp incoming voice & video calls"),
                                    "1" to ("Block All Calls" to "Automatically drop every incoming WhatsApp call"),
                                    "2" to ("Unknown Contacts Only" to "Only allow numbers saved in your phone address book")
                                )

                                blockerOptions.forEach { (value, details) ->
                                    val isChosen = callPrivacyMode == value
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .clip(radius.defaultShape)
                                            .background(if (isChosen) colors.primary.copy(alpha = 0.08f) else Color.Transparent)
                                            .border(
                                                1.dp,
                                                if (isChosen) colors.primary else colors.outlineVariant,
                                                radius.defaultShape
                                            )
                                            .clickable {
                                                callPrivacyMode = value
                                                prefManager.putString("call_privacy", value)
                                            }
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .border(2.dp, if (isChosen) colors.primary else colors.outlineVariant, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isChosen) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(10.dp)
                                                        .clip(CircleShape)
                                                        .background(colors.primary)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = details.first,
                                                style = typography.bodyLg,
                                                fontWeight = FontWeight.SemiBold,
                                                color = colors.onSurface
                                            )
                                            Text(
                                                text = details.second,
                                                style = typography.bodyMd,
                                                color = colors.onSurfaceVariant,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // ── SECTION 2: REJECTION ACTION SIMULATION ─────────────────────
                        if (callPrivacyMode != "0") {
                            Surface(
                                shape = radius.bentoCardShape,
                                color = colors.surfaceDim,
                                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(18.dp)) {
                                    Text(
                                        text = "Call Rejection Appearance",
                                        style = typography.headlineMd,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = "Choose what the caller experiences when their call is blocked",
                                        style = typography.bodyMd,
                                        color = colors.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))

                                    val rejectOptions = listOf(
                                        "no_internet" to ("No Internet Connection" to "Caller sees 'Calling...' with no connection ringing tone"),
                                        "busy" to ("User Busy" to "Simulates immediate line busy tone"),
                                        "declined" to ("Call Declined" to "Instantly rejects and shows call declined"),
                                        "uncallable" to ("Not Available" to "Simulates caller not reachable")
                                    )

                                    rejectOptions.forEach { (typeVal, details) ->
                                        val isChosen = callRejectType == typeVal
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                                .clip(radius.defaultShape)
                                                .background(if (isChosen) colors.primary.copy(alpha = 0.08f) else Color.Transparent)
                                                .border(
                                                    1.dp,
                                                    if (isChosen) colors.primary else colors.outlineVariant,
                                                    radius.defaultShape
                                                )
                                                .clickable {
                                                    callRejectType = typeVal
                                                    prefManager.putString("call_type", typeVal)
                                                }
                                                .padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .clip(CircleShape)
                                                    .border(2.dp, if (isChosen) colors.primary else colors.outlineVariant, CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (isChosen) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(10.dp)
                                                            .clip(CircleShape)
                                                            .background(colors.primary)
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(
                                                    text = details.first,
                                                    style = typography.bodyLg,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = colors.onSurface
                                                )
                                                Text(
                                                    text = details.second,
                                                    style = typography.bodyMd,
                                                    color = colors.onSurfaceVariant,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ── SECTION 3: CALL DIAGNOSTICS & INFO ────────────────────────
                        Surface(
                            shape = radius.bentoCardShape,
                            color = colors.surfaceDim,
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Additional Call Information",
                                        style = typography.headlineMd,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = "Display network bitrate, packet loss & audio codec during active calls",
                                        style = typography.bodyMd,
                                        color = colors.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                                StitchSwitch(
                                    checked = callInfoEnabled,
                                    onCheckedChange = {
                                        callInfoEnabled = it
                                        prefManager.putBoolean("call_info", it)
                                    }
                                )
                            }
                        }

                        // ── SECTION 4: CALL RECORDING SUITE ────────────────────────────
                        Text(
                            text = "AUTOMATIC CALL RECORDING",
                            style = typography.labelSm,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary,
                            letterSpacing = 0.5.sp
                        )

                        // Main Recording Switch
                        Surface(
                            shape = radius.bentoCardShape,
                            color = colors.surfaceDim,
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Record WhatsApp Calls",
                                        style = typography.headlineMd,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = "Automatically capture all incoming and outgoing voice conversations",
                                        style = typography.bodyMd,
                                        color = colors.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                                StitchSwitch(
                                    checked = callRecordingEnabled,
                                    onCheckedChange = {
                                        callRecordingEnabled = it
                                        prefManager.putBoolean("call_recording_enabled", it)
                                    }
                                )
                            }
                        }

                        if (callRecordingEnabled) {
                            // Audio Capture Engine Mode
                            Surface(
                                shape = radius.bentoCardShape,
                                color = colors.surfaceDim,
                                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(18.dp)) {
                                    Text(
                                        text = "Audio Capture Engine",
                                        style = typography.headlineMd,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = "Select direct hardware audio pipeline or standard microphone source",
                                        style = typography.bodyMd,
                                        color = colors.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))

                                    // Root Direct Stream
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(radius.defaultShape)
                                            .background(if (useRootStream) colors.primary.copy(alpha = 0.08f) else Color.Transparent)
                                            .border(
                                                1.dp,
                                                if (useRootStream) colors.primary else colors.outlineVariant,
                                                radius.defaultShape
                                            )
                                            .clickable {
                                                useRootStream = true
                                                prefManager.putBoolean("call_recording_use_root", true)
                                            }
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .border(2.dp, if (useRootStream) colors.primary else colors.outlineVariant, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (useRootStream) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(10.dp)
                                                        .clip(CircleShape)
                                                        .background(colors.primary)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = "Direct Audio Pipeline (Recommended)",
                                                style = typography.bodyLg,
                                                fontWeight = FontWeight.SemiBold,
                                                color = colors.onSurface
                                            )
                                            Text(
                                                text = "Captures both incoming caller and outgoing voice in crystal clarity",
                                                style = typography.bodyMd,
                                                color = colors.onSurfaceVariant,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Non-Root Microphone
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(radius.defaultShape)
                                            .background(if (!useRootStream) colors.primary.copy(alpha = 0.08f) else Color.Transparent)
                                            .border(
                                                1.dp,
                                                if (!useRootStream) colors.primary else colors.outlineVariant,
                                                radius.defaultShape
                                            )
                                            .clickable {
                                                useRootStream = false
                                                prefManager.putBoolean("call_recording_use_root", false)
                                            }
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .border(2.dp, if (!useRootStream) colors.primary else colors.outlineVariant, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (!useRootStream) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(10.dp)
                                                        .clip(CircleShape)
                                                        .background(colors.primary)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = "Microphone Standard Capture",
                                                style = typography.bodyLg,
                                                fontWeight = FontWeight.SemiBold,
                                                color = colors.onSurface
                                            )
                                            Text(
                                                text = "Uses standard Android audio record stream",
                                                style = typography.bodyMd,
                                                color = colors.onSurfaceVariant,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                }
                            }

                            // Audio Format Card
                            Surface(
                                shape = radius.bentoCardShape,
                                color = colors.surfaceDim,
                                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(18.dp)) {
                                    Text(
                                        text = "Recording Format & Encoding",
                                        style = typography.headlineMd,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        listOf(
                                            "m4a" to "AAC (.m4a)",
                                            "wav" to "Lossless (.wav)",
                                            "opus" to "Opus (.opus)"
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
                                                    .padding(vertical = 12.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = label,
                                                    style = typography.bodyMd,
                                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (active) colors.primary else colors.onSurface,
                                                    fontSize = 12.sp
                                                )
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
                                text = "No Call Recordings Yet",
                                style = typography.headlineMd,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "When WhatsApp calls are placed or received with Call Recording enabled, the audio files will appear here automatically.",
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
                                Surface(
                                    shape = radius.bentoCardShape,
                                    color = if (isPlaying) colors.primary.copy(alpha = 0.08f) else colors.surfaceDim,
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isPlaying) colors.primary else colors.outlineVariant
                                    ),
                                    modifier = Modifier.fillMaxWidth()
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
                                                        val mp = MediaPlayer().apply {
                                                            setDataSource(item.file.absolutePath)
                                                            prepare()
                                                            setOnCompletionListener {
                                                                stopAudio()
                                                            }
                                                            start()
                                                        }
                                                        mediaPlayer = mp
                                                        currentlyPlayingPath = item.file.absolutePath
                                                    } catch (e: Exception) {
                                                        Toast.makeText(context, "Playback error: ${e.message}", Toast.LENGTH_SHORT).show()
                                                        stopAudio()
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
                                                contentDescription = if (isPlaying) "Stop" else "Play",
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

                                        // Delete Recording Button
                                        IconButton(
                                            onClick = {
                                                if (currentlyPlayingPath == item.file.absolutePath) {
                                                    stopAudio()
                                                }
                                                try {
                                                    item.file.delete()
                                                    recordingsList.remove(item)
                                                    Toast.makeText(context, "Recording deleted", Toast.LENGTH_SHORT).show()
                                                } catch (_: Throwable) {}
                                            },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = WaexIcons.Clear,
                                                contentDescription = "Delete",
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
