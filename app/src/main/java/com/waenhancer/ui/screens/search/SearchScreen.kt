package com.waenhancer.ui.screens.search

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.waenhancer.config.FeatureCatalog
import com.waenhancer.config.FeatureItem
import com.waenhancer.ui.components.WaexEmptyState
import com.waenhancer.ui.components.WaexSearchBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.Screen
import com.waenhancer.utils.LocalizationHelper
import com.waenhancer.utils.UiText

typealias SearchablePreference = FeatureItem

private val preferenceSearchAliases = mapOf(
    "chat_filters" to "chat search display search bar search icon hide search no search chatfilter",
    "anti_revoke" to "show as deleted native deleted message layout"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen() {
    val context = LocalContext.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val navController = LocalWaexNavController.current

    var query by remember { mutableStateOf("") }
    val preferenceRegistry = FeatureCatalog.ALL_FEATURES
    val filteredPreferences = remember(query, context) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            preferenceRegistry
        } else {
            val terms = trimmed.lowercase().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            preferenceRegistry.filter { preference ->
                val haystack = buildString {
                    append(LocalizationHelper.getTitle(context, preference))
                    append(' ')
                    append(LocalizationHelper.getDesc(context, preference))
                    append(' ')
                    append(LocalizationHelper.getSection(context, preference))
                    append(' ')
                    append(LocalizationHelper.getTabTitle(context, preference.tabId))
                    append(' ')
                    append(preference.key)
                    append(' ')
                    append(preferenceSearchAliases[preference.key].orEmpty())
                }.lowercase()
                terms.all(haystack::contains)
            }
        }
    }
    val groupedPreferences = remember(filteredPreferences, context) {
        filteredPreferences.groupBy { LocalizationHelper.getSection(context, it) }
    }

    Scaffold(
        topBar = {
            Surface(color = colors.background, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .padding(horizontal = spacing.pageMargin),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = navController::popBack,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(colors.surfaceContainerHigh)
                        ) {
                            Icon(
                                imageVector = WaexIcons.Back,
                                contentDescription = UiText.get(R.string.action_back, "Back"),
                                tint = colors.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        WaexSearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = stringResource(R.string.search_placeholder),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        containerColor = colors.background
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            if (filteredPreferences.isEmpty()) {
                WaexEmptyState(
                    title = stringResource(R.string.no_results_found),
                    description = stringResource(R.string.no_results_description),
                    icon = WaexIcons.Search,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    groupedPreferences.forEach { (section, preferences) ->
                        stickyHeader(key = "section_$section") {
                            SearchSectionHeader(section)
                        }
                        items(preferences, key = { it.key }) { preference ->
                            SearchResultRow(
                                preference = preference,
                                onClick = {
                                    if (
                                        preference.key == "pro_features_unlock" ||
                                        preference.key == "license_verification"
                                    ) {
                                        navController.navigateTo(Screen.LicenseActivation)
                                    } else {
                                        navController.navigateToPreference(
                                            tabIndex = preference.tabIndex,
                                            subTabId = preference.subTabId,
                                            preferenceKey = preference.key
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(section: String) {
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .padding(top = 12.dp)
    ) {
        Text(
            text = section,
            style = typography.labelLg,
            fontWeight = FontWeight.SemiBold,
            color = colors.primary,
            modifier = Modifier.padding(
                start = spacing.pageMargin,
                end = spacing.pageMargin,
                bottom = 10.dp
            )
        )
        HorizontalDivider(
            color = colors.outlineVariant,
            thickness = 1.dp,
            modifier = Modifier.padding(horizontal = spacing.pageMargin)
        )
    }
}

@Composable
private fun SearchResultRow(
    preference: SearchablePreference,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = spacing.pageMargin)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 15.dp, end = spacing.pageMargin, bottom = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = LocalizationHelper.getTitle(context, preference),
                    style = typography.titleMd,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = LocalizationHelper.getDesc(context, preference),
                    style = typography.bodyMd,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Surface(
                color = colors.primaryContainer,
                contentColor = colors.primary,
                shape = radius.fullShape,
                modifier = Modifier.widthIn(max = 96.dp)
            ) {
                Text(
                    text = LocalizationHelper.getTabTitle(context, preference.tabId),
                    style = typography.labelSm,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = WaexIcons.ChevronRight,
                contentDescription = null,
                tint = colors.outline,
                modifier = Modifier.size(18.dp)
            )
        }
        HorizontalDivider(color = colors.outlineVariant, thickness = 1.dp)
    }
}
