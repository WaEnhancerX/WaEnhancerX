package com.waenhancer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.LaunchedEffect
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.designsystem.LocalThemeMode
import com.waenhancer.ui.designsystem.LocalThemeModeUpdater
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.MainContainerScreen
import com.waenhancer.ui.navigation.WaexNavController
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var themeMode by rememberSaveable { mutableStateOf("System") }
            val isDark = when (themeMode) {
                "Light" -> false
                "Dark" -> true
                else -> isSystemInDarkTheme()
            }

            LaunchedEffect(isDark) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    },
                    navigationBarStyle = if (isDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    }
                )
            }

            CompositionLocalProvider(
                LocalThemeMode provides themeMode,
                LocalThemeModeUpdater provides { mode -> themeMode = mode }
            ) {
                WaexTheme(darkTheme = isDark) {
                    val navController = remember { WaexNavController() }
                    CompositionLocalProvider(LocalWaexNavController provides navController) {
                        MainContainerScreen()
                    }
                }
            }
        }
    }
}
