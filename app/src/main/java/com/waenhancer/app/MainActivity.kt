package com.waenhancer.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.waenhancer.core.preferences.WaexPreferenceManager
import com.waenhancer.licensing.LicenseManager
import com.waenhancer.ui.designsystem.LocalThemeMode
import com.waenhancer.ui.designsystem.LocalThemeModeUpdater
import com.waenhancer.ui.designsystem.InterfaceSize
import com.waenhancer.ui.designsystem.LocalInterfaceSize
import com.waenhancer.ui.designsystem.LocalInterfaceSizeUpdater
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import com.waenhancer.ui.navigation.MainContainerScreen
import com.waenhancer.ui.navigation.WaexNavController
import com.waenhancer.ui.components.GlobalNetworkErrorHost
import com.waenhancer.update.UpdateCheckCoordinator
import com.waenhancer.update.UpdateDownloader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import android.content.Context
import com.waenhancer.ui.navigation.Screen
import com.waenhancer.utils.LocaleHelper

internal object StartupRootAccess {
    @Volatile private var pendingResult: Boolean? = null

    fun publish(granted: Boolean) {
        pendingResult = granted
    }

    @Synchronized
    fun consume(): Boolean? = pendingResult.also { pendingResult = null }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var preferenceManager: WaexPreferenceManager

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    private var currentNavController: WaexNavController? = null
    private var rootCheckAttempt by mutableIntStateOf(0)
    private var rootState by mutableStateOf(RootAccessState.Checking)
    private var hasGrantedRootThisSession by mutableStateOf(false)
    private var initialRootResultProvided = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val releaseTag = releaseTagFrom(intent)
        if (releaseTag != null) {
            currentNavController?.navigateTo(Screen.ReleaseDetails(releaseTag))
        } else if (intent.getStringExtra("target_screen") == "supported_versions") {
            currentNavController?.navigateTo(Screen.SupportedVersions)
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
        val initialRootResult = StartupRootAccess.consume()
        initialRootResultProvided = initialRootResult != null
        if (initialRootResult != null) {
            hasGrantedRootThisSession = initialRootResult
            rootState = if (hasGrantedRootThisSession) RootAccessState.Granted else RootAccessState.Required
        }
        enableEdgeToEdge()
        setContent {
            val initialTheme = remember { preferenceManager.getString("app_theme_mode", "System") }
            var themeMode by remember { mutableStateOf(initialTheme) }
            val initialInterfaceSize = remember {
                InterfaceSize.fromStorage(preferenceManager.getString("app_interface_size", "Compact"))
            }
            var interfaceSize by remember { mutableStateOf(initialInterfaceSize) }
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
                },
                LocalInterfaceSize provides interfaceSize,
                LocalInterfaceSizeUpdater provides {
                    interfaceSize = it
                    preferenceManager.putString("app_interface_size", it.storageValue)
                }
            ) {
                WaexTheme(
                    darkTheme = isDark,
                    useMonet = themeMode == "Monet",
                    interfaceSize = interfaceSize
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        val navController = remember {
                            val controller = WaexNavController()
                            currentNavController = controller
                            val releaseTag = releaseTagFrom(intent)
                            if (releaseTag != null) {
                                controller.navigateTo(Screen.ReleaseDetails(releaseTag))
                            } else if (intent?.getStringExtra("target_screen") == "supported_versions") {
                                controller.navigateTo(Screen.SupportedVersions)
                            }
                            controller
                        }

                        LaunchedEffect(rootCheckAttempt) {
                            if (initialRootResultProvided && rootCheckAttempt == 0) return@LaunchedEffect
                            rootState = RootAccessState.Checking
                            val hasRoot = withContext(Dispatchers.IO) {
                                UpdateDownloader.hasRootAccess()
                            }
                            rootState = if (hasRoot) {
                                hasGrantedRootThisSession = true
                                RootAccessState.Granted
                            } else {
                                RootAccessState.Required
                            }
                        }

                        LaunchedEffect(hasGrantedRootThisSession) {
                            if (hasGrantedRootThisSession) {
                                UpdateCheckCoordinator.request(
                                    this@MainActivity,
                                    UpdateCheckCoordinator.TRIGGER_MODULE
                                )
                            }
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            CompositionLocalProvider(
                                LocalWaexNavController provides navController,
                                LocalWaexPreferenceManager provides preferenceManager
                            ) {
                                if (hasGrantedRootThisSession) {
                                    MainContainerScreen()
                                }
                            }
                            if (rootState != RootAccessState.Granted) {
                                RootAccessRequirementScreen(
                                    state = rootState,
                                    onRetry = { rootCheckAttempt++ },
                                    onExit = { finishAndRemoveTask() }
                                )
                            }
                            GlobalNetworkErrorHost()
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
