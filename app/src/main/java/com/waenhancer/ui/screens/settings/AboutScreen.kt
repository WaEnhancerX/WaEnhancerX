package com.waenhancer.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
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
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat

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

// In-memory Avatar Cache
object AvatarCache {
    private val memoryCache = androidx.collection.LruCache<String, Bitmap>(30)

    fun get(url: String): Bitmap? = memoryCache.get(url)
    fun put(url: String, bitmap: Bitmap) {
        memoryCache.put(url, bitmap)
    }
}

@Composable
fun AsyncAvatarImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(url) { mutableStateOf(AvatarCache.get(url)) }

    LaunchedEffect(url) {
        if (bitmap == null && url.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                try {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.setRequestProperty("User-Agent", "WaEnhancerX-App")
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    connection.doInput = true
                    connection.connect()
                    val input: InputStream = connection.inputStream
                    val decoded = BitmapFactory.decodeStream(input)
                    if (decoded != null) {
                        AvatarCache.put(url, decoded)
                        withContext(Dispatchers.Main) {
                            bitmap = decoded
                        }
                    }
                } catch (ignored: Exception) {
                }
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
            val prefs = com.waenhancer.config.PreferenceStores.cacheStore(context, com.waenhancer.config.PreferenceStores.GITHUB_API_CACHE)
            val lastFetch = prefs.getLong("last_fetch", 0)
            val cachedJson = prefs.getString("contributors_json", null)

            if (cachedJson != null && (System.currentTimeMillis() - lastFetch < 3600000)) {
                parseContributors(cachedJson, contributors)
                withContext(Dispatchers.Main) { isLoadingContributors = false }
                return@withContext
            }

            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://api.github.com/repos/mubashardev/WaEnhancer/contributors")
                .header("User-Agent", "WaEnhancerX-App")
                .header("Accept", "application/vnd.github.v3+json")
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
            val prefs = com.waenhancer.config.PreferenceStores.cacheStore(context, com.waenhancer.config.PreferenceStores.GITHUB_USER_CACHE)
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
                .header("Accept", "application/vnd.github.v3+json")
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
            val prefs = com.waenhancer.config.PreferenceStores.cacheStore(context, com.waenhancer.config.PreferenceStores.GITHUB_USER_CACHE)
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
                .header("Accept", "application/vnd.github.v3+json")
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
            Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
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
            // App Branding Header Card (CollapsingToolbar style in WaEnhancer)
            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceContainerLow,
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // App Launcher Icon
                    Image(
                        painter = painterResource(id = R.drawable.ic_app_logo),
                        contentDescription = "App Logo",
                        modifier = Modifier
                            .size(88.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.app_name),
                        style = typography.headlineMd,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        letterSpacing = (-0.5).sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Maintainer Pill Badge - mubashardev
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFF10B981).copy(alpha = 0.12f))
                            .border(1.dp, Color(0xFF10B981).copy(alpha = 0.5f), CircleShape)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "✦ ",
                                color = Color(0xFF10B981),
                                fontSize = 10.sp
                            )
                            Text(
                                text = "MAINTAINER",
                                color = Color(0xFF10B981),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = stringResource(R.string.maintainer),
                        style = typography.bodyLg,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                }
            }

            // Card 1: About Description
            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceContainerLow,
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
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
                }
            }

            // Card 2: Installation Instructions
            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceContainerLow,
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
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
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
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
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
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
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.contributors),
                        style = typography.headlineMd,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (isLoadingContributors) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(90.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = colors.primary,
                                strokeWidth = 3.dp
                            )
                        }
                    } else if (contributors.isEmpty()) {
                        Text(
                            text = "No contributors found",
                            style = typography.bodyMd,
                            color = colors.onSurfaceVariant
                        )
                    } else {
                        // 3-Column Grid matching WaEnhancer
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            maxItemsInEachRow = 3,
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            contributors.forEach { contributor ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .width(90.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            loadUserProfile(contributor)
                                        }
                                        .padding(8.dp)
                                ) {
                                    AsyncAvatarImage(
                                        url = contributor.avatarUrl,
                                        contentDescription = contributor.login,
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(CircleShape)
                                            .border(1.5.dp, colors.outlineVariant, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = contributor.login,
                                        style = typography.labelSm,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.onSurface,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Card 5: Support
            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceContainerLow,
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
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
                }
            }

            // Card 6: License & Disclaimer
            Surface(
                shape = radius.bentoCardShape,
                color = colors.surfaceContainerLow,
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
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

        ModalBottomSheet(
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
                            CircularProgressIndicator(
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
                                        text = if (profile.location.isNotEmpty()) " • ${profile.followers} followers" else "${profile.followers} followers",
                                        style = typography.labelSm,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                if (user.contributions > 0) {
                                    Text(
                                        text = " • ${user.contributions} commits",
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
                                                    text = "Open to hire",
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
                                    .height(52.dp),
                                shape = RoundedCornerShape(12.dp),
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
                                    text = "View GitHub Profile",
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
                                        .height(52.dp),
                                    shape = RoundedCornerShape(12.dp),
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
                                        text = "Visit Website",
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
                                        .height(52.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colors.primary,
                                        contentColor = colors.onPrimary
                                    )
                                ) {
                                    Text(
                                        text = "View Detailed Contributions",
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
                                contentDescription = "Back",
                                tint = colors.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${userProfileData?.name ?: user.login}'s Contributions",
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
                            CircularProgressIndicator(
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
                                        text = "Total Commits",
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
                                            text = "Lines Added",
                                            style = typography.bodyLg,
                                            color = colors.onSurfaceVariant
                                        )
                                        Text(
                                            text = "+ ${format.format(stats.linesAdded)}",
                                            style = typography.bodyLg,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF10B981)
                                        )
                                    }

                                    HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Lines Deleted",
                                            style = typography.bodyLg,
                                            color = colors.onSurfaceVariant
                                        )
                                        Text(
                                            text = "- ${format.format(stats.linesDeleted)}",
                                            style = typography.bodyLg,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFEF4444)
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
