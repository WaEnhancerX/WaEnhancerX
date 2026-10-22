package com.waenhancer.fixture

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.waenhancer.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private class LayoutProbe(
    width: Int,
    fontScale: Float,
    language: String = "es",
    direction: LayoutDirection = LayoutDirection.Ltr,
    content: @Composable () -> Unit
) : AutoCloseable {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
    private val view = ComposeView(controller.get())
    val nodes: List<SemanticsNode>

    init {
        controller.get().setContentView(view)
        view.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale),
                LocalLayoutDirection provides direction,
                LocalLabels provides FixtureLabels.forLanguage(language)
            ) {
                MaterialTheme { content() }
            }
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val ownerView = findOwner(view) ?: error("No real AndroidComposeView was created")
        val owner = ownerView.javaClass.getMethod("getSemanticsOwner").invoke(ownerView) as SemanticsOwner
        nodes = buildList { collect(owner.unmergedRootSemanticsNode, this) }
    }

    private fun findOwner(view: View): View? {
        if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findOwner(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun collect(node: SemanticsNode, result: MutableList<SemanticsNode>) {
        result += node
        node.children.forEach { collect(it, result) }
    }

    fun text(value: String): SemanticsNode = nodes.single {
        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
    }

    fun layout(node: SemanticsNode): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
        assertTrue("The actual Compose text layout must be queryable", action?.invoke(results) == true)
        return results.single()
    }

    fun clickTargets(): List<SemanticsNode> = nodes.filter {
        it.config.getOrNull(SemanticsActions.OnClick) != null
    }

    override fun close() {
        view.disposeComposition()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManagerLayoutTest {
    @Test fun spanishCounterReservesOneLineForTheActionAtNarrowWidth() {
        LayoutProbe(272, 1f) { CounterFragment() }.use { probe ->
            val action = probe.text(FixtureLabels.forLanguage("es").getValue(R.string.view_all))
            val layout = probe.layout(action)
            assertEquals("The action must remain one readable line", 1, layout.lineCount)
            assertFalse("The action must fit its measured height", layout.didOverflowHeight)
            assertTrue("The action must retain nonzero width", action.boundsInRoot.width > 20f)
        }
    }

    @Test fun spanishBottomLabelsRemainOneLineAtNarrowWidth() {
        LayoutProbe(280, 1f) { BottomFragment() }.use { probe ->
            val label = probe.text(FixtureLabels.forLanguage("es").getValue(R.string.nav_automation))
            assertEquals("Automation must not wrap below the capsule", 1, probe.layout(label).lineCount)
        }
    }

    @Test fun doubleFontScaleExpandsTheCapsuleAndPreservesTextHeight() {
        LayoutProbe(320, 2f) { BottomFragment() }.use { probe ->
            val targets = probe.clickTargets()
            assertEquals(5, targets.size)
            assertTrue("Capsule items must grow beyond the former fixed 54dp height",
                targets.all { it.boundsInRoot.height > 54f })
            for (tab in BottomTab.entries) {
                val label = probe.text(FixtureLabels.forLanguage("es").getValue(tab.labelRes))
                val layout = probe.layout(label)
                assertEquals(1, layout.lineCount)
                // didOverflowHeight also includes didExceedMaxLines: a narrow
                // single-line ellipsis may set it even when its full line fits.
                // Compare the real paragraph and line bounds to the allocated
                // height, so expected horizontal truncation cannot hide or
                // falsely report vertical clipping.
                assertTrue("Scaled paragraph must fit its allocated height: "+tab,
                    layout.multiParagraph.height <= layout.size.height.toFloat())
                assertTrue("The line bottom must remain visible: "+tab,
                    layout.getLineBottom(0) <= layout.size.height.toFloat())
                assertTrue("The line top must remain visible: "+tab, layout.getLineTop(0) >= 0f)
                println("SCALED_LABEL tab="+tab+" textSize="+layout.size+
                    " paragraphHeight="+layout.multiParagraph.height+
                    " ellipsized="+layout.isLineEllipsized(0))
                assertTrue("Full label remains in semantics", label.config[SemanticsProperties.Text].any {
                    it.text == FixtureLabels.forLanguage("es").getValue(tab.labelRes)
                })
            }
        }
    }

    @Test fun wideEnglishLayoutKeepsFiveNonoverlappingReachableTabs() {
        LayoutProbe(500, 1f, "en") { BottomFragment() }.use { probe ->
            val targets = probe.clickTargets().sortedBy { it.boundsInRoot.left }
            assertEquals(5, targets.size)
            targets.zipWithNext().forEach { (left, right) ->
                assertTrue(left.boundsInRoot.right <= right.boundsInRoot.left + 1f)
            }
            targets.forEach { target ->
                assertTrue(target.boundsInRoot.width >= 48f)
                assertTrue(target.boundsInRoot.height >= 48f)
                assertTrue(target.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
        }
    }

    @Test fun rightToLeftKeepsAllLabelsInsideTheirClickableItems() {
        LayoutProbe(500, 2f, direction = LayoutDirection.Rtl) { BottomFragment() }.use { probe ->
            val targets = probe.clickTargets()
            assertEquals(5, targets.size)
            for (tab in BottomTab.entries) {
                val label = probe.text(FixtureLabels.forLanguage("es").getValue(tab.labelRes))
                val bounds = label.boundsInRoot
                assertTrue("Label must remain inside a reachable tab", targets.any {
                    val item = it.boundsInRoot
                    bounds.left >= item.left - 1f && bounds.right <= item.right + 1f &&
                        bounds.top >= item.top - 1f && bounds.bottom <= item.bottom + 1f
                })
            }
        }
    }
}
