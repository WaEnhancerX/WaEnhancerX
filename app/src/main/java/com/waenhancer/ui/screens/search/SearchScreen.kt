package com.waenhancer.ui.screens.search

import com.waenhancer.utils.UiText

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.config.FeatureCatalog
import com.waenhancer.config.FeatureItem
import com.waenhancer.ui.components.WaexSearchBar
import com.waenhancer.ui.components.WaexEmptyState
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.utils.LocalizationHelper

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.waenhancer.app.R
import androidx.compose.foundation.BorderStroke
import com.waenhancer.ui.navigation.Screen

typealias SearchablePreference = FeatureItem

private val preferenceSearchAliases = mapOf(
    "chat_filters" to "chat search display search bar search icon hide search no search chatfilter"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen() {
    val context = LocalContext.current
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val navController = LocalWaexNavController.current

    var query by remember { mutableStateOf("") }

    // Registry of all available preference items sourced from the unified FeatureCatalog
    val preferenceRegistry = FeatureCatalog.ALL_FEATURES

    // Enhanced multi-token search filter: matches all query terms across localized title, desc, section, key
    val filteredPreferences = remember(query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            preferenceRegistry
        } else {
            val terms = trimmed.lowercase().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            preferenceRegistry.filter { pref ->
                val locTitle = LocalizationHelper.getTitle(context, pref)
                val locDesc = LocalizationHelper.getDesc(context, pref)
                val locSec = LocalizationHelper.getSection(context, pref)
                val locTab = LocalizationHelper.getTabTitle(context, pref.tabId)
                val aliases = preferenceSearchAliases[pref.key].orEmpty()
                val haystack = "$locTitle $locDesc $locSec $locTab ${pref.key} $aliases".lowercase()
                terms.all { term -> haystack.contains(term) }
            }
        }
    }

    val groupedPreferences = remember(filteredPreferences) {
        filteredPreferences.groupBy { LocalizationHelper.getSection(context, it) }
    }

    Scaffold(
        topBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colors.surface
            ) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = spacing.pageMargin),
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        IconButton(
                            onClick = { navController.popBack() },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                        ) {
                            Icon(
                                imageVector = WaexIcons.Back,
                                contentDescription = UiText.get(R.string.action_back, "Back"),
                                tint = colors.onBackground,
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
                    HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = 0.6f))
                }
            }
        },

        containerColor = colors.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
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
                    contentPadding = PaddingValues(horizontal = spacing.pageMargin, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    groupedPreferences.forEach { (section, preferences) ->
                        item(key = "section_$section") {
                            Text(
                                text = section,
                                style = typography.bodyLg,
                                fontWeight = FontWeight.Bold,
                                color = colors.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                        }
                        items(preferences, key = { it.key }) { pref ->
                            // Custom badge color per tab category
                            val (badgeBg, badgeTextColor) = when (pref.tabId) {
                                "privacy" -> Color(0xFF1E88E5).copy(alpha = 0.12f) to Color(0xFF1E88E5)
                                "conversation" -> Color(0xFF8E24AA).copy(alpha = 0.12f) to Color(0xFF8E24AA)
                                "media" -> Color(0xFFFB8C00).copy(alpha = 0.12f) to Color(0xFFFB8C00)
                                "automation" -> Color(0xFF43A047).copy(alpha = 0.12f) to Color(0xFF43A047)
                                "styles" -> Color(0xFF00ACC1).copy(alpha = 0.12f) to Color(0xFF00ACC1)
                                "premium" -> Color(0xFFE53935).copy(alpha = 0.12f) to Color(0xFFE53935)
                                else -> colors.primaryContainer to colors.primary
                            }

                            Surface(
                                shape = radius.bentoCardShape,
                                color = colors.surfaceDim,
                                border = BorderStroke(1.dp, colors.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (pref.key == "pro_features_unlock" || pref.key == "license_verification") {
                                                navController.navigateTo(Screen.LicenseActivation)
                                            } else {
                                                navController.navigateToPreference(
                                                    tabIndex = pref.tabIndex,
                                                    subTabId = pref.subTabId,
                                                    preferenceKey = pref.key
                                                )
                                            }
                                        }
                                        .padding(16.dp)
                                ) {
                                    // Floating Tag Badge on top-right of the card
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(badgeBg)
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = LocalizationHelper.getTabTitle(context, pref.tabId),
                                            style = typography.labelSm,
                                            fontWeight = FontWeight.Bold,
                                            color = badgeTextColor,
                                            fontSize = 10.sp
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 64.dp), // Prevent text overlapping the top-right badge
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = LocalizationHelper.getTitle(context, pref),
                                                style = typography.bodyLg,
                                                fontWeight = FontWeight.SemiBold,
                                                color = colors.onSurface
                                            )
                                            Text(
                                                text = LocalizationHelper.getDesc(context, pref),
                                                style = typography.bodyMd,
                                                color = colors.onSurfaceVariant,
                                                fontSize = 12.sp,
                                                lineHeight = 17.sp
                                            )
                                            Text(
                                                text = UiText.get(R.string.waex_ui_section_1_s, "Section: %1\$s", LocalizationHelper.getSection(context, pref)),
                                                style = typography.labelSm,
                                                color = colors.primary,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }

                                        Icon(
                                            imageVector = WaexIcons.ChevronRight,
                                            contentDescription = null,
                                            tint = colors.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.size(16.dp)
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
