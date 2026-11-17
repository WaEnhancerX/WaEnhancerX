package com.waenhancer.ui.screens.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.content.Context
import android.text.format.Formatter
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import com.waenhancer.ui.components.WaexLinearProgressIndicator
import com.waenhancer.ui.components.waexSheetAccent
import com.waenhancer.ui.components.waexSheetOnAccent
import com.waenhancer.utils.ApkDownload
import com.waenhancer.utils.ApkDownloads
import com.waenhancer.utils.DownloadStatus
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import com.waenhancer.app.R
import com.waenhancer.ui.components.TargetAppIcon
import com.waenhancer.ui.components.WaexCircularProgressIndicator
import com.waenhancer.ui.components.WaexSheetPrimaryButton
import com.waenhancer.ui.components.WaexSheetTextButton
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.utils.ApkMirrorFeed
import com.waenhancer.utils.ApkMirrorRelease
import com.waenhancer.utils.SupportedVersionSuggester
import com.waenhancer.utils.UiText
import com.waenhancer.utils.UniversalVersionValidator
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONTokener
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.resume

private const val NO_RESPONSE_GIVE_UP_MS = 20_000L
private const val GIVE_UP_AFTER_MS = 180_000L
private const val POLL_INTERVAL_MS = 400L
private const val EVAL_TIMEOUT_MS = 4_000L
private const val TAG = "ApkMirrorVersions"

/**
 * Runs inside the loaded page. Returns a JSON array of feed XML strings (the feed plus a few
 * further same-origin pages, reusing the cleared session) once the page really is the RSS
 * document, and NOT_FEED while a Cloudflare check or any other page is still showing.
 */
private const val READ_FEED_SCRIPT = """
(function () {
  var root = document.documentElement;
  var xml = null;
  if (root && root.nodeName.toLowerCase() === 'rss') {
    xml = new XMLSerializer().serializeToString(document);
  } else {
    // Chrome's XML viewer replaces the document with an HTML page and keeps the original XML here.
    var source = document.getElementById('webkit-xml-viewer-source-xml');
    if (source) {
      xml = new XMLSerializer().serializeToString(source);
    } else {
      var text = document.body ? (document.body.innerText || '') : '';
      if (text.indexOf('<item') >= 0) xml = text;
    }
  }
  if (!xml || xml.indexOf('<item') < 0) {
    return 'NOT_FEED:' + document.title + '|' + (root ? root.nodeName : '') + '|' +
      (document.body ? (document.body.innerText || '').substring(0, 60).replace(/\s+/g, ' ') : '');
  }
  var pages = [xml];
  try {
    for (var p = 2; p <= 4; p++) {
      var x = new XMLHttpRequest();
      x.open('GET', location.origin + location.pathname + '?paged=' + p, false);
      x.send();
      if (x.status !== 200 || x.responseText.indexOf('<item') < 0) break;
      pages.push(x.responseText);
    }
  } catch (e) {}
  return JSON.stringify(pages);
})()
"""

private sealed interface FeedPhase {
    data object Loading : FeedPhase
    data class Done(val releases: List<ApkMirrorRelease>) : FeedPhase
    data class Failed(val browserUnavailable: Boolean = false) : FeedPhase
}

