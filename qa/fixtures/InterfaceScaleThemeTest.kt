package com.waenhancer.fixture

import android.content.res.Configuration
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import com.waenhancer.ui.designsystem.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private class ThemeApplier : AbstractApplier<Unit>(Unit) {
    override fun insertBottomUp(index: Int, instance: Unit) {}
    override fun insertTopDown(index: Int, instance: Unit) {}
    override fun remove(index: Int, count: Int) {}
    override fun move(from: Int, to: Int, count: Int) {}
    override fun onClear() {}
}

private data class ThemeSample(
    val density: Density,
    val typography: WaexTypography,
    val materialTypography: Typography,
    val spacing: WaexSpacing,
    val radius: WaexRadius
) {
    fun dp(value: Dp): Float = with(density) { value.toPx() }
    fun sp(value: TextUnit): Float = with(density) { value.toPx() }
    fun styles(): List<TextStyle> = typography.run {
        listOf(displayLg, displayLgMobile, headlineMd, titleLg, titleMd,
            bodyLg, bodyMd, bodySm, labelLg, labelSm)
    } + materialTypography.run {
        listOf(displayLarge, displayMedium, displaySmall, headlineLarge, headlineMedium,
            headlineSmall, titleLarge, titleMedium, titleSmall, bodyLarge, bodyMedium,
            bodySmall, labelLarge, labelMedium, labelSmall)
    }
    fun gaps(): List<Dp> = spacing.run {
        listOf(pageMargin, gutter, bentoGap, stackLg, stackMd, stackSm)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class ThemeProbe(private val scope: TestScope) {
    private val context = RuntimeEnvironment.getApplication()
    private val view = View(context)
    private val size = mutableStateOf(InterfaceSize.COMPACT)
    private val systemDensity = mutableStateOf<Density>(Density(2.75f, 1f))
    private val frameClock = BroadcastFrameClock()
    private val recomposer = Recomposer(scope.coroutineContext + frameClock)
    private val loop = scope.launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
    private val composition = Composition(ThemeApplier(), recomposer)
    private var sample: ThemeSample? = null
    private var frame = 0L

    init {
        Dispatchers.setMain(StandardTestDispatcher(scope.testScheduler))
        composition.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalView provides view,
                LocalConfiguration provides context.resources.configuration,
                LocalDensity provides systemDensity.value
            ) {
                // The complete production theme, tokens and MaterialTheme execute here.
                WaexTheme(darkTheme = false, useMonet = false, interfaceSize = size.value) {
                    val current = ThemeSample(LocalDensity.current, WaexTheme.typography,
                        MaterialTheme.typography, WaexTheme.spacing, WaexTheme.radius)
                    SideEffect { sample = current }
                }
            }
        }
    }

    fun measure(selected: InterfaceSize, density: Density): ThemeSample {
        size.value = selected
        systemDensity.value = density
        Snapshot.sendApplyNotifications()
        repeat(3) {
            scope.runCurrent()
            Snapshot.sendApplyNotifications()
            frameClock.sendFrame(++frame * 16_000_000L)
            scope.runCurrent()
            Snapshot.sendApplyNotifications()
        }
        return requireNotNull(sample) { "Production theme did not compose" }
    }

    suspend fun close() {
        composition.dispose()
        recomposer.cancel()
        loop.cancelAndJoin()
        Dispatchers.resetMain()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28, 34], manifest = Config.NONE)
class InterfaceScaleThemeTest {
    private fun near(label: String, expected: Float, actual: Float) {
        assertEquals(label, expected, actual, 0.001f)
    }

    @Test fun spacingAndFixedControlsScaleByTheSameFactor() = runTest {
        val probe = ThemeProbe(this)
        try {
            for (density in listOf(2.75f, 1f, 3.5f)) {
                val base = Density(density, 1f)
                val compact = probe.measure(InterfaceSize.COMPACT, base)
                val samples = InterfaceSize.entries.map { it to probe.measure(it, base) }
                if (density == 2.75f) {
                    println("UI_SCALE_SPACING " + samples.joinToString { (size, sample) ->
                        "${size.storageValue}:margin=${sample.dp(sample.spacing.pageMargin)}:fixed16=${sample.dp(16.dp)}"
                    })
                }
                for ((size, sample) in samples) {
                    near("${size.storageValue} screen density", base.density * size.scale, sample.density.density)
                    near("${size.storageValue} fixed control", compact.dp(48.dp) * size.scale, sample.dp(48.dp))
                    near("${size.storageValue} corner radius", compact.dp(compact.radius.card) * size.scale,
                        sample.dp(sample.radius.card))
                    compact.gaps().zip(sample.gaps()).forEachIndexed { index, (before, after) ->
                        near("${size.storageValue} spacing $index", compact.dp(before) * size.scale, sample.dp(after))
                    }
                }
            }
        } finally { probe.close() }
    }

