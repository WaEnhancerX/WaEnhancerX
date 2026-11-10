package com.waenhancer.ui.screens.settings

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.components.BuildTypeSelectionSheet
import com.waenhancer.ui.components.UpdateDownloadSheet
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.components.WaexCircularProgressIndicator
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import com.waenhancer.app.BuildConfig
import com.waenhancer.update.ReleaseRepository
import com.waenhancer.update.WaexAsset
import com.waenhancer.update.WaexRelease
import com.waenhancer.update.normalizeVersion

import com.waenhancer.ui.components.WaexEmptyState
import com.waenhancer.ui.components.NetworkErrorCenter
import com.waenhancer.ui.designsystem.WaexIcons

@Composable
fun ReleaseDetailsScreen(tagName: String) {
    val nav = LocalWaexNavController.current
    val context = LocalContext.current
    val preferences = LocalWaexPreferenceManager.current
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val spacing = WaexTheme.spacing
    val initialCached = remember(tagName) {
        ReleaseRepository.getCachedReleases(context).firstOrNull {
            it.tagName.equals(tagName, true) || it.name.equals(tagName, true) ||
                it.version.equals(normalizeVersion(tagName), true)
        }
    }
    var release by remember(tagName) { mutableStateOf<WaexRelease?>(initialCached) }
    var error by remember(tagName) { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    var showBuildSelection by remember { mutableStateOf(false) }
    var activeDownload by remember { mutableStateOf<Pair<WaexRelease, WaexAsset>?>(null) }
    val installedVersion = remember {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    LaunchedEffect(tagName, retry) {
        error = null
        runCatching { ReleaseRepository.fetchRelease(tagName, context) }
            .onSuccess {
                if (it != null) {
                    release = it
                } else if (release == null) {
                    error = UiText.get(R.string.waex_ui_release_not_found, "Release not found.")
                }
            }
            .onFailure { failure ->
                if (release == null) {
                    if (NetworkErrorCenter.isNetworkError(failure)) {
                        NetworkErrorCenter.show(failure) { retry++ }
                    } else {
                        error = failure.message ?: UiText.get(R.string.waex_ui_unable_to_load_release, "Unable to load release.")
                    }
                }
            }
    }
    Scaffold(topBar = { WaexTopBar(UiText.get(R.string.waex_ui_release_details, "Release Details"), onBackClick = { nav.popBack() }) }, containerColor = colors.background) { insets ->
        val item = release
        when {
            item != null -> LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(spacing.pageMargin), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Text(item.name, style = typography.headlineMd, fontWeight = FontWeight.Bold, color = colors.onSurface)
                    Text(
                        UiText.get(R.string.waex_ui_version_with_date, "Version %1\$s  •  %2\$s", item.version, formatReleaseDate(item.publishedAt)),
                        style = typography.bodyMd,
                        color = colors.onSurfaceVariant
                    )
                    Spacer(Modifier.height(18.dp))
                    ChangelogBodyView(item.body, initiallyExpanded = true)
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val isInstalled = item.version.equals(normalizeVersion(installedVersion), true)
                        val debugAsset = item.assets.firstOrNull { it.isDebug }
                        val releaseAsset = item.assets.firstOrNull { !it.isDebug }
                        val hasBothModes = debugAsset != null && releaseAsset != null
                        val canSwitchMode = isInstalled && hasBothModes
                        val otherModeAsset = if (isInstalled) {
                            if (BuildConfig.DEBUG) releaseAsset else debugAsset
                        } else null

                        val isDowngrade = ReleaseRepository.versionCode(item.version) < ReleaseRepository.versionCode(installedVersion)
                        val allowDowngrade = preferences.getBoolean("downgrades_enabled", false)
                        val canInstall = canSwitchMode || (!isDowngrade || allowDowngrade)

                        if (canInstall && (item.assets.isNotEmpty() || item.downloadUrl != null)) {
                            Button(
                                onClick = {
                                    if (canSwitchMode && otherModeAsset != null) {
                                        activeDownload = item to otherModeAsset
                                    } else if (item.assets.size > 1) {
                                        showBuildSelection = true
                                    } else {
                                        val asset = item.assets.firstOrNull() ?: item.defaultAsset
                                        if (asset != null) {
                                            activeDownload = item to asset
                                        } else if (item.downloadUrl != null) {
                                            activeDownload = item to WaexAsset(
                                                name = "WaEnhancerX-${item.tagName}.apk",
                                                downloadUrl = item.downloadUrl!!,
                                                size = item.downloadSize
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                val buttonText = when {
                                    canSwitchMode -> if (BuildConfig.DEBUG) UiText.get(R.string.waex_l10n_switch_release, "Switch to Release") else UiText.get(R.string.waex_l10n_switch_debug, "Switch to Debug")
                                    isInstalled -> UiText.get(R.string.waex_l10n_reinstall_apk, "Reinstall APK")
                                    isDowngrade -> UiText.get(R.string.waex_ui_downgrade, "Downgrade")
                                    else -> UiText.get(R.string.waex_ui_install_apk, "Install APK")
                                }
                                Text(buttonText)
                            }
                        }
                        if (item.htmlUrl.isNotBlank()) OutlinedButton(onClick = { openUrl(context, item.htmlUrl) }, modifier = Modifier.weight(1f)) { Text(UiText.get(R.string.waex_ui_release_page, "Release page")) }
                    }
                }
            }
            error != null -> {
                val isRateLimit = error?.contains("rate limit", ignoreCase = true) == true
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(insets)
                        .padding(spacing.pageMargin),
                    contentAlignment = Alignment.Center
                ) {
                    WaexEmptyState(
                        title = if (isRateLimit) {
                            UiText.get(R.string.waex_ui_github_rate_limited, "GitHub Rate Limit Exceeded")
                        } else {
                            UiText.get(R.string.waex_ui_unable_to_load_release, "Unable to load release.")
                        },
                        description = if (isRateLimit) {
                            UiText.get(R.string.waex_ui_github_rate_limited_desc, "GitHub API limit reached. Pull to refresh or try again in a few minutes.")
                        } else {
                            error.orEmpty()
                        },
                        icon = if (isRateLimit) WaexIcons.Warning else WaexIcons.Info,
                        actionText = UiText.get(R.string.waex_ui_try_again, "Try again"),
                        onActionClick = { retry++ }
                    )
                }
            }
            else -> Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) { WaexCircularProgressIndicator(color = colors.primary) }
        }
    }

    if (showBuildSelection && release != null) {
        BuildTypeSelectionSheet(
            release = release!!,
            onSelectAsset = { asset ->
                showBuildSelection = false
                activeDownload = release!! to asset
            },
            onDismiss = { showBuildSelection = false }
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
