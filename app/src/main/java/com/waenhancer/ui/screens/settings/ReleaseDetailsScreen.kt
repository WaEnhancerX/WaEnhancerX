package com.waenhancer.ui.screens.settings

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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.components.UpdateDownloadSheet
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import com.waenhancer.update.ReleaseRepository
import com.waenhancer.update.WaexRelease

@Composable
fun ReleaseDetailsScreen(tagName: String) {
    val nav = LocalWaexNavController.current
    val context = LocalContext.current
    val preferences = LocalWaexPreferenceManager.current
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val spacing = WaexTheme.spacing
    var release by remember(tagName) { mutableStateOf<WaexRelease?>(null) }
    var error by remember(tagName) { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    var downloadUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tagName, retry) {
        error = null
        runCatching { ReleaseRepository.fetchRelease(tagName) }
            .onSuccess { release = it; if (it == null) error = "Release not found." }
            .onFailure { error = it.message ?: "Unable to load release." }
    }
    Scaffold(topBar = { WaexTopBar("Release Details", onBackClick = { nav.popBack() }) }, containerColor = colors.background) { insets ->
        val item = release
        when {
            item != null -> LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(spacing.pageMargin), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Text(item.tagName, style = typography.headlineMd, fontWeight = FontWeight.Bold, color = colors.onSurface)
                    Text(formatReleaseDate(item.publishedAt), style = typography.bodyMd, color = colors.onSurfaceVariant)
                    Spacer(Modifier.height(18.dp))
                    MarkdownText(item.body, colors.onSurface.toArgb())
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        item.downloadUrl?.let { url -> Button(onClick = { downloadUrl = url }, modifier = Modifier.weight(1f)) { Text("Install APK") } }
                        if (item.htmlUrl.isNotBlank()) OutlinedButton(onClick = { openUrl(context, item.htmlUrl) }, modifier = Modifier.weight(1f)) { Text("Release page") }
                    }
                }
            }
            error != null -> Column(Modifier.fillMaxSize().padding(insets).padding(spacing.pageMargin), Arrangement.Center, Alignment.CenterHorizontally) {
                Text(error!!, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { retry++ }) { Text("Try again") }
            }
            else -> Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.primary) }
        }
    }
    downloadUrl?.let { url ->
        UpdateDownloadSheet(
            url = url,
            version = tagName,
            useRoot = preferences.getBoolean("downgrades_enabled", false),
            onDismiss = { downloadUrl = null }
        )
    }
}