/**
 * Reads the supported WhatsApp builds from APKMirror's feed and hands them to [onLoaded].
 *
 * The feed is read through a WebView. It loads silently first; if Cloudflare asks for a human
 * check the same WebView grows into view so the user can pass it, and the result is delivered
 * as soon as the feed is readable. Cookies and DOM storage persist, so the check is not repeated
 * every time.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun FeedLoader(packageName: String, onLoaded: (List<ApkMirrorRelease>) -> Unit) {
    val context = LocalContext.current
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val spacing = WaexTheme.spacing

    val rules = remember(context) { UniversalVersionValidator.getBuiltinSupportedVersions(context) }
    val feedUrl = remember(packageName) { ApkMirrorFeed.feedUrl(packageName) }

    var attempt by remember { mutableIntStateOf(0) }
    var phase by remember(attempt) { mutableStateOf<FeedPhase>(FeedPhase.Loading) }
    var needsVerification by remember(attempt) { mutableStateOf(false) }
    var showVerification by remember(attempt) { mutableStateOf(false) }
    var rendererGone by remember(attempt) { mutableStateOf(false) }
    var loadFailed by remember(attempt) { mutableStateOf(false) }
    // Set as soon as the page finishes loading or the site answers with an HTTP error.
    var pageSettled by remember(attempt) { mutableStateOf(false) }
    var errorDetail by remember(attempt) { mutableStateOf<String?>(null) }

    val webView = remember(attempt) {
        WebView(context).also { view ->
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(view, true)
            }
            view.webViewClient = object : WebViewClient() {
                // The verification page may redirect; keep every navigation inside this WebView.
                override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest) = false

                override fun onPageFinished(view: WebView, url: String?) {
                    pageSettled = true
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse
                ) {
                    // Cloudflare answers its check page with 403/503.
                    if (request.isForMainFrame) {
                        Log.d(TAG, "http ${errorResponse.statusCode} for ${request.url}")
                        pageSettled = true
                        // Cloudflare's check page: open the WebView for the user right away.
                        needsVerification = true
                        showVerification = true
                    }
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    // Handled here so a dead renderer never takes the app down with it.
                    Log.w(TAG, "renderer gone (crashed=${detail.didCrash()})")
                    rendererGone = true
                    return true
                }

                override fun onReceivedError(webView: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        errorDetail = "${error.errorCode}: ${error.description}"
                        Log.w(TAG, "main frame error ${request.url} -> $errorDetail")
                        loadFailed = true
                    }
                }
            }
        }
    }
    DisposableEffect(webView) {
        onDispose {
            CookieManager.getInstance().flush()
            webView.stopLoading()
            webView.destroy()
        }
    }

    LaunchedEffect(webView) {
        if (feedUrl == null) {
            phase = FeedPhase.Failed()
            return@LaunchedEffect
        }
        // The headless WebView is the first attempt; it is the same instance the user sees if
        // a check is needed.
        webView.loadUrl(feedUrl)
        val startedAt = SystemClock.elapsedRealtime()
        // True once the page has answered any JavaScript, i.e. the WebView renderer is alive.
        var pageResponded = false
        while (isActive) {
            delay(POLL_INTERVAL_MS)
            when (val read = webView.readFeedPages()) {
                is FeedRead.Pages -> {
                    phase = FeedPhase.Done(
                        ApkMirrorFeed.filterSupported(ApkMirrorFeed.parseAll(read.xml, packageName), rules)
                    )
                    return@LaunchedEffect
                }
                FeedRead.NotFeed -> {
                    pageResponded = true
                    // A finished page that is not the feed XML needs the user's check.
                    if (pageSettled && !needsVerification) {
                        needsVerification = true
                        showVerification = true
                    }
                }
                FeedRead.NoResponse -> Unit
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            // A WebView whose renderer never starts (some emulators and ROMs) cannot be used.
            // Once the site has answered (even with a 403) the WebView is kept for the check.
            val dead = rendererGone || (!pageResponded && !pageSettled && elapsed > NO_RESPONSE_GIVE_UP_MS)
            if (loadFailed || dead || elapsed > GIVE_UP_AFTER_MS) {
                Log.w(TAG, "feed load gave up (loadFailed=$loadFailed, responded=$pageResponded, url=${webView.url})")
                phase = FeedPhase.Failed(browserUnavailable = dead)
                return@LaunchedEffect
            }
        }
    }

    fun open(url: String?) {
        if (url == null || !ApkMirrorFeed.isTrustedLink(url)) return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    run {
        Box(modifier = Modifier.fillMaxSize()) {
            when (val current = phase) {
                FeedPhase.Loading -> {
                    // The one WebView is mounted for the whole load, inside the verification card
                    // below. It is never moved or recreated: the silent attempt and the visible
                    // check are the same instance, so a passed check is reused at once, and its
                    // cookies and DOM storage persist for later visits.
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = spacing.pageMargin, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)
                    ) {
                        WaexCircularProgressIndicator(modifier = Modifier.size(36.dp))
                        Text(
                            text = if (needsVerification) {
                                UiText.get(R.string.waex_ui_verify_human_title, "Verify you are human")
                            } else {
                                UiText.get(R.string.waex_ui_fetching_versions, "Fetching available versions…")
                            },
                            style = typography.bodyMd,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface,
                            textAlign = TextAlign.Center
                        )
                        if (needsVerification) {
                            Text(
                                text = UiText.get(
                                    R.string.waex_ui_verify_human_message,
                                    "APKMirror asks for a quick check. Complete it and the versions will load automatically."
                                ),
                                style = typography.labelSm,
                                color = colors.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            WaexSheetPrimaryButton(
                                text = UiText.get(R.string.waex_ui_verify_now, "Verify now"),
                                onClick = { showVerification = true }
                            )
                        }
                    }

                    val cardAlpha by animateFloatAsState(
                        targetValue = if (showVerification) 1f else 0f,
                        animationSpec = tween(250),
                        label = "waex_verify_alpha"
                    )
                    val cardScale by animateFloatAsState(
                        targetValue = if (showVerification) 1f else 0.92f,
                        animationSpec = tween(250),
                        label = "waex_verify_scale"
                    )
                    if (cardAlpha > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(1f)
                                .background(Color.Black.copy(alpha = 0.4f * cardAlpha))
                                .clickable(enabled = showVerification, indication = null, interactionSource = null) {}
                        )
                    }
                    Surface(
                        shape = WaexTheme.radius.mdShape,
                        color = colors.surface,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth()
                            .height(520.dp)
                            .zIndex(if (showVerification) 2f else -1f)
                            .graphicsLayer {
                                alpha = cardAlpha
                                scaleX = cardScale
                                scaleY = cardScale
                            }
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = UiText.get(R.string.waex_ui_verify_human_title, "Verify you are human"),
                                style = typography.bodyMd,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface
                            )
                            AndroidView(
                                factory = { webView },
                                modifier = Modifier.fillMaxWidth().weight(1f).clip(WaexTheme.radius.smShape)
                            )
                            OutlinedButton(
                                onClick = { showVerification = false },
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, colors.primary),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary),
                                modifier = Modifier.fillMaxWidth().height(40.dp)
                            ) {
                                Text(
                                    text = UiText.get(R.string.waex_ui_verify_close, "Close"),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                is FeedPhase.Done -> LaunchedEffect(current) { onLoaded(current.releases) }

                is FeedPhase.Failed -> Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = spacing.pageMargin),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
                ) {
                    Text(
                        text = UiText.get(R.string.waex_ui_versions_unavailable_title, "Couldn't load versions"),
                        style = typography.bodyMd,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    Text(
                        text = if (current.browserUnavailable) {
                            UiText.get(
                                R.string.waex_ui_browser_unavailable_message,
                                "The in-app browser could not start on this device. Open APKMirror in your browser instead."
                            )
                        } else {
                            UiText.get(
                                R.string.waex_ui_versions_unavailable_message,
                                "The version list could not be loaded. Check your connection and try again, or browse APKMirror directly."
                            )
                        },
                        style = typography.labelSm,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    errorDetail?.let {
                        Text(text = it, style = typography.labelSm, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                    WaexSheetPrimaryButton(
                        text = UiText.get(R.string.waex_ui_try_again, "Try again"),
                        onClick = { attempt++ }
                    )
                    WaexSheetTextButton(
                        text = UiText.get(R.string.waex_ui_open_apkmirror, "Open APKMirror"),
                        onClick = { open(SupportedVersionSuggester.downloadPageUrl(packageName)) }
                    )
                }
            }
        }
    }
}

/**
 * Step 2: the supported builds. Loads the feed once (kept in [cached] by the host so coming back
 * from a release page does not reload it), then lists one card per build. A card shows the
 * download that was started from it: live progress, or an Install button once it is finished.
 */
