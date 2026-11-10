import android.content.pm.ApplicationInfo


import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.waenhancer.xposed.features.automation.PresenceStateStore
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
    private lateinit var h: RendererHarness
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
        h = RendererHarness(app)
        val companion = RendererHarness::class.java.getDeclaredField("identityAliases")
        companion.isAccessible = true; (companion.get(null) as MutableMap<*,*>).clear()
    }
    private fun row(w: Int = 400, height: Int = 80): FrameLayout = FrameLayout(app).also {
        it.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                   View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        it.layout(0, 0, w, height)
    }
    private fun pump() { shadowOf(Looper.getMainLooper()).idle() }
    private fun visible(r: View) = h.dot(r)?.visibility == View.VISIBLE
    @Test fun exactCachedOnlineMeasuresVisibleDot() {
        val r = row(); PresenceStateStore.update(a, true); h.bind(r, a); pump()
        assertTrue(visible(r)); assertTrue("dot must be measured", h.dot(r)!!.measuredWidth > 0)
        assertTrue(h.dot(r)!!.height > 0); assertEquals("online", h.text(r)!!.text.toString())
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
    @Test fun failedOrGroupBindClearsPriorDecoration() {
        val r = row(); PresenceStateStore.update(a, true); h.bind(r, a); pump(); assertTrue(visible(r))
        h.queue(r, null); pump(); assertNull(h.bound(r)); assertFalse(visible(r))
        assertEquals("", h.text(r)!!.text.toString())
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
        PresenceStateStore.update(a, true); pump(); assertTrue(visible(r)); assertTrue(h.dot(r)!!.width > 0)
        PresenceStateStore.update(a, false); pump(); assertFalse(visible(r)); assertTrue(h.text(r)!!.width > 0)
    }
    @Test fun resizeRelayoutsOverlayAndChildren() {
        val r = row(); PresenceStateStore.update(a, true); h.bind(r, a); pump()
        r.layout(0, 0, 600, 110); pump()
        assertEquals(600, h.overlay(r)!!.width); assertEquals(110, h.overlay(r)!!.height)
        assertTrue(h.dot(r)!!.width > 0)
    }
    @Test fun rtlFollowsHostDirection() {
        val r = row(); r.layoutDirection = View.LAYOUT_DIRECTION_RTL
        assertEquals("fixture host must support RTL", View.LAYOUT_DIRECTION_RTL, r.layoutDirection)
        PresenceStateStore.update(a, true); h.bind(r, a); pump()
        assertEquals(View.LAYOUT_DIRECTION_RTL, h.overlay(r)!!.layoutDirection)
        assertTrue("START dot must be on right in RTL", h.dot(r)!!.left > r.width / 2)
    }
    @Test fun initiallyZeroSizedRowRecoversAfterLayout() {
        val r = row(0, 0); PresenceStateStore.update(a, true); h.bind(r, a); pump()
        r.layout(0, 0, 400, 80); pump(); assertTrue(visible(r)); assertTrue(h.dot(r)!!.width > 0)
    }
    @Test fun repeatedBindDoesNotDuplicateOverlay() {
        val r = row(); PresenceStateStore.update(a, true); h.bind(r, a); pump(); val first = h.overlay(r)
        repeat(50) { h.bind(r, a) }; pump(); assertSame(first, h.overlay(r)); assertTrue(h.weakValues())
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
        val dot = h.dot(r)!!; val x = dot.left + dot.width / 2; val y = dot.top + dot.height / 2
        val bitmap = Bitmap.createBitmap(400, 80, Bitmap.Config.ARGB_8888)
        r.draw(Canvas(bitmap)); assertEquals("rendered center pixel", Color.rgb(37, 211, 102), bitmap.getPixel(x,y))
        PresenceStateStore.update(a, false); pump(); bitmap.eraseColor(Color.TRANSPARENT)
        r.draw(Canvas(bitmap)); assertNotEquals(Color.rgb(37,211,102), bitmap.getPixel(x,y))
    }
    @Test fun deniedPresenceClearsVisibleDotAndTextWithoutLastSeen() {
        val r = row(); PresenceStateStore.update(a, true); h.bind(r, a); pump()
        PresenceStateStore.invalidate(a); pump()
        assertFalse(visible(r)); assertEquals("", h.text(r)!!.text.toString())
        assertEquals(View.GONE, h.text(r)!!.visibility)
    }
    @Test fun deniedAliasMasksAnOlderCanonicalObservation() {
        val r = row(); h.alias(lid, a); PresenceStateStore.update(a, true)
        PresenceStateStore.invalidate(lid); h.bind(r, a); pump()
        assertFalse(visible(r)); assertEquals("", h.text(r)!!.text.toString())
        PresenceStateStore.update(lid, true); pump(); assertTrue(visible(r))
    }
    @Test fun transportInvalidationRepaintsAlreadyVisibleRows() {
        val ra = row(); val rb = row()
        PresenceStateStore.update(a, true); PresenceStateStore.update(b, false)
        h.bind(ra, a); h.bind(rb, b); pump()
        PresenceStateStore.invalidateAll(); pump()
        assertFalse(visible(ra)); assertEquals("", h.text(ra)!!.text.toString())
        assertEquals("", h.text(rb)!!.text.toString())
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
        assertEquals("", h.text(r)!!.text.toString())
    }
    @Test fun invalidationClearsTheNativeGreenPixel() {
        val r = row(); PresenceStateStore.update(a, true); h.bind(r, a); pump()
        val dot = h.dot(r)!!; val x = dot.left + dot.width / 2; val y = dot.top + dot.height / 2
        val bitmap = Bitmap.createBitmap(400, 80, Bitmap.Config.ARGB_8888)
        r.draw(Canvas(bitmap)); assertEquals(Color.rgb(37,211,102), bitmap.getPixel(x,y))
        PresenceStateStore.invalidate(a); pump(); bitmap.eraseColor(Color.TRANSPARENT)
        r.draw(Canvas(bitmap)); assertNotEquals(Color.rgb(37,211,102), bitmap.getPixel(x,y))
    }
}
