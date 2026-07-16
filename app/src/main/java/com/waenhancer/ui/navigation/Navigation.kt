package com.waenhancer.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

sealed class Screen {
    object MainDashboard : Screen()
}

class WaexNavController {
    fun navigateTo(screen: Screen) {}
    fun popBack() {}
}

val LocalWaexNavController = staticCompositionLocalOf { WaexNavController() }

@Composable
fun MainDashboardScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("WA Enhancer X - Main Dashboard Scaffold")
    }
}