@Composable
fun VersionsStep(
    packageName: String,
    appName: String,
    cached: List<ApkMirrorRelease>?,
    downloads: List<ApkDownload>,
    installedVersion: String?,
    onLoaded: (List<ApkMirrorRelease>) -> Unit,
    onPick: (ApkMirrorRelease) -> Unit,
    onInstall: (ApkDownload) -> Unit,
    onRemove: (ApkDownload) -> Unit,
    onOpenSite: () -> Unit
) {
    // Files already downloaded are shown at once, whatever the online list is doing.
    val mine = remember(downloads, packageName) {
        downloads.filter { it.packageName == packageName }.sortedByDescending { it.startedAt }
    }
    if (cached == null) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (mine.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = WaexTheme.spacing.pageMargin, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DownloadedHeader()
                    mine.forEach { download ->
                        DownloadedCard(
                            packageName = packageName,
                            appName = appName,
                            download = download,
                            installedVersion = installedVersion,
                            onInstall = onInstall,
                            onRemove = onRemove
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                FeedLoader(packageName = packageName, onLoaded = onLoaded)
            }
        }
    } else {
        VersionList(
            packageName = packageName,
            appName = appName,
            releases = cached,
            downloads = downloads,
            orphans = mine.filter { d -> cached.none { it.version == d.version } },
            installedVersion = installedVersion,
            onPick = onPick,
            onInstall = onInstall,
            onRemove = onRemove,
            onOpenSite = onOpenSite
        )
    }
}

