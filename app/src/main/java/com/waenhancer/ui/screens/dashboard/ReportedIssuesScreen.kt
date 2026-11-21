package com.waenhancer.ui.screens.dashboard

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import com.waenhancer.ui.screens.license.shimmerBrush
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.waenhancer.app.R
import com.waenhancer.licensing.report.ReportLogin
import com.waenhancer.ui.components.WaexCard
import com.waenhancer.ui.components.WaexEmptyState
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private sealed interface IssuesState {
    data object Loading : IssuesState
    data object Failed : IssuesState
    data class Loaded(val issues: List<ReportLogin.Issue>) : IssuesState
}

/** Issues the logged-in Telegram account has reported, with a button to create a new one. */
@Composable
fun ReportedIssuesScreen() {
    val colors = WaexTheme.colors
    val context = LocalContext.current
    val navController = LocalWaexNavController.current

    var reloadKey by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<IssuesState>(IssuesState.Loading) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(reloadKey) {
        // A pull-to-refresh keeps the current list on screen; only a first load shows the skeleton.
        if (state !is IssuesState.Loaded) state = IssuesState.Loading
        refreshing = true
        val result = withContext(Dispatchers.IO) { ReportLogin.loadMyIssues(context) }
        refreshing = false
        when (result) {
            is ReportLogin.IssuesResult.Loaded -> state = IssuesState.Loaded(result.issues)
            ReportLogin.IssuesResult.Failed ->
                if (state is IssuesState.Loaded) {
                    Toast.makeText(context, R.string.report_issues_load_failed, Toast.LENGTH_SHORT).show()
                } else {
                    state = IssuesState.Failed
                }
            ReportLogin.IssuesResult.SessionExpired -> {
                Toast.makeText(context, R.string.license_tg_login_required, Toast.LENGTH_LONG).show()
                navController.popBack()
            }
        }
    }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = stringResource(R.string.report_issues_title),
                onBackClick = { navController.popBack() }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navController.navigateTo(Screen.ReportIssue) },
                containerColor = colors.primary,
                contentColor = colors.onPrimary,
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.report_issues_new),
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        containerColor = colors.background
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing && state is IssuesState.Loaded,
            onRefresh = { reloadKey++ },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            when (val current = state) {
                IssuesState.Loading -> IssuesSkeleton()
                IssuesState.Failed -> Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.report_issues_load_failed),
                        style = WaexTheme.typography.bodyMd,
                        color = colors.onSurfaceVariant
                    )
                    TextButton(onClick = { reloadKey++ }) {
                        Text(stringResource(R.string.report_issues_retry), color = colors.primary)
                    }
                }
                is IssuesState.Loaded ->
                    if (current.issues.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            contentAlignment = Alignment.Center
                        ) {
                            WaexEmptyState(
                                title = stringResource(R.string.report_issues_empty_title),
                                description = stringResource(R.string.report_issues_empty_desc),
                                icon = Icons.Rounded.BugReport
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = WaexTheme.spacing.pageMargin,
                                end = WaexTheme.spacing.pageMargin,
                                top = 12.dp,
                                bottom = 96.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(current.issues, key = { it.id }) { issue -> IssueCard(issue) }
                        }
                    }
            }
        }
    }
}

@Composable
private fun IssueCard(issue: ReportLogin.Issue) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val context = LocalContext.current
    val url = issue.issueUrl

    WaexCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            Unit
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "#${issue.id}",
                    style = typography.labelSm,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurfaceVariant
                )
                StatusChip(issue.status)
            }
            Text(
                text = issue.title,
                style = typography.bodyLg,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                val closedAt = issue.closedAt
                Text(
                    text = if (closedAt != null) {
                        stringResource(R.string.report_issues_closed_ago, relativeDate(closedAt))
                    } else {
                        relativeDate(issue.createdAt)
                    },
                    style = typography.labelSm,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Rounded.OpenInNew,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val colors = WaexTheme.colors
    val (label, container, content) = when (status.lowercase(Locale.US)) {
        "closed", "resolved" -> Triple(R.string.report_issues_status_closed, colors.successContainer, colors.onSuccessContainer)
        "in_progress" -> Triple(R.string.report_issues_status_in_progress, colors.warningContainer, colors.onWarningContainer)
        else -> Triple(R.string.report_issues_status_open, colors.primaryContainer, colors.primary)
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(content))
        Text(
            text = stringResource(label),
            style = WaexTheme.typography.labelSm,
            fontWeight = FontWeight.SemiBold,
            color = content
        )
    }
}

/** Placeholder rows with the same shape as [IssueCard] while the list loads. */
@Composable
private fun IssuesSkeleton() {
    val brush = shimmerBrush()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = false,
        contentPadding = PaddingValues(
            start = WaexTheme.spacing.pageMargin,
            end = WaexTheme.spacing.pageMargin,
            top = 12.dp,
            bottom = 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(5) {
            WaexCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SkeletonBar(brush, width = 36.dp, height = 12.dp)
                        SkeletonBar(brush, width = 72.dp, height = 22.dp, shape = CircleShape)
                    }
                    SkeletonBar(brush, modifier = Modifier.fillMaxWidth(), height = 16.dp)
                    SkeletonBar(brush, modifier = Modifier.fillMaxWidth(0.6f), height = 16.dp)
                    SkeletonBar(brush, width = 90.dp, height = 12.dp)
                }
            }
        }
    }
}

@Composable
private fun SkeletonBar(
    brush: Brush,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    height: Dp,
    shape: Shape = RoundedCornerShape(6.dp)
) {
    Box(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .height(height)
            .clip(shape)
            .background(brush)
    )
}

private fun relativeDate(iso: String): String = runCatching {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val time = parser.parse(iso.take(19))?.time ?: return@runCatching iso
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}.getOrDefault(iso)
