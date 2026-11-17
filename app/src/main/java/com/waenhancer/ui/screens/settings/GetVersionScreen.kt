package com.waenhancer.ui.screens.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.waenhancer.app.R
import com.waenhancer.ui.components.WaexConfirmationBottomSheet
import com.waenhancer.ui.components.WaexLinearProgressIndicator
import com.waenhancer.ui.components.WaexSheetPrimaryButton
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.utils.ApkDownload
import com.waenhancer.utils.ApkDownloads
import com.waenhancer.utils.ApkMirrorRelease
import com.waenhancer.utils.DownloadStatus
import com.waenhancer.utils.SupportedVersionSuggester
import com.waenhancer.utils.TargetAppManager
import com.waenhancer.utils.UiText
import com.waenhancer.utils.UniversalVersionValidator

private const val TAG = "GetVersion"

/** The steps of getting a supported build. Install happens on the install screen afterwards. */
private sealed interface Step {
    data object Source : Step
    data object Versions : Step
    data class Release(val release: ApkMirrorRelease) : Step
}

/**
 * Brings the user to a supported WhatsApp build in clear steps:
 * 1. choose where the file comes from (storage, or APKMirror),
 * 2. pick a supported build from the list,
 * 3. open its release page inside WAEX and start the download (kept in module storage),
 * Installing happens in a sheet: from a finished download's card (labelled Upgrade, Downgrade or
 * Reinstall), or for a file picked from storage.
 */
@Composable
fun GetVersionScreen(packageName: String) {
    val context = LocalContext.current
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors

    val detectedApps by TargetAppManager.detectedApps.collectAsState()
    val app = detectedApps.firstOrNull { it.packageName == packageName }
    val appName = app?.appName ?: "WhatsApp"
    // Right after an install the detected list lags behind; the version just installed wins until
    // the detected one catches up, so the cards switch to Reinstall at once.
    var justInstalled by remember { mutableStateOf<String?>(null) }
    val detectedVersion = app?.takeIf { it.isInstalled }?.versionName
    LaunchedEffect(detectedVersion) {
        if (justInstalled != null && detectedVersion?.removePrefix("v") == justInstalled?.removePrefix("v")) {
            justInstalled = null
        }
    }
    val installedVersion = justInstalled ?: detectedVersion
    val rules = remember(context) { UniversalVersionValidator.getBuiltinSupportedVersions(context) }
    val pattern = remember(rules) { SupportedVersionSuggester.newestBuiltinRule(rules).orEmpty() }

    var step by remember { mutableStateOf<Step>(Step.Source) }
    var releases by remember { mutableStateOf<List<ApkMirrorRelease>?>(null) }
    // The downloaded file whose install sheet is open.
    var installTarget by remember { mutableStateOf<ApkDownload?>(null) }
    // The download waiting for the user's confirmation to be removed (or cancelled).
    var removeTarget by remember { mutableStateOf<ApkDownload?>(null) }
    // A file picked from storage, opened in the same install sheet.
    var pickedFile by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pickedFile = uri
    }

    LaunchedEffect(Unit) { ApkDownloads.load(context) }
    val downloads by ApkDownloads.downloads.collectAsState()

    // Release pages handle their own history first; this is the step-level back.
    BackHandler(enabled = step !is Step.Source && step !is Step.Release) { step = Step.Source }

    val title = when (val current = step) {
        Step.Source -> UiText.get(R.string.waex_ui_get_supported_version_title, "Get WhatsApp %1\$s", pattern)
        Step.Versions -> UiText.get(R.string.waex_ui_pick_version_title, "Choose a version")
        is Step.Release -> "$appName ${current.release.version}"
    }

    installTarget?.let { download ->
        ApkInstallSheet(
            packageName = packageName,
            appName = appName,
            version = download.version,
            source = Uri.fromFile(ApkDownloads.fileOf(context, download)),
            installedVersion = installedVersion,
            onInstalled = { justInstalled = it },
            onDismiss = { installTarget = null }
        )
    }
    removeTarget?.let { download ->
        val stopping = download.status == DownloadStatus.DOWNLOADING
        WaexConfirmationBottomSheet(
            title = if (stopping) {
                UiText.get(R.string.waex_ui_cancel_download_title, "Cancel this download?")
            } else {
                UiText.get(R.string.waex_ui_remove_download_title, "Remove this download?")
            },
            message = if (stopping) {
                UiText.get(
                    R.string.waex_ui_cancel_download_message,
                    "The download of %1\$s %2\$s will stop and its partial file will be deleted.",
                    appName, download.version
                )
            } else {
                UiText.get(
                    R.string.waex_ui_remove_download_message,
                    "%1\$s %2\$s will be deleted from WAEX storage. You can download it again later.",
                    appName, download.version
                )
            },
            confirmLabel = if (stopping) {
                UiText.get(R.string.waex_ui_cancel_download_confirm, "Stop download")
            } else {
                UiText.get(R.string.waex_ui_remove_download, "Remove")
            },
            destructive = true,
            onConfirm = {
                ApkDownloads.delete(context, download.id)
                removeTarget = null
            },
            onDismiss = { removeTarget = null }
        )
    }
    pickedFile?.let { uri ->
        ApkInstallSheet(
            packageName = packageName,
            appName = appName,
            version = null,
            source = uri,
            installedVersion = installedVersion,
            onInstalled = { justInstalled = it },
            onDismiss = { pickedFile = null }
        )
    }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = title,
                onBackClick = {
                    when (step) {
                        Step.Source -> navController.popBack()
                        Step.Versions -> step = Step.Source
                        is Step.Release -> step = Step.Versions
                    }
                }
            )
        },
        containerColor = colors.background
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            StepHeader(
                current = when (step) {
                    Step.Source -> 0
                    Step.Versions -> 1
                    is Step.Release -> 2
                }
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (val current = step) {
                    Step.Source -> SourceStep(
                        pattern = pattern,
                        onInstallFromStorage = { picker.launch(arrayOf("*/*")) },
                        onChooseVersion = { step = Step.Versions }
                    )

                    Step.Versions -> VersionsStep(
                        packageName = packageName,
                        appName = appName,
                        cached = releases,
                        downloads = downloads,
                        installedVersion = installedVersion,
                        onLoaded = { releases = it },
                        onPick = { step = Step.Release(it) },
                        onInstall = { installTarget = it },
                        onRemove = { removeTarget = it },
                        onOpenSite = {
                            SupportedVersionSuggester.downloadPageUrl(packageName)?.let { url ->
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        }
                    )

                    is Step.Release -> ReleaseStep(
                        packageName = packageName,
                        release = current.release,
                        onDownloadStarted = { step = Step.Versions },
                        onBack = { step = Step.Versions }
                    )
                }
            }
        }
    }
}

