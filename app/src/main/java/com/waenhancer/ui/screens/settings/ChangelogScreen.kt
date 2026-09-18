package com.waenhancer.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.util.LinkifyCompat
import androidx.core.util.PatternsCompat
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import com.waenhancer.ui.navigation.Screen
import com.waenhancer.update.ReleaseRepository
import com.waenhancer.update.ReleaseChannel
import com.waenhancer.update.WaexRelease
import io.noties.markwon.Markwon
import io.noties.markwon.html.HtmlPlugin
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun ChangelogScreen() {
    val navController = LocalWaexNavController.current
    val context = LocalContext.current
    val preferences = LocalWaexPreferenceManager.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    var selectedChannel by remember {
        mutableStateOf(if (preferences.getString("release_channel", "stable") == "beta") "Beta" else "Stable")
    }
    var releases by remember { mutableStateOf<List<WaexRelease>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    val installedVersion = remember {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }

    LaunchedEffect(reloadKey) {
        loading = true
        error = null
        runCatching { ReleaseRepository.fetchReleases() }
            .onSuccess { releases = it }
            .onFailure { error = it.message ?: "Unable to load releases." }
        loading = false
    }

    val filtered = releases.filter { (selectedChannel == "Beta") == it.isBeta }
    val availableUpdate = ReleaseRepository.findUpdate(
        filtered,
        installedVersion,
        if (selectedChannel == "Beta") ReleaseChannel.BETA else ReleaseChannel.STABLE
    )
    Scaffold(
        topBar = { WaexTopBar("Changelog & Releases", onBackClick = { navController.popBack() }) },
        containerColor = colors.background
    ) { insets ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.primary) }
            error != null -> Column(Modifier.fillMaxSize().padding(insets).padding(spacing.pageMargin), Arrangement.Center, Alignment.CenterHorizontally) {
                Text(error!!, color = colors.onSurfaceVariant, style = typography.bodyMd)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { reloadKey++ }) { Text("Try again") }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(insets),
                contentPadding = PaddingValues(horizontal = spacing.pageMargin, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Stable", "Beta").forEach { channel ->
                            val active = selectedChannel == channel
                            Box(Modifier.clip(CircleShape).background(if (active) colors.primary else colors.surfaceDim)
                                .border(1.dp, if (active) colors.primary else colors.outlineVariant, CircleShape)
                                .clickable { selectedChannel = channel }.padding(horizontal = 18.dp, vertical = 9.dp)) {
                                Text(channel, style = typography.labelSm, fontWeight = FontWeight.Bold, color = if (active) colors.onPrimary else colors.onSurface)
                            }
                        }
                    }
                }
                if (filtered.isEmpty()) item { Text("No $selectedChannel releases found.", color = colors.onSurfaceVariant) }
                items(filtered, key = { it.tagName }) { release ->
                    val installed = release.version.equals(com.waenhancer.update.normalizeVersion(installedVersion), true)
                    Surface(
                        shape = radius.bentoCardShape,
                        color = colors.surfaceContainerLow,
                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                        modifier = Modifier.fillMaxWidth().clickable { navController.navigateTo(Screen.ReleaseDetails(release.tagName)) }
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(release.tagName, style = typography.headlineMd, fontWeight = FontWeight.Bold, color = colors.onSurface)
                                if (installed) Badge("Installed", Color(0xFF10B981))
                                if (release.tagName == availableUpdate?.tagName) Badge("Update", colors.primary)
                                Spacer(Modifier.weight(1f))
                                Text(formatReleaseDate(release.publishedAt), style = typography.bodyMd, color = colors.onSurfaceVariant, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(12.dp))
                            MarkdownText(release.body, colors.onSurfaceVariant.toArgb(), maxLines = 8)
                        }
                    }
                }
            }
        }
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
internal fun MarkdownText(markdown: String, textColor: Int, maxLines: Int = Int.MAX_VALUE) {
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
        modifier = Modifier.fillMaxWidth()
    )
}

internal fun formatReleaseDate(value: String): String = try {
    val input = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    SimpleDateFormat("MMM dd, yyyy", Locale.US).format(input.parse(value)!!)
} catch (_: Exception) { value.substringBefore('T') }

internal fun openUrl(context: android.content.Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
