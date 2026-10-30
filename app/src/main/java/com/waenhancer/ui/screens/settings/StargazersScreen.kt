package com.waenhancer.ui.screens.settings

import com.waenhancer.utils.UiText

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import com.waenhancer.ui.components.WaexModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.app.BuildConfig
import com.waenhancer.app.R
import com.waenhancer.config.PreferenceStores
import com.waenhancer.licensing.LicenseManager
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.components.NetworkErrorCenter
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.sin
import kotlin.random.Random
import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import java.text.DateFormat

private const val PROJECT_URL = "https://github.com/WaEnhancerX/WaEnhancerX"
private const val STARGAZERS_DATA_URL = "https://waex.mubashar.dev/api/stargazers"
private const val GITHUB_PROFILE_API_URL = "https://waex.mubashar.dev/api/v1/github/users"

private data class GitHubStargazer(
    val login: String,
    val avatarUrl: String,
    val profileUrl: String,
    val starredAt: String,
    val badge: String? = null
)

private data class StargazerProfile(
    val login: String,
    val name: String,
    val avatarUrl: String,
    val profileUrl: String,
    val bio: String,
    val location: String,
    val company: String,
    val blog: String,
    val twitter: String,
    val followers: Int,
    val following: Int,
    val publicRepos: Int
)

