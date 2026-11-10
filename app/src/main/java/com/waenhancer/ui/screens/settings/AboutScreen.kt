package com.waenhancer.ui.screens.settings

import com.waenhancer.utils.UiText
import com.waenhancer.app.BuildConfig

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.waenhancer.ui.components.WaexCircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.waenhancer.ui.components.WaexModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.app.R
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.NumberFormat
import java.util.concurrent.ConcurrentHashMap
import androidx.collection.LruCache
import com.waenhancer.config.PreferenceStores

data class GitHubContributor(
    val login: String,
    val avatarUrl: String,
    val htmlUrl: String,
    val contributions: Int
)

data class GitHubUserProfile(
    val login: String,
    val name: String,
    val avatarUrl: String,
    val htmlUrl: String,
    val bio: String,
    val location: String,
    val followers: Int,
    val twitter: String,
    val blog: String,
    val hireable: Boolean,
    val contributions: Int
)

data class RepoStats(
    val totalCommits: Int,
    val linesAdded: Long,
    val linesDeleted: Long
)

object AvatarCache {
    private val memoryCache = LruCache<String, Bitmap>(150)
    private val loadLocks = ConcurrentHashMap<String, Any>()

    fun get(url: String): Bitmap? = memoryCache.get(url)

    fun put(url: String, bitmap: Bitmap) {
        memoryCache.put(url, bitmap)
    }

    fun load(context: Context, url: String): Bitmap? {
        get(url)?.let { return it }

        val lock = loadLocks.getOrPut(url) { Any() }
        return synchronized(lock) {
            get(url)?.let { return@synchronized it }

            val cacheFile = avatarCacheFile(context, url)
            if (cacheFile.isFile) {
                BitmapFactory.decodeFile(cacheFile.absolutePath)?.let { cached ->
                    put(url, cached)
                    return@synchronized cached
                }
                cacheFile.delete()
            }

            downloadAvatar(url)?.also { (bytes, decoded) ->
                runCatching {
                    cacheFile.parentFile?.mkdirs()
                    val temporaryFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
                    temporaryFile.writeBytes(bytes)
                    if (!temporaryFile.renameTo(cacheFile)) temporaryFile.delete()
                }
                put(url, decoded)
            }?.second
        }
    }

    private fun avatarCacheFile(context: Context, url: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(File(context.cacheDir, "github_avatars"), digest)
    }

    private fun downloadAvatar(url: String): Pair<ByteArray, Bitmap>? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.setRequestProperty("User-Agent", "WaEnhancerX-App")
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.doInput = true
            connection.connect()
            if (connection.responseCode !in 200..299) return null

            val bytes = connection.inputStream.use { it.readBytes() }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            bytes to decoded
        } finally {
            connection.disconnect()
        }
    }
}

