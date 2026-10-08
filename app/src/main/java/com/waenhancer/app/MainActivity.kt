package com.waenhancer.app

import android.content.Intent
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
import com.waenhancer.core.preferences.WaexPreferenceManager
import com.waenhancer.licensing.LicenseManager
import com.waenhancer.ui.designsystem.LocalThemeMode
import com.waenhancer.ui.designsystem.LocalThemeModeUpdater
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import com.waenhancer.ui.navigation.MainContainerScreen
import com.waenhancer.ui.navigation.WaexNavController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var preferenceManager: WaexPreferenceManager


    private var currentNavController: WaexNavController? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val releaseTag = releaseTagFrom(intent)
        if (releaseTag != null) {
            currentNavController?.navigateTo(com.waenhancer.ui.navigation.Screen.ReleaseDetails(releaseTag))
        } else if (intent.getStringExtra("target_screen") == "supported_versions") {
            currentNavController?.navigateTo(com.waenhancer.ui.navigation.Screen.SupportedVersions)
        }
    }

    override fun onResume() {
        super.onResume()
        if ("ACTIVE".equals(LicenseManager.getProStatus(this), ignoreCase = true)) {
            val wasPro = true
            LicenseManager.silentCheck(this) {
                val isProNow = "ACTIVE".equals(
                    LicenseManager.getProStatus(this),
                    ignoreCase = true
                )
                if (wasPro != isProNow && !isFinishing && !isDestroyed) recreate()
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val initialTheme = remember { preferenceManager.getString("app_theme_mode", "System") }
            var themeMode by remember { mutableStateOf(initialTheme) }
            val isDark = when (themeMode) {
                "Dark" -> true
                "Light" -> false
                else -> isSystemInDarkTheme()
            }
            CompositionLocalProvider(
                LocalThemeMode provides themeMode,
                LocalThemeModeUpdater provides {
                    themeMode = it
                    preferenceManager.putString("app_theme_mode", it)
                }
            ) {
                WaexTheme(darkTheme = isDark) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        val navController = remember {
                            val controller = WaexNavController()
                            currentNavController = controller
                            val releaseTag = releaseTagFrom(intent)
                            if (releaseTag != null) {
                                controller.navigateTo(com.waenhancer.ui.navigation.Screen.ReleaseDetails(releaseTag))
                            } else if (intent?.getStringExtra("target_screen") == "supported_versions") {
                                controller.navigateTo(com.waenhancer.ui.navigation.Screen.SupportedVersions)
                            }
                            controller
                        }
                        CompositionLocalProvider(
                            LocalWaexNavController provides navController,
                            LocalWaexPreferenceManager provides preferenceManager
                        ) {
                            MainContainerScreen()
                        }


                    }
                }
            }
        }
    }

    private fun releaseTagFrom(intent: Intent?): String? {
        val path = intent?.data?.path ?: return intent?.getStringExtra("release_tag")
        if (!path.startsWith("/releases/")) return null
        return path.removePrefix("/releases/").trim('/').takeIf { it.isNotBlank() }
    }
}