/** "1 Source · 2 Version · 3 Download", with the current step highlighted. */
@Composable
private fun StepHeader(current: Int) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val labels = listOf(
        UiText.get(R.string.waex_ui_step_source, "Source"),
        UiText.get(R.string.waex_ui_step_version, "Version"),
        UiText.get(R.string.waex_ui_step_download, "Download")
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WaexTheme.spacing.pageMargin, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == current
            val done = index < current
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(
                            color = if (active || done) colors.primary else colors.surfaceContainerHighest,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = (index + 1).toString(),
                        style = typography.labelSm,
                        fontWeight = FontWeight.Bold,
                        color = if (active || done) colors.onPrimary else colors.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    style = typography.labelSm,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) colors.onSurface else colors.onSurfaceVariant
                )
            }
        }
    }
}

/** Step 1: where does the file come from? */
@Composable
private fun SourceStep(
    pattern: String,
    onInstallFromStorage: () -> Unit,
    onChooseVersion: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = WaexTheme.spacing.pageMargin, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = UiText.get(
                R.string.waex_ui_get_supported_version_notice,
                "WAEX downloads official WhatsApp builds for you or installs a file you already have, and installs it with root. It strictly checks the signing certificate and installs only official WhatsApp files; any modified or repacked APK is rejected. Any version inside a supported range such as %1\$s works. Installing outside Google Play can put your phone number at risk of suspension, so prefer official updates when you can.",
                pattern
            ),
            style = typography.bodyMd,
            color = colors.onSurfaceVariant
        )

        SourceOption(
            title = UiText.get(R.string.waex_ui_source_choose_title, "Choose a version"),
            description = UiText.get(
                R.string.waex_ui_source_choose_desc,
                "Browse every supported build, download it inside WAEX, then install it."
            ),
            primary = true,
            onClick = onChooseVersion
        )
        SourceOption(
            title = UiText.get(R.string.waex_ui_install_downloaded_apk, "Install from storage"),
            description = UiText.get(
                R.string.waex_ui_source_storage_desc,
                "Already have the file? WAEX verifies it and installs it with root."
            ),
            primary = false,
            onClick = onInstallFromStorage
        )
    }
}

