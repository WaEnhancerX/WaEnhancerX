package com.waenhancer.app.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.waenhancer.feature.auth.AuthScreen
import com.waenhancer.feature.auth.AuthViewModel
import com.waenhancer.feature.home.HomeScreen
import com.waenhancer.feature.home.HomeViewModel
import com.waenhancer.feature.settings.SettingsScreen
import com.waenhancer.feature.settings.SettingsViewModel
import com.waenhancer.feature.tools.ToolsScreen
import com.waenhancer.feature.tools.ToolsViewModel

@Composable
fun MainNavigation() {
    val backStack = rememberNavBackStack(Auth)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Auth> {
                val viewModel: AuthViewModel = hiltViewModel()
                AuthScreen(
                    viewModel = viewModel,
                    onAuthSuccess = {
                        backStack.add(Home)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            entry<Home> {
                val viewModel: HomeViewModel = hiltViewModel()
                HomeScreen(
                    viewModel = viewModel,
                    onNavigateToTools = { backStack.add(Tools) },
                    onNavigateToSettings = { backStack.add(Settings) },
                    modifier = Modifier.fillMaxSize()
                )
            }
            entry<Tools> {
                val viewModel: ToolsViewModel = hiltViewModel()
                ToolsScreen(
                    viewModel = viewModel,
                    onBack = { backStack.removeLastOrNull() },
                    modifier = Modifier.fillMaxSize()
                )
            }
            entry<Settings> {
                val viewModel: SettingsViewModel = hiltViewModel()
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { backStack.removeLastOrNull() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    )
}
