package com.waenhancer.ui.screens.conversation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.app.R
import com.waenhancer.core.emojis.CustomEmojiManager
import com.waenhancer.core.emojis.CustomEmojiPack
import com.waenhancer.ui.components.WaexCircularProgressIndicator
import com.waenhancer.ui.components.WaexConfirmationBottomSheet
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import com.waenhancer.utils.UiText
import com.waenhancer.xposed.utils.AppRestartHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// ─── Lightweight in-process bitmap cache for emoji previews ───────────────────
private object EmojiPreviewCache {
    private val cache = mutableMapOf<String, Bitmap>()

    fun get(url: String): Bitmap? = cache[url]

    suspend fun load(url: String): Bitmap? {
        cache[url]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.setRequestProperty("User-Agent", "WAEX-Android")
                conn.doInput = true
                conn.connect()
                if (conn.responseCode !in 200..299) return@runCatching null
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?.also { bm -> cache[url] = bm }
            }.getOrNull()
        }
    }
}

@Composable
fun emojiShimmerBrush(targetValue: Float = 1000f): Brush {
    val shimmerColors = listOf(
        WaexTheme.colors.surfaceContainerHighest.copy(alpha = 0.5f),
        WaexTheme.colors.surfaceContainerLow.copy(alpha = 0.95f),
        WaexTheme.colors.surfaceContainerHighest.copy(alpha = 0.5f)
    )
    val transition = rememberInfiniteTransition(label = "emojiShimmerTransition")
    val translateAnimation = transition.animateFloat(
        initialValue = 0f,
        targetValue = targetValue,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "emojiShimmerTranslate"
    )
    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset.Zero,
        end = Offset(x = translateAnimation.value, y = translateAnimation.value)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomEmojisScreen() {
    val context = LocalContext.current
    val navController = LocalWaexNavController.current
    val preferenceManager = LocalWaexPreferenceManager.current
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val spacing = WaexTheme.spacing
    val scope = rememberCoroutineScope()

    val targetPackage = preferenceManager.targetPackage ?: "com.whatsapp"
    val targetAppName = if (targetPackage == "com.whatsapp.w4b") "WA Business" else "WhatsApp"

    // ── Preference state ────────────────────────────────────────────────────
    var selectedEmojiFont by remember(preferenceManager) {
        mutableStateOf(preferenceManager.getString("custom_emoji_font", "") ?: "")
    }
    var customEmojiEnabled by remember(preferenceManager) {
        mutableStateOf(
            preferenceManager.getBoolean("custom_emojis", false) ||
                    preferenceManager.getBoolean("custom_emoji_enabled", false)
        )
    }
    var deviceEmojisEnabled by remember(preferenceManager) {
        mutableStateOf(preferenceManager.getBoolean("disable_defemojis", false))
    }

    // ── Cloud list state ────────────────────────────────────────────────────
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var emojiPacks by remember { mutableStateOf<List<CustomEmojiPack>>(emptyList()) }

    // ── Pull-to-refresh state ───────────────────────────────────────────────
    val pullToRefreshState = rememberPullToRefreshState()

    // ── Download state ──────────────────────────────────────────────────────
    val downloadProgress = remember { mutableStateMapOf<String, Int>() }
    val isDownloading = remember { mutableStateMapOf<String, Boolean>() }
    var localFilesRevision by remember { mutableIntStateOf(0) }

    // ── UI state ─────────────────────────────────────────────────────────────
    var hasChangedStyle by remember { mutableStateOf(false) }
    var packToDelete by remember { mutableStateOf<CustomEmojiPack?>(null) }
    var showClearAllDialog by remember { mutableStateOf(false) }

    val totalDownloadedBytes = remember(localFilesRevision) {
        CustomEmojiManager.getTotalDownloadedBytes(context)
    }

    // ── Helper functions ─────────────────────────────────────────────────────
    fun applyWhatsAppDefault() {
        if (!deviceEmojisEnabled && !customEmojiEnabled) return
        deviceEmojisEnabled = false; customEmojiEnabled = false; selectedEmojiFont = ""
        preferenceManager.putBoolean("disable_defemojis", false)
        preferenceManager.putBoolean("custom_emojis", false)
        preferenceManager.putBoolean("custom_emoji_enabled", false)
        preferenceManager.putString("custom_emoji_font", "")
        hasChangedStyle = true
    }

    fun applyDeviceDefault() {
        if (deviceEmojisEnabled) return
        deviceEmojisEnabled = true; customEmojiEnabled = false; selectedEmojiFont = ""
        preferenceManager.putBoolean("disable_defemojis", true)
        preferenceManager.putBoolean("custom_emojis", false)
        preferenceManager.putBoolean("custom_emoji_enabled", false)
        preferenceManager.putString("custom_emoji_font", "")
        hasChangedStyle = true
    }

    fun applyCustomEmoji(filename: String) {
        if (!deviceEmojisEnabled && customEmojiEnabled && selectedEmojiFont == filename) return
        deviceEmojisEnabled = false; customEmojiEnabled = true; selectedEmojiFont = filename
        preferenceManager.putBoolean("disable_defemojis", false)
        preferenceManager.putBoolean("custom_emojis", true)
        preferenceManager.putBoolean("custom_emoji_enabled", true)
        preferenceManager.putString("custom_emoji_font", filename)
        hasChangedStyle = true
    }

    fun downloadAndApply(pack: CustomEmojiPack) {
        if (pack.downloadUrl.isBlank()) {
            Toast.makeText(context, "Download unavailable offline", Toast.LENGTH_SHORT).show()
            return
        }
        isDownloading[pack.filename] = true
        downloadProgress[pack.filename] = 0
        scope.launch {
            val result = CustomEmojiManager.downloadEmoji(context, pack) { p ->
                downloadProgress[pack.filename] = p
            }
            isDownloading[pack.filename] = false
            result.onSuccess {
                localFilesRevision++
                applyCustomEmoji(pack.filename)
            }.onFailure {
                Toast.makeText(context, UiText.get(R.string.custom_emojis_download_error, "Download failed. Try again."), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun refreshList(fromPull: Boolean = false) {
        if (fromPull) {
            isRefreshing = true
        } else {
            isLoading = true
        }
        errorMessage = null
        scope.launch {
            val result = CustomEmojiManager.fetchCloudEmojis()
            result.onSuccess { list ->
                emojiPacks = list
                isLoading = false
                isRefreshing = false
            }.onFailure { err ->
                val localFiles = CustomEmojiManager.getDownloadedFiles(context)
                if (localFiles.isNotEmpty()) {
                    emojiPacks = localFiles.map { f -> CustomEmojiPack(f.name, f.length(), "") }
                }
                errorMessage = err.localizedMessage ?: UiText.get(R.string.custom_emojis_fetch_error, "Failed to load. Check connection.")
                isLoading = false
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(Unit) { refreshList(fromPull = false) }

    // ── Determine active mode ─────────────────────────────────────────────────
    val activeMode: ActiveEmojiMode = when {
        deviceEmojisEnabled -> ActiveEmojiMode.DeviceDefault
        customEmojiEnabled && selectedEmojiFont.isNotBlank() -> ActiveEmojiMode.Custom(selectedEmojiFont.removeSuffix(".ttf"))
        else -> ActiveEmojiMode.WhatsAppDefault
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCAFFOLD
    // ─────────────────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            WaexTopBar(
                title = UiText.get(R.string.custom_emojis_screen_title, "Emoji Style"),
                onBackClick = { navController.popBack() },
                actions = {
                    if (totalDownloadedBytes > 0) {
                        IconButton(onClick = { showClearAllDialog = true }) {
                            Icon(
                                imageVector = WaexIcons.Delete,
                                contentDescription = "Clear All",
                                tint = colors.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        },
        containerColor = colors.background
    ) { insets ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { refreshList(fromPull = true) },
            state = pullToRefreshState,
            indicator = {
                if (isRefreshing || pullToRefreshState.distanceFraction > 0f) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                            .size(52.dp)
                            .shadow(6.dp, RoundedCornerShape(18.dp)),
                        shape = RoundedCornerShape(18.dp),
                        color = colors.primaryContainer,
                        contentColor = colors.primary
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isRefreshing) {
                                LoadingIndicator(
                                    modifier = Modifier.size(30.dp),
                                    color = colors.primary
                                )
                            } else {
                                Icon(
                                    imageVector = WaexIcons.Refresh,
                                    contentDescription = UiText.get(R.string.waex_ui_refresh, "Refresh"),
                                    modifier = Modifier
                                        .size(22.dp)
                                        .graphicsLayer {
                                            rotationZ = 180f * pullToRefreshState.distanceFraction.coerceIn(0f, 1f)
                                        }
                                )
                            }
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.pageMargin, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {

                // ─── Meta row: target app + storage used ──────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(shape = radius.fullShape, color = colors.primary.copy(alpha = 0.1f)) {
                        Text(
                            text = targetAppName,
                            style = typography.labelSm,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    if (totalDownloadedBytes > 0) {
                        Text(
                            text = CustomEmojiManager.formatFileSize(totalDownloadedBytes) + " downloaded",
                            style = typography.labelSm,
                            color = colors.onSurfaceVariant
                        )
                    }
                }

                // ─── SECTION: BUILT-IN ────────────────────────────────────────
                EmojiSection(title = "BUILT-IN") {
                    Surface(
                        shape = radius.cardShape,
                        color = colors.surfaceContainerLow,
                        border = BorderStroke(1.dp, colors.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            // WhatsApp Default
                            val isDefaultActive = activeMode is ActiveEmojiMode.WhatsAppDefault
                            EmojiBuiltinRow(
                                icon = WaexIcons.Mood,
                                title = UiText.get(R.string.custom_emojis_default_title, "WhatsApp Default"),
                                subtitle = "Original built-in emojis",
                                isSelected = isDefaultActive,
                                onClick = { applyWhatsAppDefault() }
                            )

                            EmojiDivider()

                            // Device System
                            val isDeviceActive = activeMode is ActiveEmojiMode.DeviceDefault
                            EmojiBuiltinRow(
                                icon = WaexIcons.Smartphone,
                                title = UiText.get(R.string.custom_emojis_device_title, "Device Emojis"),
                                subtitle = "Use your phone's system emoji font",
                                isSelected = isDeviceActive,
                                onClick = { applyDeviceDefault() }
                            )
                        }
                    }
                }

                // ─── SECTION: CLOUD STYLES ────────────────────────────────────
                EmojiSection(title = "CLOUD STYLES") {
                    when {
                        isLoading -> CloudSkeletonList()

                        errorMessage != null && emojiPacks.isEmpty() -> CloudErrorCard(
                            message = errorMessage ?: "Failed to load styles.",
                            onRetry = { refreshList(fromPull = false) }
                        )

                        else -> {
                            Surface(
                                shape = radius.cardShape,
                                color = colors.surfaceContainerLow,
                                border = BorderStroke(1.dp, colors.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column {
                                    emojiPacks.forEachIndexed { index, pack ->
                                        val isDownloaded = remember(pack.filename, localFilesRevision) {
                                            CustomEmojiManager.isDownloaded(context, pack.filename)
                                        }
                                        val isSelected = activeMode is ActiveEmojiMode.Custom &&
                                                activeMode.name.equals(pack.displayName, ignoreCase = true)
                                        val downloading = isDownloading[pack.filename] == true
                                        val progress = downloadProgress[pack.filename] ?: 0

                                        EmojiCloudRow(
                                            pack = pack,
                                            isSelected = isSelected,
                                            isDownloaded = isDownloaded,
                                            downloading = downloading,
                                            progress = progress,
                                            onApply = {
                                                if (isDownloaded) applyCustomEmoji(pack.filename)
                                                else if (!downloading) downloadAndApply(pack)
                                            },
                                            onDelete = { packToDelete = pack }
                                        )

                                        if (index < emojiPacks.lastIndex) EmojiDivider()
                                    }
                                }
                            }
                        }
                    }
                }

                // ─── Restart notice ───────────────────────────────────────────
                AnimatedVisibility(
                    visible = hasChangedStyle,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    RestartBanner(
                        appName = targetAppName,
                        targetPackage = targetPackage
                    )
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // ─── Confirmation Bottom Sheets ──────────────────────────────────────────
    packToDelete?.let { pack ->
        WaexConfirmationBottomSheet(
            title = "Remove ${pack.displayName}?",
            message = "Delete the downloaded emoji font (${pack.formattedSize})?",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = {
                val isCurrent = selectedEmojiFont == pack.filename
                CustomEmojiManager.deleteDownloadedEmoji(context, pack.filename)
                if (isCurrent) applyWhatsAppDefault()
                localFilesRevision++
                packToDelete = null
            },
            onDismiss = { packToDelete = null }
        )
    }

    if (showClearAllDialog) {
        WaexConfirmationBottomSheet(
            title = "Clear All Downloads?",
            message = "Remove all saved emoji fonts (${CustomEmojiManager.formatFileSize(totalDownloadedBytes)})?",
            confirmLabel = "Clear All",
            destructive = true,
            onConfirm = {
                CustomEmojiManager.clearAllDownloaded(context)
                if (customEmojiEnabled) applyWhatsAppDefault()
                localFilesRevision++
                showClearAllDialog = false
            },
            onDismiss = { showClearAllDialog = false }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SECTION HEADER
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun EmojiSection(title: String, content: @Composable () -> Unit) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = typography.labelSm,
            fontWeight = FontWeight.Bold,
            color = colors.primary,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 4.dp)
        )
        content()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BUILT-IN ROW (WhatsApp Default / Device Emojis)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun EmojiBuiltinRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(
                    if (isSelected) colors.primary.copy(alpha = 0.12f)
                    else colors.surfaceContainerHighest,
                    radius.mdShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = typography.bodyMd,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isSelected) colors.primary else colors.onSurface
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = subtitle,
                style = typography.bodySm,
                color = colors.onSurfaceVariant
            )
        }

        if (isSelected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = WaexIcons.Check,
                contentDescription = "Active",
                tint = colors.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CLOUD EMOJI ROW (with preview image & shimmer while loading preview)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun EmojiCloudRow(
    pack: CustomEmojiPack,
    isSelected: Boolean,
    isDownloaded: Boolean,
    downloading: Boolean,
    progress: Int,
    onApply: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    // Load preview bitmap
    var previewBitmap by remember(pack.filename) {
        mutableStateOf(pack.previewUrl?.let { EmojiPreviewCache.get(it) })
    }
    LaunchedEffect(pack.previewUrl) {
        val url = pack.previewUrl ?: return@LaunchedEffect
        if (previewBitmap == null) {
            previewBitmap = EmojiPreviewCache.load(url)
        }
    }

    val badgeLabel = remember(pack.filename) {
        when {
            pack.filename.startsWith("Apple", ignoreCase = true)   -> "iOS"
            pack.filename.startsWith("Twitter", ignoreCase = true)  -> "X"
            pack.filename.startsWith("Facebook", ignoreCase = true) -> "FB"
            pack.filename.startsWith("Samsung", ignoreCase = true)  -> "SAM"
            pack.filename.startsWith("Windows", ignoreCase = true)  -> "WIN"
            pack.filename.startsWith("Android", ignoreCase = true)  -> "PIE"
            pack.filename.startsWith("Pixel", ignoreCase = true)    -> "PIX"
            pack.filename.startsWith("LG", ignoreCase = true)       -> "LG"
            pack.filename.startsWith("Phantom", ignoreCase = true)  -> "PH"
            else -> pack.displayName.take(3).uppercase()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !downloading, onClick = onApply)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Top row: badge + name + action ──────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Badge
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(
                        if (isSelected) colors.primary.copy(alpha = 0.12f)
                        else colors.surfaceContainerHighest,
                        radius.mdShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badgeLabel,
                    style = typography.labelSm,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) colors.primary else colors.onSurfaceVariant
                )
            }

            Spacer(Modifier.width(12.dp))

            // Name + status
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pack.displayName,
                    style = typography.bodyMd,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isSelected) colors.primary else colors.onSurface
                )
                Spacer(Modifier.height(1.dp))
                val statusText = when {
                    downloading   -> "Downloading $progress%"
                    isSelected    -> "Active · ${pack.formattedSize}"
                    isDownloaded  -> "Ready · ${pack.formattedSize}"
                    else          -> pack.formattedSize
                }
                Text(
                    text = statusText,
                    style = typography.bodySm,
                    color = if (isSelected) colors.primary.copy(alpha = 0.75f) else colors.onSurfaceVariant
                )
            }

            Spacer(Modifier.width(8.dp))

            // Trailing action
            when {
                downloading -> {
                    WaexCircularProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                        color = colors.primary
                    )
                }

                isSelected -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = WaexIcons.Check,
                            contentDescription = "Active",
                            tint = colors.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(2.dp))
                        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = WaexIcons.Delete,
                                contentDescription = "Remove",
                                tint = colors.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                isDownloaded -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = radius.smShape,
                            color = colors.primary.copy(alpha = 0.1f),
                            modifier = Modifier.clickable(onClick = onApply)
                        ) {
                            Text(
                                text = "Apply",
                                style = typography.labelSm,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(Modifier.width(2.dp))
                        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = WaexIcons.Delete,
                                contentDescription = "Remove",
                                tint = colors.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                else -> {
                    IconButton(onClick = onApply, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = WaexIcons.Download,
                            contentDescription = "Download",
                            tint = colors.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // ── Preview image (200×50 PNG with shimmer skeleton while loading) ─
        val bmp = previewBitmap
        if (bmp != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(radius.smShape)
                    .background(colors.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "${pack.displayName} preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(radius.smShape)
                )
            }
        } else {
            val shimmer = emojiShimmerBrush()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(radius.smShape)
                    .background(shimmer)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SKELETON / SHIMMER LOADING LIST
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun CloudSkeletonList() {
    val colors = WaexTheme.colors
    val radius = WaexTheme.radius
    val shimmer = emojiShimmerBrush()

    Surface(
        shape = radius.cardShape,
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            repeat(4) { index ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Badge skeleton
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(radius.mdShape)
                                .background(shimmer)
                        )

                        Spacer(Modifier.width(12.dp))

                        // Title & subtitle skeleton
                        Column(modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.45f)
                                    .height(14.dp)
                                    .clip(radius.smShape)
                                    .background(shimmer)
                            )
                            Spacer(Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.25f)
                                    .height(10.dp)
                                    .clip(radius.smShape)
                                    .background(shimmer)
                            )
                        }

                        // Action button skeleton
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(radius.fullShape)
                                .background(shimmer)
                        )
                    }

                    // Preview bar skeleton
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(radius.smShape)
                            .background(shimmer)
                    )
                }

                if (index < 3) EmojiDivider()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ERROR CARD
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun CloudErrorCard(message: String, onRetry: () -> Unit) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    Surface(
        shape = radius.cardShape,
        color = colors.errorContainer.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, colors.error.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = WaexIcons.Warning,
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = message,
                style = typography.bodySm,
                color = colors.error
            )
            OutlinedButton(onClick = onRetry, shape = radius.buttonShape) {
                Text("Retry")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RESTART BANNER
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun RestartBanner(appName: String, targetPackage: String) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val context = LocalContext.current

    Surface(
        shape = radius.cardShape,
        color = colors.primary.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = "Restart to apply",
                    style = typography.bodyMd,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Force-stop $appName to load your new emoji style.",
                    style = typography.bodySm,
                    color = colors.onSurfaceVariant
                )
            }
            Button(
                onClick = { AppRestartHelper.restartPackage(context, targetPackage, appName, true) },
                shape = radius.buttonShape
            ) {
                Text("Restart")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DIVIDER
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun EmojiDivider() {
    HorizontalDivider(
        thickness = 0.8.dp,
        color = WaexTheme.colors.outlineVariant.copy(alpha = 0.4f),
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// ACTIVE MODE SEALED CLASS
// ─────────────────────────────────────────────────────────────────────────────
private sealed class ActiveEmojiMode {
    object WhatsAppDefault : ActiveEmojiMode()
    object DeviceDefault : ActiveEmojiMode()
    data class Custom(val name: String) : ActiveEmojiMode()
}