@Composable
private fun SourceOption(title: String, description: String, primary: Boolean, onClick: () -> Unit) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    Surface(
        shape = WaexTheme.radius.mdShape,
        color = colors.surfaceDim,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(description, style = typography.bodyMd, color = colors.onSurfaceVariant)
            if (primary) {
                WaexSheetPrimaryButton(text = title, onClick = onClick)
            } else {
                OutlinedButton(
                    onClick = onClick,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, colors.primary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary),
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                ) {
                    Text(title, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Brings the page to the Download area: the first of these that exists is scrolled into view.
 * It runs again on every page the user opens, because each page of the download path has its own.
 */
private const val SCROLL_TO_DOWNLOAD_SCRIPT = """
(function () {
  // The real download link wins; "scroll to downloads" buttons point at '#downloads' and come later.
  var selectors = ['a.downloadButton[href*="/download/"]', 'a[href*="/download/?key="]',
                   '.variants-table', '#downloads'];
  for (var i = 0; i < selectors.length; i++) {
    var el = document.querySelector(selectors[i]);
    if (el) { el.scrollIntoView({ block: 'center' }); return 'scrolled:' + selectors[i]; }
  }
  return 'none:' + document.title;
})()
"""

/**
 * Step 3: the release page inside WAEX. It scrolls to the Download area on every page; the first
 * file the site starts to download is taken over and saved in module storage under its own id.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ReleaseStep(
    packageName: String,
    release: ApkMirrorRelease,
    onDownloadStarted: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val started by rememberUpdatedState(onDownloadStarted)
    var progress by remember(release.link) { mutableIntStateOf(0) }

    val webView = remember(release.link) {
        WebView(context).also { view ->
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            // Shares its cookies with the feed's WebView, so the human check is not asked again.
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(view, true)
            }

            // One path for every way a file can arrive: the WebView's own download event, or the
            // redirect to the file itself (which is handled here before the WebView would load it).
            fun beginDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
                Log.d(TAG, "download offered url=$url mime=$mimeType cd=$contentDisposition")
                if (ApkDownloads.isAcceptable(url, contentDisposition, mimeType)) {
                    ApkDownloads.start(
                        context = context,
                        packageName = packageName,
                        version = release.version,
                        url = url,
                        userAgent = userAgent ?: view.settings.userAgentString,
                        referer = view.url,
                        contentDisposition = contentDisposition,
                        mimeType = mimeType
                    )
                    Toast.makeText(
                        context,
                        UiText.get(R.string.waex_ui_download_started, "Download started"),
                        Toast.LENGTH_SHORT
                    ).show()
                    started()
                } else {
                    Toast.makeText(
                        context,
                        UiText.get(R.string.waex_ui_download_unsupported, "This file cannot be installed"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            view.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(webView: WebView, newProgress: Int) {
                    progress = newProgress
                }
            }
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    // The download link redirects to the file on storage: take the file here.
                    if (ApkDownloads.isPackageFileUrl(url)) {
                        beginDownload(url, null, null, null)
                        return true
                    }
                    // Ads and redirects to other sites are blocked; only APKMirror pages open here.
                    val host = request.url.host.orEmpty()
                    val blocked = !(host == "apkmirror.com" || host.endsWith(".apkmirror.com"))
                    Log.d(TAG, "navigate $url blocked=$blocked")
                    return blocked
                }

                override fun onPageFinished(webView: WebView, url: String?) {
                    // Pages fill in after loading, so the scroll is repeated shortly after.
                    listOf(0L, 700L, 2_000L).forEach { delayMs ->
                        webView.postDelayed({
                            webView.evaluateJavascript(SCROLL_TO_DOWNLOAD_SCRIPT) { result ->
                                Log.d(TAG, "${webView.url} -> $result")
                            }
                        }, delayMs)
                    }
                }
            }
            view.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                beginDownload(url, userAgent, contentDisposition, mimeType)
            }
            view.loadUrl(release.link)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            CookieManager.getInstance().flush()
            webView.stopLoading()
            webView.destroy()
        }
    }

    // Inside the site, back goes to the previous page first and only then leaves the step.
    BackHandler { if (webView.canGoBack()) webView.goBack() else onBack() }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        if (progress in 1..99) {
            WaexLinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
