import android.content.pm.ApplicationInfo
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.waenhancer.xposed.features.automation.PresenceStateStore
import java.util.WeakHashMap
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28, 34, 36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PresenceRowRenderingTest {
    private lateinit var app: Application
    private lateinit var host: Context
    private lateinit var h: RendererHarness
    private var avatarId = 0
    private val hostRows = linkedMapOf<FrameLayout, Pair<Int, Int>>()
    private val a = "910001@s.whatsapp.net"
    private val b = "910002@s.whatsapp.net"
    private val lid = "123456@lid"
    @Before fun reset() {
        for (name in listOf("STATES", "LISTENERS")) {
            val f = PresenceStateStore::class.java.getDeclaredField(name); f.isAccessible = true
            val value = f.get(null)
            if (value is MutableMap<*,*>) value.clear()
            if (value is MutableSet<*>) value.clear()
        }
        app = RuntimeEnvironment.getApplication()
        // Config.NONE has no manifest: explicitly enable the RTL fixture contract.
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        app.applicationInfo.targetSdkVersion = 28
        // AAPT2 supplies a genuine host resource table, not an always-matching
        // getIdentifier stub. The APK has resources only and is never installed.
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val cookie = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
            .invoke(assets, requireNotNull(System.getProperty("waex.presence.hostResources"))) as Int
        assertTrue("compiled fixture resource table must load", cookie != 0)
        val resources = Resources(assets, app.resources.displayMetrics,
                                  Configuration(app.resources.configuration))
        host = object : ContextWrapper(app) {
            override fun getResources() = resources
            override fun getAssets() = assets
            override fun getPackageName() = "org.waex.qa.presence.host"
        }
        avatarId = resources.getIdentifier("contact_selector", "id", host.packageName)
        assertTrue("real compiled host ID must resolve", avatarId != 0)
        h = RendererHarness(host)
        hostRows.clear()
        val companion = RendererHarness::class.java.getDeclaredField("identityAliases")
        companion.isAccessible = true; (companion.get(null) as MutableMap<*,*>).clear()
    }
    private fun px(dp: Int) = (dp * host.resources.displayMetrics.density + 0.5f).toInt()
    private fun row(w: Int = 400, height: Int = 80, withAvatar: Boolean = true): FrameLayout =
        FrameLayout(if (withAvatar) host else app).also { r ->
        if (withAvatar) {
            val avatar = FrameLayout(host).apply { id = avatarId }
            val params = FrameLayout.LayoutParams(px(48), px(48), Gravity.START or Gravity.TOP)
            params.marginStart = px(16); params.topMargin = px(8)
            r.addView(avatar, params)
        }
        resize(r, w, height)
    }
    private fun resize(row: FrameLayout, w: Int, height: Int) {
        hostRows[row] = w to height
        layoutHost(row, w, height)
    }
    private fun layoutHost(row: FrameLayout, w: Int, height: Int) {
        // Real parent traversal measures/positions real children. No accessor
        // creates views or simulates the renderer's layout/visibility result.
        row.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        row.layout(0, 0, w, height)
    }
    private fun pump() {
        shadowOf(Looper.getMainLooper()).idle()
        hostRows.forEach { (row, size) -> layoutHost(row, size.first, size.second) }
    }
    private fun avatar(row: View) = row.findViewById<FrameLayout>(avatarId)!!
    private fun centerInRow(row: FrameLayout, child: View): Pair<Int, Int> {
        val rect = Rect(0, 0, child.width, child.height)
        row.offsetDescendantRectToMyCoords(child, rect)
        return rect.centerX() to rect.centerY()
    }
    private fun childCountWithId(group: ViewGroup, id: Int): Int =
        (0 until group.childCount).sumOf {
            val child = group.getChildAt(it)
            (if (child.id == id) 1 else 0) +
                (if (child is ViewGroup) childCountWithId(child, id) else 0)
        }
    private fun assertWeakKeys(map: MutableMap<*, *>) {
        // Inspect the actual production synchronized-map backing; no boolean
        // fixture answer can certify weak ownership. This is not a GC test.
        val field = map.javaClass.getDeclaredField("m"); field.isAccessible = true
        assertTrue("row tracking must use weak keys", field.get(map) is WeakHashMap<*, *>)
    }
    private fun dot(row: View): View {
        val view = h.dot(row); assertNotNull("expected attached dot child", view)
        return view!!
    }
    private fun text(row: View): TextView {
        val view = h.text(row); assertNotNull("expected attached text child", view)
        return view!!
    }
    private fun visible(r: View) = h.dot(r)?.visibility == View.VISIBLE
    @Test fun exactCachedOnlineMeasuresVisibleDot() {
        val r = row(); PresenceStateStore.update(a, true); h.bind(r, a); pump()
        assertTrue(visible(r)); assertTrue("dot must be measured", dot(r).measuredWidth > 0)
        assertTrue(dot(r).height > 0); assertEquals("online", text(r).text.toString())
    }
    @Test fun cachedAliasReplayedBeforeFirstBindAndAfterRebind() {
        val r = row(); PresenceStateStore.update(lid, true); h.alias(lid, a); h.bind(r, a); pump()
        assertTrue("previous alias observation must be replayed", visible(r))
        h.bind(r, a); pump(); assertTrue(visible(r))
    }
    @Test fun sameOnlineReobservedDoesNotLoseAliasState() {
        val r = row(); h.alias(lid, a); PresenceStateStore.update(lid, true); h.bind(r, a)
        assertFalse(PresenceStateStore.update(lid, true)); pump(); assertTrue(visible(r))
    }
    @Test fun latestObservationWinsWithoutWallClockOrdering() {
        val r = row(); h.alias(lid, a); PresenceStateStore.update(a, false)
        val first = PresenceStateStore.get(a); PresenceStateStore.update(lid, true)
        assertTrue(PresenceStateStore.get(lid).revision > first.revision)
        h.bind(r, a); pump(); assertTrue(visible(r))
        PresenceStateStore.update(a, false); pump(); assertFalse(visible(r))
        PresenceStateStore.update(lid, true); pump(); assertTrue(visible(r))
    }
    @Test fun repeatedObservationPreservesChangeTimeAndRefreshesRevision() {
        PresenceStateStore.update(a, true); val old = PresenceStateStore.get(a)
        assertFalse(PresenceStateStore.update(a, true)); val latest = PresenceStateStore.get(a)
        assertEquals(old.changedAtMillis, latest.changedAtMillis); assertTrue(latest.revision > old.revision)
    }
    @Test fun queuedOldBindCannotReplaceNewContact() {
        val r = row(); PresenceStateStore.update(a, true); PresenceStateStore.update(b, false)
        h.queue(r, a); h.queue(r, b); pump()
        assertEquals(b, h.bound(r)); assertFalse(visible(r))
        PresenceStateStore.update(a, false); PresenceStateStore.update(a, true); pump()
        assertEquals(b, h.bound(r)); assertFalse(visible(r))
    }
    @Test fun nullQueuedBindClearsPriorDecoration() {
        val r = row(); PresenceStateStore.update(a, true); h.bind(r, a); pump(); assertTrue(visible(r))
        h.queue(r, null); pump(); assertNull(h.bound(r)); assertFalse(visible(r))
        assertEquals("", text(r).text.toString())
        PresenceStateStore.update(a, true); pump(); assertFalse(visible(r))
    }
    @Test fun delayedObservationsResolveCurrentCache() {
        val r = row(); h.bind(r, a)
        repeat(2000) { PresenceStateStore.update(a, it % 2 == 0) }
        PresenceStateStore.update(a, true); pump(); assertTrue(visible(r))
    }
    @Test fun conflictingAliasCannotTransferPresenceBetweenContacts() {
        val ra = row(); val rb = row(); PresenceStateStore.update(lid, true)
        h.alias(lid, a); h.alias(lid, b); h.alias(lid, b)
        h.bind(ra, a); h.bind(rb, b); pump(); assertFalse(visible(ra)); assertFalse(visible(rb))
    }
    @Test fun lateAliasRegistrationRepaintsAlreadyBoundRow() {
        val r = row(); PresenceStateStore.update(lid, true); h.bind(r, a); pump()
        assertFalse(visible(r)); h.alias(lid, a); pump(); assertTrue(visible(r))
    }
    @Test fun conflictingAliasClearsPreviouslyVisibleRow() {
        val r = row(); PresenceStateStore.update(lid, true); h.alias(lid, a); h.bind(r, a); pump()
        assertTrue(visible(r)); h.alias(lid, b); pump(); assertFalse(visible(r))
    }
    @Test fun namespaceOrPrefixSimilarityDoesNotCreateAliases() {
        val r = row(); PresenceStateStore.update("910001@lid", true)
        h.bind(r, a); pump(); assertFalse(visible(r))
    }
    @Test fun invisibleToVisibleTransitionPerformsMeasurement() {
        val r = row(); h.bind(r, a); pump(); assertFalse(visible(r))
        PresenceStateStore.update(a, true); pump(); assertTrue(visible(r)); assertTrue(dot(r).width > 0)
        PresenceStateStore.update(a, false); pump(); assertFalse(visible(r)); assertTrue(text(r).width > 0)
    }
    @Test fun resizeRelayoutsAvatarAndRealChildren() {
        val r = row(); PresenceStateStore.update(a, true); h.bind(r, a); pump()
        val before = centerInRow(r, dot(r))
        avatar(r).layoutParams = (avatar(r).layoutParams as FrameLayout.LayoutParams).also {
            it.width = px(64); it.height = px(64)
        }
        resize(r, 600, 110); pump()
        assertEquals(600, r.width); assertEquals(110, r.height)
        assertEquals(px(64), avatar(r).width)
        assertEquals(px(14), dot(r).width)
        val after = centerInRow(r, dot(r))
        assertEquals(px(16), after.first - before.first)
        assertEquals(px(16), after.second - before.second)
        assertEquals(600 - px(14), text(r).right)
    }
    @Test fun rtlFollowsHostDirection() {
        for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            val r = row(); r.layoutDirection = direction
            assertEquals("fixture host must support explicit direction", direction, r.layoutDirection)
            PresenceStateStore.update(a, true); h.bind(r, a); pump()
            val dot = dot(r); val container = avatar(r)
            assertEquals(direction, container.layoutDirection)
            assertEquals(direction, dot.layoutDirection)
            if (direction == View.LAYOUT_DIRECTION_LTR) {
                assertEquals("END is the avatar's right edge in LTR", container.width - px(4), dot.right)
            } else {
                assertEquals("END is the avatar's left edge in RTL", px(4), dot.left)
            }
        }
    }
    @Test fun initiallyZeroSizedRowRecoversAfterLayout() {
        val r = row(0, 0); PresenceStateStore.update(a, true); h.bind(r, a); pump()
        resize(r, 400, 80); pump(); assertTrue(visible(r)); assertTrue(dot(r).width > 0)
    }
    @Test fun repeatedBindReusesUniqueDotAndTextChildren() {
        val r = row(); PresenceStateStore.update(a, true); h.bind(r, a); pump()
        val firstDot = dot(r); val firstText = text(r)
        repeat(50) { h.bind(r, a) }; pump()
        assertSame(firstDot, h.dot(r)); assertSame(firstText, h.text(r))
        assertSame(avatar(r), firstDot.parent); assertSame(r, firstText.parent)
        assertEquals(1, childCountWithId(r, firstDot.id))
        assertEquals(1, childCountWithId(r, firstText.id))
        assertEquals(a, h.visibleTracking()[r])
        assertWeakKeys(h.visibleTracking()); assertWeakKeys(h.pendingTracking())
    }
    @Test fun independentDotAndTextConfiguration() {
        PresenceStateStore.update(a, true)
        for (dot in listOf(false, true)) for (text in listOf(false, true)) {
            val renderer = RendererHarness(app, dot, text); val r = row(); renderer.bind(r, a); pump()
            assertEquals(dot, renderer.dot(r) != null); assertEquals(text, renderer.text(r) != null)
        }
    }
    @Test fun nativeCanvasContainsGreenDotAndClearsOffline() {
        val r = row(); PresenceStateStore.update(a, true); h.bind(r, a); pump()
        val (x, y) = centerInRow(r, dot(r))
        val bitmap = Bitmap.createBitmap(400, 80, Bitmap.Config.ARGB_8888)
        r.draw(Canvas(bitmap)); assertEquals("rendered center pixel", Color.rgb(37, 211, 102), bitmap.getPixel(x,y))
        PresenceStateStore.update(a, false); pump(); bitmap.eraseColor(Color.TRANSPARENT)
        r.draw(Canvas(bitmap)); assertNotEquals(Color.rgb(37,211,102), bitmap.getPixel(x,y))
    }
    @Test fun deniedPresenceClearsVisibleDotAndTextWithoutLastSeen() {
        val r = row(); PresenceStateStore.update(a, true); h.bind(r, a); pump()
        PresenceStateStore.invalidate(a); pump()
        assertFalse(visible(r)); assertEquals("", text(r).text.toString())
        assertEquals(View.GONE, text(r).visibility)
    }
    @Test fun deniedAliasMasksAnOlderCanonicalObservation() {
        val r = row(); h.alias(lid, a); PresenceStateStore.update(a, true)
        PresenceStateStore.invalidate(lid); h.bind(r, a); pump()
        assertFalse(visible(r)); assertEquals("", text(r).text.toString())
        PresenceStateStore.update(lid, true); pump(); assertTrue(visible(r))
    }
    @Test fun transportInvalidationRepaintsAlreadyVisibleRows() {
        val ra = row(); val rb = row()
        PresenceStateStore.update(a, true); PresenceStateStore.update(b, false)
        h.bind(ra, a); h.bind(rb, b); pump()
        PresenceStateStore.invalidateAll(); pump()
        assertFalse(visible(ra)); assertEquals("", text(ra).text.toString())
        assertEquals("", text(rb).text.toString())
    }
    @Test fun pendingOldRefreshCannotResurrectTransportState() {
        val r = row(); h.bind(r, a); PresenceStateStore.update(a, true)
        PresenceStateStore.invalidateAll(); pump(); assertFalse(visible(r))
        PresenceStateStore.update(a, true); pump(); assertTrue(visible(r))
    }
    @Test fun deniedCanonicalDoesNotReplayOldAliasAfterRebind() {
        val r = row(); h.alias(lid, a); PresenceStateStore.update(lid, true)
        h.bind(r, a); pump(); PresenceStateStore.invalidate(a)
        h.bind(r, a); pump(); assertFalse(visible(r))
        assertEquals("", text(r).text.toString())
    }
    @Test fun invalidationClearsTheNativeGreenPixel() {
        val r = row(); PresenceStateStore.update(a, true); h.bind(r, a); pump()
        val (x, y) = centerInRow(r, dot(r))
        val bitmap = Bitmap.createBitmap(400, 80, Bitmap.Config.ARGB_8888)
        r.draw(Canvas(bitmap)); assertEquals(Color.rgb(37,211,102), bitmap.getPixel(x,y))
        PresenceStateStore.invalidate(a); pump(); bitmap.eraseColor(Color.TRANSPARENT)
        r.draw(Canvas(bitmap)); assertNotEquals(Color.rgb(37,211,102), bitmap.getPixel(x,y))
    }
    @Test fun realCompiledHostIdSelectsAvatarContainer() {
        val r = row(); PresenceStateStore.update(a, true); h.bind(r, a); pump()
        assertEquals("org.waex.qa.presence.host", host.resources.getResourcePackageName(avatarId))
        assertEquals("contact_selector", host.resources.getResourceEntryName(avatarId))
        assertSame(avatar(r), dot(r).parent)
        assertSame(r, text(r).parent)
        assertFalse(dot(r).isClickable); assertFalse(dot(r).isFocusable)
        assertFalse(text(r).isClickable)
    }
    @Test fun missingHostAvatarIdFallsBackToActualRowContainer() {
        val r = row(withAvatar = false)
        assertEquals(0, r.context.resources.getIdentifier("contact_selector", "id", r.context.packageName))
        PresenceStateStore.update(a, true); h.bind(r, a); pump()
        assertSame(r, dot(r).parent); assertTrue(visible(r))
        assertEquals(px(14), dot(r).width)
        assertEquals(r.width - px(4), dot(r).right)
        assertEquals(r.height - px(4), dot(r).bottom)
    }
    @Test fun queuedBindingsUseWeakKeysAndReleasePendingTokens() {
        val r = row(); PresenceStateStore.update(a, true); h.queue(r, a)
        assertWeakKeys(h.pendingTracking()); assertEquals(1, h.pendingTracking().size)
        assertFalse(h.pendingTracking()[r] is View)
        assertFalse(h.visibleTracking().containsKey(r))
        pump(); assertTrue(h.pendingTracking().isEmpty())
        assertEquals(a, h.visibleTracking()[r]); assertTrue(visible(r))
    }
}
