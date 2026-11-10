package com.waenhancer.ui.screens.dashboard

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import android.content.Context
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.utils.InjectedHookItem
import com.waenhancer.utils.WhatsAppPackageDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.waenhancer.utils.InternalLabels

@Composable
fun InjectedHooksScreen(
    packageName: String,
    appName: String
) {
    val context = LocalContext.current
    val navController = LocalWaexNavController.current
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val spacing = WaexTheme.spacing

    var refreshTrigger by remember { mutableIntStateOf(0) }
    var hooksList by remember { mutableStateOf<List<InjectedHookItem>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }

    LaunchedEffect(packageName, refreshTrigger) {
        withContext(Dispatchers.IO) {
            val list = WhatsAppPackageDetector.getInjectedHooks(context, packageName)
            withContext(Dispatchers.Main) {
                hooksList = list
            }
        }
    }

    val categories = remember(hooksList) {
        listOf("All") + hooksList.map { it.category }.distinct().sorted()
    }

    val filteredHooks = remember(searchQuery, selectedCategory, hooksList) {
        hooksList.filter { hook ->
            val matchesCategory = (selectedCategory == "All" || hook.category.equals(selectedCategory, ignoreCase = true))
            val matchesSearch = searchQuery.isBlank() ||
                    hook.name.contains(searchQuery, ignoreCase = true) ||
                    InternalLabels.text(hook.name).contains(searchQuery, ignoreCase = true) ||
                    InternalLabels.text(hook.category).contains(searchQuery, ignoreCase = true) ||
                    hook.className.contains(searchQuery, ignoreCase = true) ||
                    hook.category.contains(searchQuery, ignoreCase = true)
            matchesCategory && matchesSearch
        }
    }

    val activeCount = hooksList.count { it.status == "ACTIVE" }
    val failedCount = hooksList.count { it.status == "FAILED" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // Top App Bar
        WaexTopBar(
            title = UiText.get(R.string.waex_ui_injected_hooks, "Injected Hooks"),
            onBackClick = { navController.popBack() },
            actions = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(radius.smShape)
                            .background(colors.primaryContainer)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = UiText.get(R.string.waex_ui_1_s_active, "%1\$s Active", activeCount),
                            style = typography.labelSm,
                            color = colors.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                    if (failedCount > 0) {
                        Box(
                            modifier = Modifier
                                .clip(radius.smShape)
                                .background(colors.errorContainer)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = UiText.get(R.string.waex_ui_1_s_failed, "%1\$s Failed", failedCount),
                                style = typography.labelSm,
                                color = colors.error,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = spacing.pageMargin),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
        ) {
            // Target App Subtitle Info Card
            item(key = "app_info") {
                Surface(
                    shape = radius.defaultShape,
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = appName,
                                style = typography.bodyMd,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface
                            )
                            Text(
                                text = packageName,
                                style = typography.labelSm,
                                color = colors.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(colors.surfaceDim)
                                .border(1.dp, colors.outlineVariant, CircleShape)
                                .clickable { refreshTrigger++ },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = WaexIcons.Refresh,
                                contentDescription = UiText.get(R.string.waex_ui_refresh_hooks, "Refresh hooks"),
                                tint = colors.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }

            // Search Bar
            item(key = "search") {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = UiText.get(R.string.waex_ui_search_injected_hooks_classes, "Search injected hooks & classes..."),
                            style = typography.bodyMd,
                            color = colors.onSurfaceVariant
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = WaexIcons.Search,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            Icon(
                                imageVector = WaexIcons.Clear,
                                contentDescription = UiText.get(R.string.waex_ui_clear_search, "Clear search"),
                                tint = colors.onSurfaceVariant,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { searchQuery = "" }
                            )
                        }
                    },
                    singleLine = true,
                    shape = radius.defaultShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surfaceDim,
                        unfocusedContainerColor = colors.surfaceDim,
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.outlineVariant
                    )
                )
            }

            // Category Filter Chips
            item(key = "categories") {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(categories) { category ->
                        val isSelected = (selectedCategory == category)
                        Box(
                            modifier = Modifier
                                .clip(radius.smShape)
                                .background(if (isSelected) colors.primary else colors.surfaceDim)
                                .border(
                                    1.dp,
                                    if (isSelected) colors.primary else colors.outlineVariant,
                                    radius.smShape
                                )
                                .clickable { selectedCategory = category }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = InternalLabels.text(category),
                                style = typography.labelSm,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) colors.onPrimary else colors.onSurface,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            item(key = "divider") {
                HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)
            }

            // Hooks List
            if (filteredHooks.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (hooksList.isEmpty()) UiText.get(R.string.waex_ui_no_active_hooks_reported_by_whatsapp_p, "No active hooks reported by WhatsApp process.\nEnsure WhatsApp is running.") else UiText.get(R.string.waex_ui_no_injected_hooks_matching_filter, "No injected hooks matching filter"),
                            style = typography.bodyMd,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(
                    items = filteredHooks,
                    key = { hook -> "${hook.className}:${hook.name}:${hook.category}" }
                ) { hook ->
                    InjectedHookRow(hook = hook)
                }
            }
        }
    }
}