@Composable
private fun DownloadedHeader() {
    Text(
        text = UiText.get(R.string.waex_ui_downloaded_section, "DOWNLOADED"),
        style = WaexTheme.typography.labelSm,
        fontWeight = FontWeight.Bold,
        color = WaexTheme.colors.primary,
        letterSpacing = 1.sp
    )
}

/** A downloaded file shown on its own, e.g. before the online list is available. */
@Composable
private fun DownloadedCard(
    packageName: String,
    appName: String,
    download: ApkDownload,
    installedVersion: String?,
    onInstall: (ApkDownload) -> Unit,
    onRemove: (ApkDownload) -> Unit
) {
    ReleaseCard(
        packageName = packageName,
        appName = appName,
        release = ApkMirrorRelease(download.version, "", download.startedAt),
        download = download,
        installedVersion = installedVersion,
        openable = false,
        onPick = {},
        onInstall = onInstall,
        onRemove = onRemove
    )
}

@Composable
private fun VersionList(
    packageName: String,
    appName: String,
    releases: List<ApkMirrorRelease>,
    downloads: List<ApkDownload>,
    orphans: List<ApkDownload>,
    installedVersion: String?,
    onPick: (ApkMirrorRelease) -> Unit,
    onInstall: (ApkDownload) -> Unit,
    onRemove: (ApkDownload) -> Unit,
    onOpenSite: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val spacing = WaexTheme.spacing

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = spacing.pageMargin, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (orphans.isNotEmpty()) {
            item { DownloadedHeader() }
            items(orphans, key = { "downloaded-${it.id}" }) { download ->
                DownloadedCard(
                    packageName = packageName,
                    appName = appName,
                    download = download,
                    installedVersion = installedVersion,
                    onInstall = onInstall,
                    onRemove = onRemove
                )
            }
        }
        item {
            Text(
                text = if (releases.isEmpty()) {
                    UiText.get(R.string.waex_ui_no_matching_versions, "No supported versions were found in the feed.")
                } else {
                    UiText.get(
                        R.string.waex_ui_pick_version_message,
                        "Every %1\$s build below is supported. Tap one to download it inside WAEX.",
                        appName
                    )
                },
                style = typography.bodyMd,
                color = colors.onSurfaceVariant
            )
        }
        if (releases.isEmpty()) {
            item {
                WaexSheetPrimaryButton(
                    text = UiText.get(R.string.waex_ui_open_apkmirror, "Open APKMirror"),
                    onClick = onOpenSite
                )
            }
        }
        items(releases, key = { it.version }) { release ->
            ReleaseCard(
                packageName = packageName,
                appName = appName,
                release = release,
                download = ApkDownloads.latestFor(downloads, packageName, release.version),
                installedVersion = installedVersion,
                onPick = { onPick(release) },
                onInstall = onInstall,
                onRemove = onRemove
            )
        }
    }
}

