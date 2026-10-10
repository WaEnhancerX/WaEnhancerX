package com.waenhancer.ui.components

import android.widget.Toast
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateDownloadSheet(url: String, version: String, useRoot: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    var downloadProgress by remember(url) { mutableIntStateOf(0) }
    var downloadedBytes by remember(url) { mutableLongStateOf(0L) }
    var downloadTotalBytes by remember(url) { mutableLongStateOf(0L) }
    var call by remember(url) { mutableStateOf<Call?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(url) {
        call = UpdateDownloader.downloadApk(context, url, version, object : UpdateDownloader.DownloadCallback {
            override fun onProgress(progress: Int, currentBytes: Long, totalBytes: Long) {
                mainHandler.post {
                    downloadProgress = progress
                    downloadedBytes = currentBytes
                    downloadTotalBytes = totalBytes
                }
            }
            override fun onSuccess(apkFile: File) {
                mainHandler.post {
                    onDismiss()
                    if (useRoot) UpdateDownloader.installApkWithRoot(context, apkFile) else UpdateDownloader.installApk(context, apkFile)
                }
            }
            override fun onFailure(error: Exception) {
                mainHandler.post {
                    onDismiss()
                    Toast.makeText(context, "Download failed: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        })
        // Dismissal must not leak network activity or mutate a disposed sheet.
        onDispose { call?.cancel() }
    }

    ModalBottomSheet(onDismissRequest = {}, containerColor = colors.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Downloading update", style = typography.headlineMd, fontWeight = FontWeight.Bold, color = colors.onSurface)
            LinearProgressIndicator(
                progress = { downloadProgress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = colors.primary,
                trackColor = colors.surfaceContainerHighest
            )
            Text(
                String.format(Locale.US, "%.1f MB / %.1f MB (%d%%)", downloadedBytes / 1048576.0, downloadTotalBytes.coerceAtLeast(0) / 1048576.0, downloadProgress),
                style = typography.bodyMd,
                color = colors.onSurfaceVariant
            )
            OutlinedButton(onClick = { call?.cancel(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}
