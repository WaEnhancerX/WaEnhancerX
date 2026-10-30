package com.waenhancer.ui.components

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import android.widget.Toast
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.waenhancer.update.UpdateDownloadSession
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.update.UpdateDownloader
import okhttp3.Call
import java.io.File
import java.util.Locale

import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.navigationBarsPadding

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateDownloadSheet(
    url: String, version: String, useRoot: Boolean, onDismiss: () -> Unit,
    expectedSha256: String? = null, expectedSize: Long = 0L
) {
    val context = LocalContext.current
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var downloadProgress by remember(context, url, version, useRoot, expectedSha256, expectedSize) { mutableIntStateOf(0) }
    var downloadedBytes by remember(context, url, version, useRoot, expectedSha256, expectedSize) { mutableLongStateOf(0L) }
    var downloadTotalBytes by remember(context, url, version, useRoot, expectedSha256, expectedSize) { mutableLongStateOf(0L) }
    val session = remember(context, url, version, useRoot, expectedSha256, expectedSize) { UpdateDownloadSession() }
    val dismiss by rememberUpdatedState(onDismiss)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(session) {
        val latest = AtomicReference<Triple<Int, Long, Long>?>(null)
        val pendingProgress = AtomicBoolean(false)
        val progressTask = Runnable {
            pendingProgress.set(false)
            val update = latest.getAndSet(null)
            if (session.isActive() && update != null) {
                downloadProgress = update.first
                downloadedBytes = update.second
                downloadTotalBytes = update.third
            }
        }
        val call = UpdateDownloader.downloadApk(context, url, version, object : UpdateDownloader.DownloadCallback {
            override fun onProgress(progress: Int, currentBytes: Long, totalBytes: Long) {
                synchronized(progressTask) {
                    if (!session.isActive()) return
                    latest.set(Triple(progress, currentBytes, totalBytes))
                    if (pendingProgress.compareAndSet(false, true) && !mainHandler.post(progressTask)) {
                        pendingProgress.set(false)
                        latest.set(null)
                    }
                }
            }
            override fun onSuccess(apkFile: File) {
                if (!mainHandler.post {
                    if (!session.claimTerminal()) {
                        if (session.isCancelled()) apkFile.delete()
                        return@post
                    }
                    mainHandler.removeCallbacks(progressTask)
                    try {
                        if (useRoot) UpdateDownloader.installApkWithRoot(context, apkFile, version)
                        else UpdateDownloader.installApk(context, apkFile, version)
                    } finally { dismiss() }
                }) {
                    session.cancel()
                    apkFile.delete()
                }
            }
            override fun onFailure(error: Exception) {
                mainHandler.post {
                    if (!session.claimTerminal()) return@post
                    mainHandler.removeCallbacks(progressTask)
                    dismiss()
                    if (NetworkErrorCenter.isNetworkError(error)) {
                        NetworkErrorCenter.show(error)
                    } else {
                        Toast.makeText(context, UiText.get(R.string.waex_ui_download_failed_1_s, "Download failed: %1\$s", error.message), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }, expectedSha256, expectedSize)
        session.attach { call?.cancel() }
        onDispose {
            session.cancel()
            mainHandler.removeCallbacks(progressTask)
            latest.set(null)
        }
    }

    WaexModalBottomSheet(
        onDismissRequest = {},
        sheetState = sheetState,
        containerColor = colors.surfaceContainer
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(UiText.get(R.string.waex_ui_downloading_update, "Downloading update"), style = typography.headlineMd, fontWeight = FontWeight.Bold, color = colors.onSurface)
            WaexLinearProgressIndicator(
                progress = { downloadProgress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp),
                color = colors.primary,
                trackColor = colors.surfaceContainerHighest
            )
            Text(
                String.format(Locale.US, "%.1f MB / %.1f MB (%d%%)", downloadedBytes / 1048576.0, downloadTotalBytes.coerceAtLeast(0) / 1048576.0, downloadProgress),
                style = typography.bodyMd,
                color = colors.onSurfaceVariant
            )
            OutlinedButton(onClick = { session.cancel(); dismiss() }, modifier = Modifier.fillMaxWidth()) { Text(UiText.get(R.string.action_cancel, "Cancel")) }
        }
    }
}