@Composable
private fun ReleaseCard(
    packageName: String,
    appName: String,
    release: ApkMirrorRelease,
    download: ApkDownload?,
    installedVersion: String?,
    openable: Boolean = true,
    onPick: () -> Unit,
    onInstall: (ApkDownload) -> Unit,
    onRemove: (ApkDownload) -> Unit
) {
    val context = LocalContext.current
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val busy = download?.status == DownloadStatus.DOWNLOADING

    Surface(
        shape = WaexTheme.radius.smShape,
        color = colors.surfaceDim,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy && openable, onClick = onPick)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The user's own installed app icon, so the list reads as "this app".
                TargetAppIcon(packageName = packageName, size = 36.dp)
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        text = appName,
                        style = typography.bodyMd,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    Text(
                        text = release.version,
                        style = typography.labelSm,
                        color = colors.onSurfaceVariant
                    )
                }
                release.publishedAt?.let { published ->
                    Text(
                        text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(published)),
                        style = typography.labelSm,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            when (download?.status) {
                DownloadStatus.DOWNLOADING -> {
                    val progress = download.progress
                    WaexLinearProgressIndicator(
                        progress = { progress ?: 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = UiText.get(
                                R.string.waex_ui_downloading_progress,
                                "Downloading… %1\$s",
                                downloadLabel(context, download)
                            ),
                            style = typography.labelSm,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onRemove(download) }) {
                            Text(UiText.get(R.string.action_cancel, "Cancel"), fontWeight = FontWeight.Bold)
                        }
                    }
                }

                DownloadStatus.COMPLETED -> {
                    val change = VersionChange.of(installedVersion, release.version)
                    // Compact actions: the card is a list row, not a call to action on its own.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { onRemove(download) },
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, colors.outlineVariant),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(
                                UiText.get(R.string.waex_ui_remove_download, "Remove"),
                                style = typography.labelSm,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = { onInstall(download) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = waexSheetAccent(),
                                contentColor = waexSheetOnAccent()
                            ),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(change.label(), style = typography.labelSm, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                DownloadStatus.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = UiText.get(R.string.waex_ui_download_failed, "Download failed") +
                            (download.error?.let { " ($it)" } ?: ""),
                        style = typography.labelSm,
                        color = colors.error,
                        modifier = Modifier.weight(1f)
                    )
                    if (openable) {
                        TextButton(onClick = onPick) {
                            Text(UiText.get(R.string.waex_ui_download_again, "Download again"), fontWeight = FontWeight.Bold)
                        }
                    } else {
                        TextButton(onClick = { onRemove(download) }) {
                            Text(UiText.get(R.string.waex_ui_remove_download, "Remove"), fontWeight = FontWeight.Bold)
                        }
                    }
                }

                null -> Unit
            }
        }
    }
}

/** How a downloaded build compares to what is installed. */
internal enum class VersionChange {
    INSTALL, UPGRADE, DOWNGRADE, REINSTALL;

    @Composable
    fun label(): String = when (this) {
        INSTALL -> UiText.get(R.string.waex_ui_install_action, "Install")
        UPGRADE -> UiText.get(R.string.waex_ui_install_upgrade, "Upgrade")
        DOWNGRADE -> UiText.get(R.string.waex_ui_install_downgrade, "Downgrade")
        REINSTALL -> UiText.get(R.string.waex_ui_install_reinstall, "Reinstall")
    }

    companion object {
        fun of(installed: String?, target: String): VersionChange {
            if (installed.isNullOrBlank()) return INSTALL
            val order = SupportedVersionSuggester.compareVersions(target, installed.removePrefix("v"))
            return when {
                order > 0 -> UPGRADE
                order < 0 -> DOWNGRADE
                else -> REINSTALL
            }
        }
    }
}

private fun downloadLabel(context: Context, download: ApkDownload): String {
    val done = Formatter.formatShortFileSize(context, download.downloadedBytes)
    val progress = download.progress ?: return done
    val total = Formatter.formatShortFileSize(context, download.totalBytes)
    return "${(progress * 100).toInt()}%  ($done / $total)"
}

private sealed interface FeedRead {
    /** The page did not answer JavaScript in time: its renderer is not running (yet). */
    data object NoResponse : FeedRead
    /** The page is alive but is not the feed, for example a Cloudflare check. */
    data object NotFeed : FeedRead
    data class Pages(val xml: List<String>) : FeedRead
}

/** Runs [READ_FEED_SCRIPT] and reports what the page returned. */
private suspend fun WebView.readFeedPages(): FeedRead {
    val raw = withTimeoutOrNull(EVAL_TIMEOUT_MS) {
        suspendCancellableCoroutine<String?> { continuation ->
            evaluateJavascript(READ_FEED_SCRIPT) { continuation.resume(it) }
        }
    } ?: return FeedRead.NoResponse
    val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return FeedRead.NotFeed
    if (text.startsWith("NOT_FEED")) {
        Log.d(TAG, "not the feed yet: ${text.take(160)}")
        return FeedRead.NotFeed
    }
    val xml = runCatching {
        val array = JSONArray(text)
        List(array.length()) { array.getString(it) }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
    return if (xml != null) FeedRead.Pages(xml) else FeedRead.NotFeed
}