    @Test fun allWaexAndMaterialTextRolesScaleOnce() = runTest {
        val probe = ThemeProbe(this)
        try {
            val base = Density(2.75f, 1f)
            val compact = probe.measure(InterfaceSize.COMPACT, base)
            val samples = InterfaceSize.entries.map { it to probe.measure(it, base) }
            println("UI_SCALE_BODY " + samples.joinToString { (size, sample) ->
                "${size.storageValue}:waex=${sample.sp(sample.typography.bodyMd.fontSize)}:material=${sample.sp(sample.materialTypography.bodyMedium.fontSize)}"
            })
            assertEquals(25, compact.styles().size)
            for ((size, sample) in samples) {
                compact.styles().zip(sample.styles()).forEachIndexed { index, (before, after) ->
                    near("${size.storageValue} role $index font", compact.sp(before.fontSize) * size.scale, sample.sp(after.fontSize))
                    near("${size.storageValue} role $index line", compact.sp(before.lineHeight) * size.scale, sample.sp(after.lineHeight))
                    near("${size.storageValue} role $index tracking", compact.sp(before.letterSpacing) * size.scale, sample.sp(after.letterSpacing))
                }
            }
        } finally { probe.close() }
    }

    @Test fun accessibilityFontScaleAndNonlinearConversionArePreserved() = runTest {
        val probe = ThemeProbe(this)
        try {
            val app = RuntimeEnvironment.getApplication()
            for (fontScale in listOf(1f, 1.3f, 2f)) {
                val configuration = Configuration(app.resources.configuration).apply { this.fontScale = fontScale }
                val base = Density(app.createConfigurationContext(configuration))
                val compact = probe.measure(InterfaceSize.COMPACT, base)
                for (size in InterfaceSize.entries) {
                    val sample = probe.measure(size, base)
                    near("fontScale was replaced", base.fontScale, sample.density.fontScale)
                    // The public Android Density(Context) is the oracle, including its
                    // cached nonlinear font converter. No linear fontScale formula is assumed.
                    for (text in listOf(8.sp, 14.sp, 24.sp, 57.sp, 72.sp)) {
                        val expected = with(base) { text.toPx() } * size.scale
                        near("$fontScale/${size.storageValue} direct $text", expected, sample.sp(text))
                    }
                    compact.styles().zip(sample.styles()).forEachIndexed { index, (before, after) ->
                        near("$fontScale/${size.storageValue} role $index", compact.sp(before.fontSize) * size.scale,
                            sample.sp(after.fontSize))
                    }
                }
            }
        } finally { probe.close() }
    }

    @Test fun changingDensityFontScaleAndSizeDoesNotRetainPreviousMeasurements() = runTest {
        val probe = ThemeProbe(this)
        try {
            val observations = (0 until 24).map { step ->
                val size = InterfaceSize.entries[step % InterfaceSize.entries.size]
                val base = Density(listOf(1f, 2.75f, 3.5f)[step % 3], listOf(1f, 1.3f, 2f)[(step / 3) % 3])
                Triple(size, base, probe.measure(size, base))
            }
            for ((size, base, sample) in observations) {
                near("latest density", base.density * size.scale, sample.density.density)
                near("latest font scale", base.fontScale, sample.density.fontScale)
                near("latest page margin", with(base) { 16.dp.toPx() } * size.scale,
                    sample.dp(sample.spacing.pageMargin))
                near("latest body size", with(base) { 14.sp.toPx() } * size.scale,
                    sample.sp(sample.typography.bodyMd.fontSize))
            }
            val reset = probe.measure(InterfaceSize.COMPACT, Density(2.75f, 1f))
            near("compact reset margin", 44f, reset.dp(reset.spacing.pageMargin))
            near("compact reset font", 38.5f, reset.sp(reset.typography.bodyMd.fontSize))
        } finally { probe.close() }
    }
}
