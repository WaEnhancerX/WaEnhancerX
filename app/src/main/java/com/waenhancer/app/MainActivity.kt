package com.waenhancer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.waenhancer.api.contracts.WaexLicenseManager
import com.waenhancer.api.contracts.WaexPreferenceManager
import com.waenhancer.api.contracts.WaexPreferenceRepository
import com.waenhancer.ui.designsystem.LocalThemeMode
import com.waenhancer.ui.designsystem.LocalThemeModeUpdater
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexLicenseManager
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import com.waenhancer.ui.navigation.LocalWaexPreferenceRepository
import com.waenhancer.ui.navigation.MainContainerScreen
import com.waenhancer.ui.navigation.WaexNavController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var preferenceManager: WaexPreferenceManager
    @Inject lateinit var preferenceRepository: WaexPreferenceRepository
    @Inject lateinit var licenseManager: WaexLicenseManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var themeMode by remember { mutableStateOf("System") }
            val isDark = when (themeMode) {
                "Dark" -> true
                "Light" -> false
                else -> isSystemInDarkTheme()
            }
            CompositionLocalProvider(
                LocalThemeMode provides themeMode,
                LocalThemeModeUpdater provides { themeMode = it }
            ) {
                WaexTheme(darkTheme = isDark) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        val navController = remember { WaexNavController() }
                        CompositionLocalProvider(
                            LocalWaexNavController provides navController,
                            LocalWaexPreferenceManager provides preferenceManager,
                            LocalWaexPreferenceRepository provides preferenceRepository,
                            LocalWaexLicenseManager provides licenseManager
                        ) {
                            MainContainerScreen()
                        }
                    }
                }
            }
        }
    }
}
