package com.waenhancer.ui.screens.settings

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import android.content.Intent
import android.net.Uri
import android.widget.TextView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.util.LinkifyCompat
import androidx.core.util.PatternsCompat
import com.waenhancer.app.BuildConfig
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.components.BuildTypeSelectionSheet
import com.waenhancer.ui.components.UpdateDownloadSheet
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import com.waenhancer.ui.navigation.Screen
import com.waenhancer.update.ReleaseRepository
import com.waenhancer.update.ChangelogParser
import com.waenhancer.update.ChangelogSection
import com.waenhancer.update.WaexAsset
import com.waenhancer.update.WaexRelease
import io.noties.markwon.Markwon
import io.noties.markwon.html.HtmlPlugin
import java.text.SimpleDateFormat
import java.util.Locale
import android.content.Context
import androidx.compose.foundation.BorderStroke
import com.waenhancer.update.normalizeVersion
import com.waenhancer.utils.InternalLabels
import java.text.DateFormat

@Composable
fun ChangelogScreen() {
    val navController = LocalWaexNavController.current
    val context = LocalContext.current
    val preferences = LocalWaexPreferenceManager.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    var releases by remember { mutableStateOf<List<WaexRelease>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var buildSelectionRelease by remember { mutableStateOf<WaexRelease?>(null) }
    var activeDownload by remember { mutableStateOf<Pair<WaexRelease, WaexAsset>?>(null) }
    val installedVersion = remember {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }

    LaunchedEffect(reloadKey) {
        loading = true
        error = null
        runCatching { ReleaseRepository.fetchReleases() }
            .onSuccess { releases = it }
            .onFailure { error = it.message ?: UiText.get(R.string.waex_ui_unable_to_load_releases, "Unable to load releases.") }
        loading = false
    }

    val availableUpdate = ReleaseRepository.findUpdate(
        releases,
        installedVersion
    )
    Scaffold(
        topBar = {
            WaexTopBar(
                UiText.get(R.string.waex_ui_changelog_releases, "Changelog & Releases"),
                onBackClick = { navController.popBack() },
                actions = {
                    IconButton(onClick = { navController.navigateTo(Screen.UpdateSettings) }) {
                        Icon(WaexIcons.Settings, contentDescription = UiText.get(R.string.waex_ui_update_settings, "Update settings"), tint = colors.onSurface)
                    }
                }
            )
        },
        containerColor = colors.background
    ) { insets ->
        when {
            loading -> ChangelogSkeleton(
                modifier = Modifier.padding(insets),
                contentPadding = PaddingValues(horizontal = spacing.pageMargin, vertical = 16.dp)
            )
            error != null -> Column(Modifier.fillMaxSize().padding(insets).padding(spacing.pageMargin), Arrangement.Center, Alignment.CenterHorizontally) {
                Text(error!!, color = colors.onSurfaceVariant, style = typography.bodyMd)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { reloadKey++ }) { Text(UiText.get(R.string.waex_ui_try_again, "Try again")) }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(insets),
                contentPadding = PaddingValues(horizontal = spacing.pageMargin, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (releases.isEmpty()) item { Text(UiText.get(R.string.waex_ui_no_releases_found, "No releases found."), color = colors.onSurfaceVariant) }
                items(releases, key = { it.tagName }) { release ->
                    val installed = release.version.equals(normalizeVersion(installedVersion), true)
                    val comparison = ReleaseRepository.versionCode(release.version).compareTo(ReleaseRepository.versionCode(installedVersion))
                    val allowDowngrade = preferences.getBoolean("downgrades_enabled", false)
                    Surface(
                        shape = radius.bentoCardShape,
                        color = colors.surfaceContainerLow,
                        border = BorderStroke(1.dp, colors.outlineVariant),
                        modifier = Modifier.fillMaxWidth().clickable { navController.navigateTo(Screen.ReleaseDetails(release.tagName)) }
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        release.name,
                                        style = typography.headlineMd,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        formatReleaseDate(release.publishedAt),
                                        style = typography.bodyMd,
                                        color = colors.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                                IconButton(onClick = { navController.navigateTo(Screen.ReleaseDetails(release.tagName)) }) {
                                    Icon(
                                        WaexIcons.ChevronRight,
                                        contentDescription = UiText.get(R.string.waex_ui_view_release_details, "View release details"),
                                        tint = colors.onSurfaceVariant
                                    )
                                }
                            }
                            if (installed || release.tagName == availableUpdate?.tagName) {
                                Row(Modifier.padding(top = 8.dp)) {
                                    if (installed) Badge(UiText.get(R.string.waex_ui_installed, "Installed"), colors.success)
                                    if (release.tagName == availableUpdate?.tagName) Badge(UiText.get(R.string.waex_ui_update, "Update"), colors.primary)
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            Surface(
                                shape = radius.bentoCardShape,
                                color = colors.surfaceDim,
                                border = BorderStroke(
                                    1.dp,
                                    colors.outlineVariant.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                ChangelogBodyView(
                                    body = release.body,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                            val debugAsset = release.assets.firstOrNull { it.isDebug }
                            val releaseAsset = release.assets.firstOrNull { !it.isDebug }
                            val hasBothModes = debugAsset != null && releaseAsset != null
                            val canSwitchMode = installed && hasBothModes
                            val otherModeAsset = if (installed) {
                                if (BuildConfig.DEBUG) releaseAsset else debugAsset
                            } else null

                            val showActionButton = (canSwitchMode && otherModeAsset != null) ||
                                ((release.assets.isNotEmpty() || release.downloadUrl != null) && (comparison > 0 || (comparison < 0 && allowDowngrade)))

                            if (showActionButton) {
                                Spacer(Modifier.height(14.dp))
                                Button(
                                    onClick = {
                                        if (canSwitchMode && otherModeAsset != null) {
                                            activeDownload = release to otherModeAsset
                                        } else if (release.assets.size > 1) {
                                            buildSelectionRelease = release
                                        } else {
                                            val asset = release.assets.firstOrNull() ?: release.defaultAsset
                                            if (asset != null) {
                                                activeDownload = release to asset
                                            } else if (release.downloadUrl != null) {
                                                activeDownload = release to WaexAsset(
                                                    name = "WaEnhancerX-${release.tagName}.apk",
                                                    downloadUrl = release.downloadUrl!!,
                                                    size = release.downloadSize
                                                )
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val buttonText = when {
                                        canSwitchMode -> if (BuildConfig.DEBUG) UiText.get(R.string.waex_l10n_switch_release, "Switch to Release") else UiText.get(R.string.waex_l10n_switch_debug, "Switch to Debug")
                                        comparison > 0 -> UiText.get(R.string.waex_ui_upgrade, "Upgrade")
                                        else -> UiText.get(R.string.waex_ui_downgrade, "Downgrade")
                                    }
                                    Text(buttonText)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    buildSelectionRelease?.let { rel ->
        BuildTypeSelectionSheet(
            release = rel,
            onSelectAsset = { asset ->
                buildSelectionRelease = null
                activeDownload = rel to asset
            },
            onDismiss = { buildSelectionRelease = null }
        )
    }

    activeDownload?.let { (rel, asset) ->
        val isDowngrade = ReleaseRepository.versionCode(rel.version) < ReleaseRepository.versionCode(installedVersion)
        UpdateDownloadSheet(
            url = asset.downloadUrl,
            expectedSha256 = asset.sha256,
            expectedSize = asset.size,
            version = rel.version,
            useRoot = isDowngrade || preferences.getBoolean("root_auto_install", false),
            onDismiss = { activeDownload = null }
        )
    }
}

@Composable
private fun Badge(label: String, color: Color) {
    Spacer(Modifier.width(8.dp))
    Box(Modifier.clip(CircleShape).background(color.copy(alpha = .15f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ChangelogBodyView(
    body: String,
    modifier: Modifier = Modifier,
    maxItemsPerCategory: Int = Int.MAX_VALUE,
    initiallyExpanded: Boolean = false
) {
    val sections = remember(body) { ChangelogParser.parse(body) }
    val expandedStates = remember(sections, initiallyExpanded) {
        mutableStateMapOf<String, Boolean>().apply {
            sections.forEach { this[it.title] = initiallyExpanded }
        }
    }
    val colors = WaexTheme.colors

    if (sections.isEmpty()) {
        MarkdownText(
            body.ifBlank { UiText.get(R.string.waex_ui_no_changelog_available, "No changelog available.") },
            colors.onSurfaceVariant.toArgb(),
            maxLines = 8,
            modifier = modifier.padding(vertical = 8.dp)
        )
    } else {
        Column(modifier = modifier) {
            sections.forEachIndexed { index, section ->
                if (index > 0) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = colors.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                val expanded = expandedStates[section.title] ?: initiallyExpanded
                ChangelogSectionRow(
                    section = section.copy(
                        items = section.items.take(maxItemsPerCategory),
                        itemLevels = section.itemLevels.take(maxItemsPerCategory)
                    ),
                    expanded = expanded,
                    onToggle = { expandedStates[section.title] = !expanded }
                )
            }
        }
    }
}

@Composable
private fun ChangelogSectionRow(
    section: ChangelogSection,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, label = "sectionChevron")

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(InternalLabels.text(section.title), style = typography.titleMd, fontWeight = FontWeight.Bold, color = colors.onSurface)
            Spacer(Modifier.width(6.dp))
            Text(
                "(${section.items.size})",
                style = typography.bodySm,
                color = colors.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Icon(
                WaexIcons.ChevronRight,
                contentDescription = if (expanded) UiText.get(R.string.waex_ui_collapse_1_s, "Collapse %1\$s", InternalLabels.text(section.title)) else UiText.get(R.string.waex_ui_expand_1_s, "Expand %1\$s", InternalLabels.text(section.title)),
                tint = colors.onSurfaceVariant,
                modifier = Modifier.rotate(rotation)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                section.items.forEachIndexed { itemIndex, item ->
                    val level = section.itemLevels.getOrElse(itemIndex) { 0 }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = (level * 18).dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            Modifier.padding(top = 7.dp).size(if (level == 0) 4.dp else 3.dp).background(
                                colors.onSurfaceVariant,
                                CircleShape
                            )
                        )
                        Spacer(Modifier.width(10.dp))
                        MarkdownText(item, colors.onSurface.toArgb(), maxLines = 4)
                    }
                }
            }
        }
    }
}

@Composable
fun changelogShimmerBrush(targetValue: Float = 1000f): Brush {
    val shimmerColors = listOf(
        WaexTheme.colors.surfaceContainerLow.copy(alpha = 0.6f),
        WaexTheme.colors.surfaceContainerHighest.copy(alpha = 0.9f),
        WaexTheme.colors.surfaceContainerLow.copy(alpha = 0.6f)
    )
    val transition = rememberInfiniteTransition(label = "changelogShimmerTransition")
    val translateAnimation = transition.animateFloat(
        initialValue = 0f,
        targetValue = targetValue,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "changelogShimmerTranslate"
    )
    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset.Zero,
        end = Offset(x = translateAnimation.value, y = translateAnimation.value)
    )
}

@Composable
fun ChangelogSkeleton(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp)
) {
    val brush = changelogShimmerBrush()
    val radius = WaexTheme.radius
    val colors = WaexTheme.colors

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(3) { index ->
            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceContainerLow,
                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp)) {
                    // Header row: version name, date, chevron
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .width(if (index == 0) 180.dp else 140.dp)
                                    .height(22.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(brush)
                            )
                            Spacer(Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .width(90.dp)
                                    .height(13.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(brush)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(brush)
                        )
                    }

                    // Optional badge skeleton on first card (e.g. Installed / Update)
                    if (index == 0) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .width(68.dp)
                                    .height(22.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(brush)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Nested container for changelog body
                    Surface(
                        shape = radius.bentoCardShape,
                        color = colors.surfaceDim,
                        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Section header
                            Box(
                                modifier = Modifier
                                    .width(110.dp)
                                    .height(15.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(brush)
                            )
                            // Bullet items
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(brush)
                                )
                                Spacer(Modifier.width(10.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.85f)
                                        .height(13.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(brush)
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(brush)
                                )
                                Spacer(Modifier.width(10.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.65f)
                                        .height(13.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(brush)
                                )
                            }
                        }
                    }

                    // Action button skeleton (Upgrade / Downgrade / Switch)
                    if (index == 0) {
                        Spacer(Modifier.height(16.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(brush)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MarkdownText(
    markdown: String,
    textColor: Int,
    maxLines: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            TextView(context).apply {
                setTextColor(textColor)
                textSize = 14f
                setLineSpacing(0f, 1.18f)
                this.maxLines = maxLines
                tag = Markwon.builder(context).usePlugin(HtmlPlugin.create()).build()
            }
        },
        update = { view ->
            view.setTextColor(textColor)
            view.maxLines = maxLines
            (view.tag as Markwon).setMarkdown(view, markdown.trim())
            LinkifyCompat.addLinks(view, PatternsCompat.WEB_URL, null)
        },
        modifier = modifier.fillMaxWidth()
    )
}

internal fun formatReleaseDate(value: String): String = try {
    val input = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    DateFormat.getDateInstance(DateFormat.MEDIUM, UiText.displayLocale()).format(input.parse(value)!!)
} catch (_: Exception) { value.substringBefore('T') }

internal fun openUrl(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