private data class StargazersSnapshot(
    val stargazers: List<GitHubStargazer>,
    val updatedAt: String,
    val updateIntervalHours: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StargazersScreen() {
    val context = LocalContext.current
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val spacing = WaexTheme.spacing

    var stargazers by remember { mutableStateOf(emptyList<GitHubStargazer>()) }
    var lastUpdated by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var selectedStargazer by remember { mutableStateOf<GitHubStargazer?>(null) }
    var selectedProfile by remember { mutableStateOf<StargazerProfile?>(null) }
    var isLoadingProfile by remember { mutableStateOf(false) }
    var profileError by remember { mutableStateOf<String?>(null) }
    var showSupportSheet by remember { mutableStateOf(false) }
    val currentUserGithubLogin = remember(context) { activeProGithubLogin(context) }
    var currentUserProfile by remember { mutableStateOf<StargazerProfile?>(null) }
    var isSearchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val confettiProgress = remember { Animatable(0f) }
    val supportSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pullToRefreshState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    val filteredStargazers = remember(stargazers, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) stargazers
        else stargazers.filter { it.login.contains(query, ignoreCase = true) }
    }
    val currentUserStargazer = remember(stargazers, currentUserGithubLogin) {
        stargazers.firstOrNull { it.login.equals(currentUserGithubLogin, ignoreCase = true) }
    }

    LaunchedEffect(currentUserGithubLogin) {
        if (currentUserGithubLogin.isEmpty()) return@LaunchedEffect
        loadStargazerProfile(context, currentUserGithubLogin)
            .onSuccess { currentUserProfile = it }
    }

    LaunchedEffect(Unit) {
        confettiProgress.animateTo(1f, animationSpec = tween(durationMillis = 2600))
    }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    fun openProjectForStar() {
        Toast.makeText(
            context,
            UiText.get(R.string.waex_ui_tap_star_on_github_and_your_profile_wi, "Tap Star on GitHub and your profile will appear after the list updates."),
            Toast.LENGTH_SHORT
        ).show()
        openUrl(PROJECT_URL)
    }

    LaunchedEffect(refreshKey) {
        isLoading = true
        errorMessage = null
        val result = withContext(Dispatchers.IO) { loadStargazers(context, forceRefresh = refreshKey > 0) }
        result.onSuccess { snapshot ->
            stargazers = sortStargazers(context, snapshot.stargazers)
            lastUpdated = formatUpdatedAt(snapshot.updatedAt)
        }.onFailure { failure ->
            errorMessage = UiText.get(R.string.waex_ui_we_couldn_t_load_the_supporters_right, "We couldn't load the supporters right now.")
            stargazers = emptyList()
            if (NetworkErrorCenter.isNetworkError(failure)) {
                NetworkErrorCenter.show(failure) { refreshKey++ }
            }
        }
        isLoading = false
    }

    LaunchedEffect(selectedStargazer?.login) {
        val stargazer = selectedStargazer ?: return@LaunchedEffect
        selectedProfile = null
        profileError = null
        isLoadingProfile = true
        loadStargazerProfile(context, stargazer.login)
            .onSuccess { selectedProfile = it }
            .onFailure { failure ->
                profileError = UiText.get(R.string.waex_ui_could_not_load_this_github_profile, "Could not load this GitHub profile.")
                if (NetworkErrorCenter.isNetworkError(failure)) {
                    NetworkErrorCenter.show(failure)
                }
            }
        isLoadingProfile = false
    }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = stringResource(R.string.stargazers_title),
                onBackClick = { navController.popBack() },
                actions = {
                    IconButton(
                        onClick = {
                            isSearchVisible = !isSearchVisible
                            if (!isSearchVisible) searchQuery = ""
                        }
                    ) {
                        Icon(
                            imageVector = if (isSearchVisible) WaexIcons.Clear else WaexIcons.Search,
                            contentDescription = if (isSearchVisible) UiText.get(R.string.waex_ui_close_search, "Close search") else UiText.get(R.string.waex_ui_search_supporters, "Search supporters"),
                            tint = colors.onSurface
                        )
                    }
                    if (currentUserProfile != null) {
                        IconButton(
                            onClick = {
                                val profile = currentUserProfile ?: return@IconButton
                                selectedProfile = profile
                                profileError = null
                                selectedStargazer = currentUserStargazer ?: GitHubStargazer(
                                    login = profile.login,
                                    avatarUrl = profile.avatarUrl,
                                    profileUrl = profile.profileUrl,
                                    starredAt = ""
                                )
                            }
                        ) {
                            AsyncAvatarImage(
                                url = currentUserProfile?.avatarUrl.orEmpty(),
                                contentDescription = UiText.get(R.string.waex_ui_open_your_github_profile, "Open your GitHub profile"),
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .border(1.dp, colors.outlineVariant, CircleShape)
                            )
                        }
                    } else {
                        IconButton(onClick = { showSupportSheet = true }) {
                            Text(
                                text = "🫵",
                                style = typography.headlineMd,
                                modifier = Modifier
                                    .padding(bottom = 2.dp)
                                    .semantics { contentDescription = UiText.get(R.string.waex_ui_join_the_supporters_list, "Join the supporters list") }
                            )
                        }
                    }
                }
            )
        },
        containerColor = colors.background
    ) { scaffoldPadding ->
        PullToRefreshBox(
            isRefreshing = isLoading && refreshKey > 0,
            onRefresh = { refreshKey++ },
            state = pullToRefreshState,
            indicator = {
                val isRefreshing = isLoading && refreshKey > 0
                if (isRefreshing || pullToRefreshState.distanceFraction > 0f) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                            .size(52.dp)
                            .shadow(6.dp, RoundedCornerShape(18.dp)),
                        shape = RoundedCornerShape(18.dp),
                        color = colors.primaryContainer,
                        contentColor = colors.primary
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isRefreshing) {
                                LoadingIndicator(
                                    modifier = Modifier.size(30.dp),
                                    color = colors.primary
                                )
                            } else {
                                Icon(
                                    imageVector = WaexIcons.Refresh,
                                    contentDescription = UiText.get(R.string.waex_ui_refresh, "Refresh"),
                                    modifier = Modifier
                                        .size(22.dp)
                                        .graphicsLayer {
                                            rotationZ = 180f * pullToRefreshState.distanceFraction.coerceIn(0f, 1f)
                                        }
                                )
                            }
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = spacing.pageMargin,
                    end = spacing.pageMargin,
                    top = 16.dp,
                    bottom = 24.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            if (isSearchVisible) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(UiText.get(R.string.waex_ui_search_github_usernames, "Search GitHub usernames")) },
                        leadingIcon = {
                            Icon(WaexIcons.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(WaexIcons.Clear, contentDescription = UiText.get(R.string.waex_ui_clear_search, "Clear search"))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                AnimatedVisibility(
                    visible = !isSearchVisible,
                    enter = fadeIn(tween(250)) + expandVertically(animationSpec = tween(250)),
                    exit = fadeOut(tween(200)) + shrinkVertically(animationSpec = tween(250))
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = colors.primaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = colors.primary,
                                contentColor = colors.onPrimary,
                                modifier = Modifier.size(58.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = WaexIcons.Premium,
                                        contentDescription = null,
                                        tint = colors.onPrimary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = stringResource(R.string.stargazers_thank_you),
                                style = typography.headlineMd,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface,
                                textAlign = TextAlign.Start
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.stargazers_message),
                                style = typography.bodyMd,
                                color = colors.onSurfaceVariant,
                                textAlign = TextAlign.Start
                            )
                            if (stargazers.isNotEmpty()) {
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    text = UiText.get(R.string.waex_ui_1_s_supporters_and_counting, "%1\$s supporters and counting", stargazers.size),
                                    style = typography.bodyLg,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSurface
                                )
                                if (lastUpdated.isNotEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = stringResource(R.string.stargazers_last_updated, lastUpdated),
                                        style = typography.bodyMd,
                                        color = colors.onSurfaceVariant,
                                        textAlign = TextAlign.Start
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (isLoading) {
                items(
                    count = 9,
                    key = { "stargazer_shimmer_$it" }
                ) {
                    StargazerCardShimmer()
                }
            } else if (errorMessage != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            style = typography.bodyLg,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { refreshKey++ }) {
                            Icon(WaexIcons.Refresh, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(UiText.get(R.string.waex_ui_try_again, "Try again"))
                        }
                    }
                }
            } else if (stargazers.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = UiText.get(R.string.waex_ui_be_the_first_supporter_featured_here_b, "Be the first supporter featured here by starring WaEnhancerX on GitHub."),
                            style = typography.bodyLg,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { showSupportSheet = true }) {
                            Icon(WaexIcons.Premium, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(UiText.get(R.string.waex_ui_open_github, "Open GitHub"))
                        }
                    }
                }
            } else if (filteredStargazers.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = WaexIcons.Search,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = UiText.get(R.string.waex_ui_couldn_t_find_you_or_your_friend_here, "Couldn’t find you or your friend here?"),
                            style = typography.bodyLg,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = UiText.get(R.string.waex_ui_join_our_supporters_and_get_your_githu, "Join our supporters and get your GitHub profile featured in this list."),
                            style = typography.bodyMd,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { showSupportSheet = true }) {
                            Text("🫵")
                            Spacer(Modifier.width(8.dp))
                            Text(UiText.get(R.string.waex_ui_become_a_supporter, "Become a supporter"), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                items(filteredStargazers, key = { it.login }) { stargazer ->
                    val isDev = stargazer.login.equals(BuildConfig.DEV_GH.trim(), ignoreCase = true)
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = colors.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedProfile = null
                                profileError = null
                                selectedStargazer = stargazer
                            }
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                AsyncAvatarImage(
                                    url = stargazer.avatarUrl,
                                    contentDescription = UiText.get(R.string.waex_ui_1_s_s_avatar, "%1\$s's avatar", stargazer.login),
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                )
                                Spacer(Modifier.height(9.dp))
                                Text(
                                    text = stargazer.login,
                                    style = typography.bodyMd,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            if (isDev) {
                                Surface(
                                    shape = CircleShape,
                                    color = colors.surfaceContainerHighest,
                                    border = BorderStroke(1.dp, colors.outlineVariant),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 8.dp, end = 8.dp)
                                        .size(24.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "</>",
                                            style = typography.labelSm,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp,
                                            color = colors.primary
                                        )
                                    }
                                }
                            } else if ("contributor".equals(stargazer.badge, ignoreCase = true) || "pro".equals(stargazer.badge, ignoreCase = true)) {
                                Surface(
                                    shape = CircleShape,
                                    color = colors.surfaceContainerHighest,
                                    border = BorderStroke(1.dp, colors.outlineVariant),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 8.dp, end = 8.dp)
                                        .size(24.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Rounded.VolunteerActivism,
                                            contentDescription = stringResource(R.string.contributor),
                                            tint = colors.primary,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
            SupportersConfettiOverlay(progress = confettiProgress.value)
        }
    }

    selectedStargazer?.let { stargazer ->
        WaexModalBottomSheet(
            onDismissRequest = { selectedStargazer = null },
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncAvatarImage(
                        url = selectedProfile?.avatarUrl?.ifEmpty { stargazer.avatarUrl } ?: stargazer.avatarUrl,
                        contentDescription = stargazer.login,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = selectedProfile?.name?.ifEmpty { stargazer.login } ?: stargazer.login,
                                style = typography.headlineMd,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface
                            )
                            val isStargazerDev = stargazer.login.equals(BuildConfig.DEV_GH.trim(), ignoreCase = true)
                            if (isStargazerDev) {
                                val sheetDevTooltipState = rememberTooltipState()
                                TooltipBox(
                                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                                    tooltip = {
                                        PlainTooltip(
                                            containerColor = colors.surfaceContainerHighest,
                                            contentColor = colors.onSurface
                                        ) {
                                            Text(
                                                text = UiText.get(R.string.waex_ui_developer, "Developer"),
                                                style = typography.labelSm,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    },
                                    state = sheetDevTooltipState
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = colors.surfaceContainerHighest,
                                        border = BorderStroke(1.dp, colors.outlineVariant),
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clickable {
                                                scope.launch { sheetDevTooltipState.show() }
                                            }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "</>",
                                                style = typography.labelSm,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 9.sp,
                                                color = colors.primary
                                            )
                                        }
                                    }
                                }
                            } else if ("contributor".equals(stargazer.badge, ignoreCase = true) || "pro".equals(stargazer.badge, ignoreCase = true)) {
                                val sheetBadgeTooltipState = rememberTooltipState()
                                TooltipBox(
                                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                                    tooltip = {
                                        PlainTooltip(
                                            containerColor = colors.surfaceContainerHighest,
                                            contentColor = colors.onSurface
                                        ) {
                                            Text(
                                                text = stringResource(R.string.contributor),
                                                style = typography.labelSm,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    },
                                    state = sheetBadgeTooltipState
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = colors.surfaceContainerHighest,
                                        border = BorderStroke(1.dp, colors.outlineVariant),
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clickable {
                                                scope.launch { sheetBadgeTooltipState.show() }
                                            }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Rounded.VolunteerActivism,
                                                contentDescription = stringResource(R.string.contributor),
                                                tint = colors.primary,
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Text(
                            text = "@${stargazer.login}",
                            style = typography.bodyMd,
                            color = colors.onSurfaceVariant
                        )
                    }
                }

                val isCurrentProUser = stargazer.login.equals(currentUserGithubLogin, ignoreCase = true)
                val isCurrentUserStargazer = isCurrentProUser && currentUserStargazer != null
                if (isCurrentUserStargazer) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = UiText.get(R.string.waex_ui_thank_you_for_being_a_pro_subscriber_a, "Thank you for being a Pro subscriber and a WaEnhancerX stargazer!"),
                        style = typography.bodyLg,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primary
                    )
                } else if (stargazer.starredAt.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = UiText.get(R.string.waex_ui_supporting_since_1_s, "Supporting since %1\$s", formatUpdatedAt(stargazer.starredAt).ifEmpty { UiText.get(R.string.waex_ui_date_unavailable, "date unavailable") }),
                        style = typography.bodyMd,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primary
                    )
                }
                Spacer(Modifier.height(14.dp))

                if (isLoadingProfile) {
                    StargazerProfileShimmer()
                } else {
                    selectedProfile?.let { profile ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (profile.location.isNotEmpty()) {
                                Icon(
                                    imageVector = Icons.Rounded.LocationOn,
                                    contentDescription = null,
                                    tint = colors.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(profile.location, style = typography.labelSm, color = colors.onSurfaceVariant)
                            }
                            val prefix = if (profile.location.isNotEmpty()) " | " else ""
                            Text(
                                text = UiText.get(R.string.waex_ui_1_s_2_s_followers_3_s_repositories, "%1\$s%2\$s followers | %3\$s repositories", prefix, profile.followers, profile.publicRepos),
                                style = typography.labelSm,
                                color = colors.onSurfaceVariant
                            )
                        }
                        if (profile.company.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(profile.company, style = typography.bodyMd, color = colors.onSurfaceVariant)
                        }
                        if (profile.bio.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text(profile.bio, style = typography.bodyMd, color = colors.onSurface)
                        }
                    }
                    profileError?.let {
                        Text(it, style = typography.bodyMd, color = colors.onSurfaceVariant)
                    }
                }

                Spacer(Modifier.height(20.dp))
                OutlinedButton(
                    onClick = { openUrl(selectedProfile?.profileUrl ?: stargazer.profileUrl) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, colors.outlineVariant)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_github),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(UiText.get(R.string.waex_ui_view_github_profile, "View GitHub Profile"), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    if (showSupportSheet) {
        WaexModalBottomSheet(
            onDismissRequest = { showSupportSheet = false },
            sheetState = supportSheetState,
            containerColor = colors.surface,
            contentColor = colors.onSurface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = colors.primary.copy(alpha = 0.12f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "🫵",
                            style = typography.headlineMd
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = UiText.get(R.string.waex_ui_want_to_see_yourself_here, "Want to see yourself here?"),
                    style = typography.headlineMd,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = UiText.get(R.string.waex_ui_star_waenhancerx_on_github_and_your_pr, "Star WaEnhancerX on GitHub and your profile can join this supporters list. It’s free, takes only a moment, and helps more people discover the project. New stars may take a little while to appear."),
                    style = typography.bodyMd,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        showSupportSheet = false
                        openProjectForStar()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = WaexIcons.Premium,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(UiText.get(R.string.waex_ui_add_me_to_the_list, "Add me to the list"), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private data class ConfettiPiece(
    val x: Float,
    val delay: Float,
    val speed: Float,
    val drift: Float,
    val size: Float,
    val rotation: Float,
    val colorIndex: Int,
    val circular: Boolean
)

@Composable
private fun SupportersConfettiOverlay(progress: Float) {
    if (progress >= 1f) return
    val pieces = remember {
        val random = Random(2026)
        List(52) {
            ConfettiPiece(
                x = random.nextFloat(),
                delay = random.nextFloat() * 0.28f,
                speed = 0.85f + random.nextFloat() * 0.35f,
                drift = (random.nextFloat() - 0.5f) * 90f,
                size = 6f + random.nextFloat() * 8f,
                rotation = random.nextFloat() * 360f,
                colorIndex = random.nextInt(6),
                circular = random.nextBoolean()
            )
        }
    }
    val palette = remember {
        listOf(
            Color(0xFFFFC107),
            Color(0xFFFF5C8A),
            Color(0xFF7C4DFF),
            Color(0xFF00BCD4),
            Color(0xFF4CAF50),
            Color(0xFFFF7043)
        )
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        pieces.forEach { piece ->
            val localProgress = ((progress - piece.delay) / (1f - piece.delay))
                .coerceIn(0f, 1f) * piece.speed
            if (localProgress <= 0f || localProgress >= 1.1f) return@forEach
            val fade = if (localProgress > 0.82f) ((1f - localProgress) / 0.18f).coerceIn(0f, 1f) else 1f
            val x = piece.x * size.width +
                sin(localProgress * 10f + piece.rotation) * piece.drift
            val y = -piece.size + localProgress * (size.height + piece.size * 3f)
            val color = palette[piece.colorIndex].copy(alpha = fade)

            rotate(piece.rotation + localProgress * 540f, pivot = Offset(x, y)) {
                if (piece.circular) {
                    drawCircle(color = color, radius = piece.size * 0.45f, center = Offset(x, y))
                } else {
                    drawRect(
                        color = color,
                        topLeft = Offset(x - piece.size / 2f, y - piece.size / 3f),
                        size = Size(piece.size, piece.size * 0.66f)
                    )
                }
            }
        }
    }
}

private fun loadStargazers(context: Context, forceRefresh: Boolean = false): Result<StargazersSnapshot> = runCatching {
    val prefs = PreferenceStores.cacheStore(context, PreferenceStores.GITHUB_API_CACHE)
    val cachedJson = prefs.getString("stargazers_json_v3", null)
    val cachedSnapshot = cachedJson?.let { runCatching { parseStargazers(it) }.getOrNull() }

    // If opened directly (not refreshed) and cache is available, return cached data
    if (!forceRefresh && cachedSnapshot != null && cachedSnapshot.hasStarredAt()) {
        return@runCatching cachedSnapshot
    }

    try {
        val request = Request.Builder()
            .url(STARGAZERS_DATA_URL)
            .header("User-Agent", "WaEnhancerX-App")
            .header("Cache-Control", "no-cache, no-store")
            .build()
        val json = OkHttpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Stargazer data returned ${response.code}")
            response.body?.string().orEmpty().also { parseStargazers(it) }
        }
        val snapshot = parseStargazers(json).also {
            check(it.hasStarredAt()) { "Stargazer timestamps are missing" }
        }

        prefs.edit()
            .putString("stargazers_json_v3", json)
            .putLong("stargazers_last_fetch_v3", System.currentTimeMillis())
            .apply()
        snapshot
    } catch (error: Exception) {
        if (cachedSnapshot?.hasStarredAt() == true) cachedSnapshot else throw error
    }
}

private fun StargazersSnapshot.hasStarredAt(): Boolean =
    stargazers.isNotEmpty() && stargazers.all { it.starredAt.isNotBlank() }

private fun sortStargazers(context: Context, rawList: List<GitHubStargazer>): List<GitHubStargazer> {
    if (rawList.isEmpty()) return rawList

    val devGh = BuildConfig.DEV_GH.trim()
    val isPro = "ACTIVE".equals(LicenseManager.getProStatus(context), ignoreCase = true)
    val userPrefs = LicenseManager.getSharedPreferences(context)
    val userGh = if (isPro) {
        val gh = userPrefs.getString("gh_username", "")?.trim().orEmpty()
        if (gh.isNotBlank() && !gh.equals("null", ignoreCase = true)) gh else ""
    } else ""

    val devStargazer = if (devGh.isNotEmpty()) {
        rawList.firstOrNull { it.login.equals(devGh, ignoreCase = true) }
    } else null

    val userStargazer = if (userGh.isNotEmpty() && !userGh.equals(devGh, ignoreCase = true)) {
        rawList.firstOrNull { it.login.equals(userGh, ignoreCase = true) }
    } else null

    val result = ArrayList<GitHubStargazer>(rawList.size)
    if (devStargazer != null) {
        result.add(devStargazer)
    }
    if (userStargazer != null) {
        result.add(userStargazer)
    }

    val remaining = rawList.filter { item ->
        val isDev = devStargazer != null && item.login.equals(devStargazer.login, ignoreCase = true)
        val isUser = userStargazer != null && item.login.equals(userStargazer.login, ignoreCase = true)
        !isDev && !isUser
    }

    val contributorUsers = remaining.filter {
        "contributor".equals(it.badge, ignoreCase = true) || "pro".equals(it.badge, ignoreCase = true)
    }
    val regularUsers = remaining.filter {
        !"contributor".equals(it.badge, ignoreCase = true) && !"pro".equals(it.badge, ignoreCase = true)
    }

    result.addAll(contributorUsers)
    result.addAll(regularUsers)

    return result
}

private fun activeProGithubLogin(context: Context): String {
    if (!"ACTIVE".equals(LicenseManager.getProStatus(context), ignoreCase = true)) return ""
    return LicenseManager.getSharedPreferences(context)
        .getString("gh_username", "")
        ?.trim()
        .orEmpty()
        .takeUnless { it.equals("null", ignoreCase = true) }
        .orEmpty()
}

private fun parseStargazers(json: String): StargazersSnapshot {
    val trimmedJson = json.trim()
    val root = if (trimmedJson.startsWith("{")) JSONObject(trimmedJson) else null
    val array = root?.optJSONArray("stargazers") ?: JSONArray(trimmedJson)
    val users = buildList {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val login = item.optString("login")
            val badge = if (item.has("badge") && !item.isNull("badge")) {
                item.optString("badge").takeIf { it.isNotBlank() }
            } else null
            if (login.isNotBlank()) {
                add(
                    GitHubStargazer(
                        login = login,
                        avatarUrl = item.optString("avatar_url"),
                        profileUrl = item.optString("html_url", "https://github.com/$login"),
                        starredAt = item.optString("starred_at"),
                        badge = badge
                    )
                )
            }
        }
    }
    return StargazersSnapshot(
        stargazers = users,
        updatedAt = root?.optString("updated_at").orEmpty(),
        updateIntervalHours = root?.optInt("update_interval_hours", 5) ?: 5
    )
}

private suspend fun loadStargazerProfile(
    context: Context,
    login: String
): Result<StargazerProfile> = withContext(Dispatchers.IO) {
    runCatching {
        val prefs = PreferenceStores.cacheStore(context, PreferenceStores.GITHUB_USER_CACHE)
        val cacheKey = "stargazer_profile_${login.lowercase(Locale.US)}"
        prefs.getString(cacheKey, null)?.let { cachedJson ->
            return@runCatching parseStargazerProfile(cachedJson, login)
        }

        val request = Request.Builder()
            .url("$GITHUB_PROFILE_API_URL/$login")
            .header("User-Agent", "WaEnhancerX-App")
            .build()
        val json = OkHttpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Profile endpoint returned ${response.code}")
            response.body?.string().orEmpty()
        }
        parseStargazerProfile(json, login).also {
            prefs.edit().putString(cacheKey, json).apply()
        }
    }
}

private fun parseStargazerProfile(json: String, fallbackLogin: String): StargazerProfile {
    val user = JSONObject(json)
    return StargazerProfile(
        login = user.optString("login", fallbackLogin),
        name = user.optString("name").nullSafe(),
        avatarUrl = user.optString("avatar_url").nullSafe(),
        profileUrl = user.optString("html_url", "https://github.com/$fallbackLogin"),
        bio = user.optString("bio").nullSafe(),
        location = user.optString("location").nullSafe(),
        company = user.optString("company").nullSafe(),
        blog = user.optString("blog").nullSafe(),
        twitter = user.optString("twitter_username").nullSafe(),
        followers = user.optInt("followers"),
        following = user.optInt("following"),
        publicRepos = user.optInt("public_repos")
    )
}

@Composable
private fun stargazerShimmerBrush(): androidx.compose.ui.graphics.Brush {
    val colors = WaexTheme.colors
    val shimmerColors = listOf(
        colors.surfaceContainerHighest,
        colors.surfaceContainerHighest.copy(alpha = 0.55f),
        colors.onSurface.copy(alpha = 0.07f),
        colors.surfaceContainerHighest.copy(alpha = 0.55f),
        colors.surfaceContainerHighest
    )
    val transition = rememberInfiniteTransition(label = "stargazer shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1400f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "stargazer shimmer translate"
    )
    return androidx.compose.ui.graphics.Brush.linearGradient(
        colors = shimmerColors,
        start = androidx.compose.ui.geometry.Offset(translateAnim - 700f, 0f),
        end = androidx.compose.ui.geometry.Offset(translateAnim, 0f)
    )
}

@Composable
private fun StargazerProfileShimmer() {
    val shimmer = stargazerShimmerBrush()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .height(14.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(shimmer)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(shimmer)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .height(16.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(shimmer)
        )
    }
}

@Composable
private fun StargazerCardShimmer() {
    val colors = WaexTheme.colors
    val radius = WaexTheme.radius
    val shimmer = stargazerShimmerBrush()

    Surface(
        shape = radius.bentoCardShape,
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(shimmer)
            )
            Spacer(Modifier.height(9.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(shimmer)
            )
        }
    }
}

private fun String.nullSafe(): String = if (this == "null") "" else this

private fun formatUpdatedAt(timestamp: String): String {
    if (timestamp.isBlank()) return ""
    return runCatching {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val cleanTimestamp = timestamp.replace(Regex("\\.\\d+Z$|Z$"), "")
        val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, UiText.displayLocale())
        formatter.format(parser.parse(cleanTimestamp) ?: Date())
    }.getOrDefault(timestamp)
}
