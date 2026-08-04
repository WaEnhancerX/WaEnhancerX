package com.waenhancer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.waenhancer.ui.designsystem.WaexTheme
import javax.inject.Inject
import com.waenhancer.api.contracts.*
import com.waenhancer.ui.navigation.*
import dagger.hilt.android.AndroidEntryPoint
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.waenhancer.ui.designsystem.LocalThemeMode
import com.waenhancer.ui.designsystem.LocalThemeModeUpdater

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var compatibilityProvider: WaexCompatibilityProvider
    @Inject lateinit var versionManager: WaexVersionManager
    @Inject lateinit var preferenceManager: WaexPreferenceManager
    @Inject lateinit var preferenceRepository: WaexPreferenceRepository
    @Inject lateinit var licenseManager: WaexLicenseManager
    @Inject lateinit var clientDetector: WaexClientDetector
    @Inject lateinit var featureExecutor: WaexFeatureExecutor

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
                            LocalWaexCompatibilityProvider provides compatibilityProvider,
                            LocalWaexVersionManager provides versionManager,
                            LocalWaexPreferenceManager provides preferenceManager,
                            LocalWaexPreferenceRepository provides preferenceRepository,
                            LocalWaexLicenseManager provides licenseManager,
                            LocalWaexClientDetector provides clientDetector,
                            LocalWaexFeatureExecutor provides featureExecutor
                        ) {
                            MainContainerScreen()
                        }
                    }
                }
            }
        }
    }
}
