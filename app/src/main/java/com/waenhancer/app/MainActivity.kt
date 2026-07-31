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

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var compatibilityProvider: WaexCompatibilityProvider
    @Inject lateinit var versionManager: WaexVersionManager
    @Inject lateinit var preferenceManager: WaexPreferenceManager
    @Inject lateinit var licenseManager: WaexLicenseManager
    @Inject lateinit var clientDetector: WaexClientDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WaexTheme {
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
                        LocalWaexLicenseManager provides licenseManager,
                        LocalWaexClientDetector provides clientDetector
                    ) {
                        MainContainerScreen()
                    }
                }
            }
        }
    }
}