@Composable
fun AsyncAvatarImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bitmap by remember(url) { mutableStateOf(AvatarCache.get(url)) }

    LaunchedEffect(url) {
        if (bitmap == null && url.isNotEmpty()) {
            bitmap = withContext(Dispatchers.IO) {
                runCatching { AvatarCache.load(context, url) }.getOrNull()
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier.background(WaexTheme.colors.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_github),
                contentDescription = null,
                tint = WaexTheme.colors.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val spacing = WaexTheme.spacing

    val contributors = remember { mutableStateListOf<GitHubContributor>() }
    var isLoadingContributors by remember { mutableStateOf(true) }

    // BottomSheet states
    var selectedUserForSheet by remember { mutableStateOf<GitHubContributor?>(null) }
    var userProfileData by remember { mutableStateOf<GitHubUserProfile?>(null) }
    var isLoadingProfile by remember { mutableStateOf(false) }

    // Contributions details view state
    var showContributionsView by remember { mutableStateOf(false) }
    var contributionsStats by remember { mutableStateOf<RepoStats?>(null) }
    var isLoadingStats by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Fetch Contributors
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val prefs = PreferenceStores.cacheStore(context, PreferenceStores.GITHUB_API_CACHE)
            val lastFetch = prefs.getLong("last_fetch", 0)
            val cachedJson = prefs.getString("contributors_json", null)

            if (cachedJson != null && (System.currentTimeMillis() - lastFetch < 3600000)) {
                parseContributors(cachedJson, contributors)
                withContext(Dispatchers.Main) { isLoadingContributors = false }
                return@withContext
            }

            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://api.github.com/repos/WaEnhancerX/WaEnhancerX/contributors")
                .header("User-Agent", "WaEnhancerX-App")
                .header(UiText.get(R.string.waex_ui_accept, "Accept"), "application/vnd.github.v3+json")
                .build()

            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful && response.body != null) {
                    val json = response.body!!.string()
                    prefs.edit()
                        .putLong("last_fetch", System.currentTimeMillis())
                        .putString("contributors_json", json)
                        .apply()
                    parseContributors(json, contributors)
                } else if (cachedJson != null) {
                    parseContributors(cachedJson, contributors)
                }
            } catch (e: Exception) {
                if (cachedJson != null) {
                    parseContributors(cachedJson, contributors)
                }
            } finally {
                withContext(Dispatchers.Main) { isLoadingContributors = false }
            }
        }
    }

    // Function to load profile
    fun loadUserProfile(contributor: GitHubContributor) {
        selectedUserForSheet = contributor
        userProfileData = null
        isLoadingProfile = true
        showContributionsView = false
        contributionsStats = null

        coroutineScope.launch(Dispatchers.IO) {
            val prefs = PreferenceStores.cacheStore(context, PreferenceStores.GITHUB_USER_CACHE)
            val lastFetch = prefs.getLong("${contributor.login}_time", 0)
            val cachedJson = prefs.getString("${contributor.login}_json", null)

            if (cachedJson != null && (System.currentTimeMillis() - lastFetch < 3600000)) {
                val profile = parseUserProfile(cachedJson, contributor.htmlUrl, contributor.avatarUrl, contributor.contributions)
                withContext(Dispatchers.Main) {
                    userProfileData = profile
                    isLoadingProfile = false
                }
                return@launch
            }

            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://api.github.com/users/${contributor.login}")
                .header("User-Agent", "WaEnhancerX-App")
                .header(UiText.get(R.string.waex_ui_accept, "Accept"), "application/vnd.github.v3+json")
                .build()

            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful && response.body != null) {
                    val json = response.body!!.string()
                    prefs.edit()
                        .putLong("${contributor.login}_time", System.currentTimeMillis())
                        .putString("${contributor.login}_json", json)
                        .apply()
                    val profile = parseUserProfile(json, contributor.htmlUrl, contributor.avatarUrl, contributor.contributions)
                    withContext(Dispatchers.Main) {
                        userProfileData = profile
                    }
                } else if (cachedJson != null) {
                    val profile = parseUserProfile(cachedJson, contributor.htmlUrl, contributor.avatarUrl, contributor.contributions)
                    withContext(Dispatchers.Main) {
                        userProfileData = profile
                    }
                }
            } catch (e: Exception) {
                if (cachedJson != null) {
                    val profile = parseUserProfile(cachedJson, contributor.htmlUrl, contributor.avatarUrl, contributor.contributions)
                    withContext(Dispatchers.Main) {
                        userProfileData = profile
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isLoadingProfile = false
                }
            }
        }
    }

    // Function to load repo contribution stats
    fun loadContributionStats(login: String) {
        isLoadingStats = true
        showContributionsView = true

        coroutineScope.launch(Dispatchers.IO) {
            val prefs = PreferenceStores.cacheStore(context, PreferenceStores.GITHUB_USER_CACHE)
            val lastFetch = prefs.getLong("repo_stats_time", 0)
            val cachedJson = prefs.getString("repo_stats_json", null)

            if (cachedJson != null && (System.currentTimeMillis() - lastFetch < 3600000)) {
                val stats = parseStats(cachedJson, login)
                withContext(Dispatchers.Main) {
                    contributionsStats = stats
                    isLoadingStats = false
                }
                return@launch
            }

            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://api.github.com/repos/Xposed-Modules-Repo/com.waenhancer/releases/stats/contributors")
                .header("User-Agent", "WaEnhancerX-App")
                .header(UiText.get(R.string.waex_ui_accept, "Accept"), "application/vnd.github.v3+json")
                .build()

            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful && response.body != null && response.code != 202) {
                    val json = response.body!!.string()
                    prefs.edit()
                        .putLong("repo_stats_time", System.currentTimeMillis())
                        .putString("repo_stats_json", json)
                        .apply()
                    val stats = parseStats(json, login)
                    withContext(Dispatchers.Main) {
                        contributionsStats = stats
                    }
                } else if (cachedJson != null) {
                    val stats = parseStats(cachedJson, login)
                    withContext(Dispatchers.Main) {
                        contributionsStats = stats
                    }
                }
            } catch (e: Exception) {
                if (cachedJson != null) {
                    val stats = parseStats(cachedJson, login)
                    withContext(Dispatchers.Main) {
                        contributionsStats = stats
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isLoadingStats = false
                }
            }
        }
    }

    fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, UiText.get(R.string.waex_ui_could_not_open_link, "Could not open link"), Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = stringResource(R.string.about_title),
                onBackClick = { navController.popBack() }
            )
        },
        containerColor = colors.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.pageMargin, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Hallmark · redesign: full · scope: app · genre: editorial · theme: WAEX brand
            // Critique: replace the repeated card stack with an identity masthead and a credits-led reading rhythm.
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = colors.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AndroidView(
                        factory = { imageContext ->
                            ImageView(imageContext).apply {
                                setImageResource(R.mipmap.ic_launcher_round)
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                contentDescription = UiText.get(R.string.waex_ui_app_logo, "App Logo")
                            }
                        },
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = typography.displayLgMobile,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                        Text(
                            text = stringResource(R.string.maintainer),
                            style = typography.bodyMd,
                            color = colors.onSurfaceVariant,
                            lineHeight = 20.sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "v${BuildConfig.VERSION_NAME}",
                                style = typography.labelSm,
                                fontWeight = FontWeight.Medium,
                                color = colors.onSurfaceVariant
                            )
                            Text(
                                text = if (BuildConfig.DEBUG) stringResource(R.string.build_variant_debug) else stringResource(R.string.build_variant_release),
                                style = typography.labelSm,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Card 1: About Description
            Surface(
                color = colors.background,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = stringResource(R.string.about_section_title),
                        style = typography.headlineMd,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.about_info),
                        style = typography.bodyMd,
                        color = colors.onSurfaceVariant,
                        lineHeight = 22.sp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    HorizontalDivider(color = colors.outlineVariant)
                }
            }

            // Card 2: Installation Instructions
            Surface(
                color = colors.background,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = stringResource(R.string.installation),
                        style = typography.headlineMd,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.installation_desc),
                        style = typography.bodyMd,
                        color = colors.onSurfaceVariant,
                        lineHeight = 22.sp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    HorizontalDivider(color = colors.outlineVariant)
                }
            }

            // Card 3: Community
            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceContainerLow,
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.community),
                        style = typography.headlineMd,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    // Telegram button
                    Button(
                        onClick = { openUrl("https://t.me/WaEnhancerX") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = radius.buttonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primary,
                            contentColor = colors.onPrimary
                        )
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_telegram),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = colors.onPrimary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.go_to_telegram),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // GitHub Issues button
                    OutlinedButton(
                        onClick = { openUrl("https://github.com/WaEnhancerX/WaEnhancerX/issues") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = radius.buttonShape,
                        border = BorderStroke(1.dp, colors.outlineVariant),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = colors.onSurface
                        )
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_github),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = colors.onSurface
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.go_to_github),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Card 4: Contributors Grid (with BottomSheet interaction)
            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.contributors),
                        style = typography.headlineMd,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    if (isLoadingContributors) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            repeat(4) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(colors.surfaceContainerHighest)
                                )
                            }
                        }
                    } else if (contributors.isEmpty()) {
                        Text(
                            text = UiText.get(R.string.waex_ui_no_contributors_found, "No contributors found"),
                            style = typography.bodyMd,
                            color = colors.onSurfaceVariant
                        )
                    } else {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            maxItemsInEachRow = 5,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            contributors.forEach { contributor ->
                                Box(
                                    modifier = Modifier
                                        .size(58.dp)
                                        .clip(CircleShape)
                                        .clickable { loadUserProfile(contributor) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box {
                                        AsyncAvatarImage(
                                            url = contributor.avatarUrl,
                                            contentDescription = contributor.login,
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .border(1.dp, colors.outlineVariant, CircleShape)
                                        )
                                        if (contributor.login.equals(BuildConfig.GH_DEV, ignoreCase = true)) {
                                            Surface(
                                                modifier = Modifier.align(Alignment.BottomCenter),
                                                shape = CircleShape,
                                                color = colors.primary,
                                                contentColor = colors.onPrimary
                                            ) {
                                                Text(
                                                    text = "Dev",
                                                    style = typography.labelSm,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Card 5: Support
            Surface(
                color = colors.background,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = stringResource(R.string.support),
                        style = typography.headlineMd,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.support_desc),
                        style = typography.bodyMd,
                        color = colors.onSurfaceVariant,
                        lineHeight = 22.sp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    HorizontalDivider(color = colors.outlineVariant)
                }
            }

            // Card 6: License & Disclaimer
            Surface(
                color = colors.background,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = stringResource(R.string.license),
                        style = typography.headlineMd,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.license_desc),
                        style = typography.bodyMd,
                        color = colors.onSurfaceVariant,
                        lineHeight = 22.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // ModalBottomSheet for Contributor Profile / Contributions Detail
    if (selectedUserForSheet != null) {
        val user = selectedUserForSheet!!

        WaexModalBottomSheet(
            onDismissRequest = { selectedUserForSheet = null },
            sheetState = sheetState,
            containerColor = colors.surface,
            contentColor = colors.onSurface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .padding(bottom = 24.dp)
            ) {
                if (!showContributionsView) {
                    // Profile View
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        AsyncAvatarImage(
                            url = user.avatarUrl,
                            contentDescription = user.login,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = userProfileData?.name?.ifEmpty { user.login } ?: user.login,
                                style = typography.headlineMd,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface
                            )
                            Text(
                                text = "@${user.login}",
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (isLoadingProfile) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            WaexCircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = colors.primary,
                                strokeWidth = 3.dp
                            )
                        }
                    } else {
                        val profile = userProfileData

                        // Location & Stats Row
                        if (profile != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (profile.location.isNotEmpty()) {
                                    Icon(
                                        imageVector = Icons.Rounded.LocationOn,
                                        contentDescription = null,
                                        tint = colors.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = profile.location,
                                        style = typography.labelSm,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                if (profile.followers > 0) {
                                    Text(
                                        text = if (profile.location.isNotEmpty()) UiText.get(R.string.waex_ui_followers_count_compact, " • %1\$s followers", profile.followers) else UiText.get(R.string.waex_ui_followers_count, "%1\$s followers", profile.followers),
                                        style = typography.labelSm,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                if (user.contributions > 0) {
                                    Text(
                                        text = UiText.get(R.string.waex_ui_1_s_commits, " • %1\$s commits", user.contributions),
                                        style = typography.labelSm,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                            }

                            if (profile.bio.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = profile.bio,
                                    style = typography.bodyMd,
                                    color = colors.onSurface,
                                    lineHeight = 20.sp
                                )
                            }

                            // Chips (Open to hire, Twitter)
                            if (profile.hireable || profile.twitter.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (profile.hireable) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(colors.primaryContainer)
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Rounded.CheckCircle,
                                                    contentDescription = null,
                                                    tint = colors.primary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = UiText.get(R.string.waex_ui_open_to_hire, "Open to hire"),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = colors.primary
                                                )
                                            }
                                        }
                                    }
                                    if (profile.twitter.isNotEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(colors.surfaceContainerHighest)
                                                .clickable { openUrl("https://x.com/${profile.twitter}") }
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = "@${profile.twitter}",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = colors.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Action Buttons
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { openUrl(user.htmlUrl) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp),
                                shape = radius.buttonShape,
                                border = BorderStroke(1.dp, colors.outlineVariant)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_github),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = colors.onSurface
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = UiText.get(R.string.waex_ui_view_github_profile, "View GitHub Profile"),
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onSurface
                                )
                            }

                            if (userProfileData?.blog?.isNotEmpty() == true) {
                                val blogUrl = if (userProfileData!!.blog.startsWith("http")) userProfileData!!.blog else "https://${userProfileData!!.blog}"
                                OutlinedButton(
                                    onClick = { openUrl(blogUrl) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp),
                                    shape = radius.buttonShape,
                                    border = BorderStroke(1.dp, colors.outlineVariant)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = colors.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = UiText.get(R.string.waex_ui_visit_website, "Visit Website"),
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.onSurface
                                    )
                                }
                            }

                            if (user.contributions > 0) {
                                Button(
                                    onClick = { loadContributionStats(user.login) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp),
                                    shape = radius.buttonShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colors.primary,
                                        contentColor = colors.onPrimary
                                    )
                                ) {
                                    Text(
                                        text = UiText.get(R.string.waex_ui_view_detailed_contributions, "View Detailed Contributions"),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Detailed Contributions View
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(onClick = { showContributionsView = false }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = UiText.get(R.string.action_back, "Back"),
                                tint = colors.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = UiText.get(R.string.waex_ui_1_s_s_contributions, "%1\$s's Contributions", userProfileData?.name ?: user.login),
                            style = typography.headlineMd,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (isLoadingStats) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            WaexCircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = colors.primary,
                                strokeWidth = 3.dp
                            )
                        }
                    } else {
                        val stats = contributionsStats
                        val format = NumberFormat.getInstance()

                        Surface(
                            shape = radius.bentoCardShape,
                            color = colors.surfaceContainerLow,
                            border = BorderStroke(1.dp, colors.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = UiText.get(R.string.waex_ui_total_commits, "Total Commits"),
                                        style = typography.bodyLg,
                                        color = colors.onSurfaceVariant
                                    )
                                    Text(
                                        text = format.format(stats?.totalCommits ?: user.contributions),
                                        style = typography.bodyLg,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface
                                    )
                                }

                                if (stats != null) {
                                    HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = UiText.get(R.string.waex_ui_lines_added, "Lines Added"),
                                            style = typography.bodyLg,
                                            color = colors.onSurfaceVariant
                                        )
                                        Text(
                                            text = "+ ${format.format(stats.linesAdded)}",
                                            style = typography.bodyLg,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.success
                                        )
                                    }

                                    HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = UiText.get(R.string.waex_ui_lines_deleted, "Lines Deleted"),
                                            style = typography.bodyLg,
                                            color = colors.onSurfaceVariant
                                        )
                                        Text(
                                            text = "- ${format.format(stats.linesDeleted)}",
                                            style = typography.bodyLg,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun parseContributors(json: String, list: MutableList<GitHubContributor>) {
    try {
        val array = JSONArray(json)
        val tempList = mutableListOf<GitHubContributor>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            tempList.add(
                GitHubContributor(
                    login = obj.optString("login"),
                    avatarUrl = obj.optString("avatar_url"),
                    htmlUrl = obj.optString("html_url"),
                    contributions = obj.optInt("contributions", 0)
                )
            )
        }
        list.clear()
        list.addAll(tempList)
    } catch (ignored: Exception) {
    }
}

private fun parseUserProfile(
    json: String,
    fallbackHtmlUrl: String,
    avatarUrl: String,
    contributions: Int
): GitHubUserProfile {
    val obj = JSONObject(json)
    val name = obj.optString("name", "")
    val login = obj.optString("login", "")
    val location = obj.optString("location", "")
    val bio = obj.optString("bio", "")
    val hireable = obj.optBoolean("hireable", false)
    val followers = obj.optInt("followers", 0)
    val twitter = obj.optString("twitter_username", "")
    val blog = obj.optString("blog", "")
    val htmlUrl = obj.optString("html_url", fallbackHtmlUrl)

    return GitHubUserProfile(
        login = login,
        name = if (name.isEmpty() || name == "null") login else name,
        avatarUrl = avatarUrl,
        htmlUrl = htmlUrl,
        bio = if (bio == "null") "" else bio,
        location = if (location == "null") "" else location,
        followers = followers,
        twitter = if (twitter == "null") "" else twitter,
        blog = if (blog == "null") "" else blog,
        hireable = hireable,
        contributions = contributions
    )
}

private fun parseStats(json: String, login: String): RepoStats {
    var totalCommits = 0
    var linesAdded = 0L
    var linesDeleted = 0L

    try {
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val author = obj.optJSONObject("author")
            if (author != null) {
                val authorLogin = author.optString("login")
                if (authorLogin.equals(login, ignoreCase = true)) {
                    totalCommits = obj.optInt("total", 0)
                    val weeks = obj.optJSONArray("weeks")
                    if (weeks != null) {
                        for (j in 0 until weeks.length()) {
                            val week = weeks.getJSONObject(j)
                            linesAdded += week.optLong("a", 0)
                            linesDeleted += week.optLong("d", 0)
                        }
                    }
                    break
                }
            }
        }
    } catch (ignored: Exception) {
    }

    return RepoStats(
        totalCommits = totalCommits,
        linesAdded = linesAdded,
        linesDeleted = linesDeleted
    )
}
