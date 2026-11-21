package com.waenhancer.fixture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import com.waenhancer.app.R
import com.waenhancer.ui.designsystem.WaexIcons
import java.util.Locale

// Only the surrounding manager data, navigation and string-resource lookup are
// fixtures. The staged layout expressions, Compose Text and measure policies are
// production code. Typeface files and label styles come from this checkout.
internal val LocalLabels = staticCompositionLocalOf { emptyMap<Int, String>() }
@Composable
internal fun stringResource(id: Int, vararg arguments: Any): String =
    LocalLabels.current.getValue(id).let {
        if (arguments.isEmpty()) it else String.format(Locale.ROOT, it, *arguments)
    }

internal sealed class Screen {
    data object MainDashboard : Screen()
    data object GlobalPrivacySettings : Screen()
    data object MediaStatusHub : Screen()
    data object AutomationTasker : Screen()
    data object StylesSettings : Screen()
    data class InjectedHooks(val packageName: String, val appName: String) : Screen()
}

internal enum class BottomTab(val labelRes: Int, val icon: ImageVector) {
    HOME(R.string.nav_home, WaexIcons.GridView),
    PRIVACY(R.string.nav_controls, WaexIcons.Security),
    MEDIA(R.string.nav_media, WaexIcons.Image),
    AUTOMATION(R.string.nav_automation, WaexIcons.AutoAwesome),
    STYLES(R.string.nav_styles, WaexIcons.Palette)
}

internal class FixtureNavigation {
    var screen: Screen? = null
    fun navigateTo(target: Screen) { screen = target }
}

internal class FixturePager {
    var page: Int? = null
    val currentPage: Int get() = page ?: 0
    suspend fun animateScrollToPage(target: Int) { page = target }
}

internal class FixtureApp {
    val packageName = "com.whatsapp"
    val appName = "WhatsApp"
    val isHooked = true
}

internal object UiText {
    @Composable
    fun get(id: Int, fallback: String, vararg arguments: Any): String =
        stringResource(id, *arguments)
}

internal object TargetAppManager {
    val requests = mutableListOf<List<String>>()
    fun syncHooks(context: android.content.Context, packages: List<String>) {
        check(packages == listOf("com.whatsapp")) { "Unexpected target in layout fixture" }
        requests += packages.toList()
    }
}
