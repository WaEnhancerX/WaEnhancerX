package com.waenhancer.xposed.features.customization;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Checkable;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 34}, manifest = Config.NONE)
public class FloatingBottomBarLifecycleTest {
    private static final int FAB_ID = 0x7f040001;
    private static final int INDICATOR_ID = 0x7f040002;
    private ActivityController<Activity> activity;
    private Context context;
    private CountingRoot root;

    @Before public void setup() throws Exception {
        for (Field field : FloatingBottomBarHook.class.getDeclaredFields()) {
            if (Map.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                ((Map<?, ?>) field.get(null)).clear();
            }
        }
        booleanField("pillDesignPro", true);
        booleanField("pillDesignIos", false);
        activity = Robolectric.buildActivity(Activity.class).setup().visible();
        Resources base = activity.get().getResources();
        // WhatsApp's ID names are fixtures. Layout, visibility and Drawable behavior
        // below execute Android framework code, without replacing their implementation.
        Resources host = new Resources(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration()) {
            @Override public int getIdentifier(String name, String type, String pkg) {
                return "fab".equals(name) && "id".equals(type) ? FAB_ID : 0;
            }
            @Override public String getResourceEntryName(int id) {
                return id == INDICATOR_ID ? "navigation_bar_item_active_indicator" : super.getResourceEntryName(id);
            }
        };
        context = new ContextWrapper(activity.get()) {
            @Override public Resources getResources() { return host; }
        };
        root = new CountingRoot(context);
        activity.get().setContentView(root);
        layout(root, 400, 800);
        assertTrue("fixture must exercise attached Android Views", root.isAttachedToWindow());
    }

    @After public void teardown() {
        activity.pause().stop().destroy();
    }

    @Test public void unchangedSelectionDoesNotScheduleAnotherRedraw() throws Exception {
        CountingTab tab = new CountingTab(context);
        select(tab, true);
        tab.invalidations = 0;
        tab.drawableRefreshes = 0;
        for (int i = 0; i < 100; i++) select(tab, true);
        assertEquals("100 idle reconciliations must not request redraw", 0, tab.invalidations);
        assertEquals("unchanged drawable state must stay idle", 0, tab.drawableRefreshes);
    }

    @Test public void selectionChangesStillUpdateCheckedSelectedAndActivated() throws Exception {
        CountingTab tab = new CountingTab(context);
        select(tab, true);
        assertTrue(tab.isChecked());
        assertTrue(tab.isSelected());
        assertTrue(tab.isActivated());
        assertTrue(tab.getBackground() instanceof TransitionDrawable);
        select(tab, false);
        assertFalse(tab.isChecked());
        assertFalse(tab.isSelected());
        assertFalse(tab.isActivated());
    }

    @Test public void repeatedSelectionDoesNotRetainTransitionHistory() throws Exception {
        CountingTab tab = new CountingTab(context);
        for (int i = 0; i < 100; i++) select(tab, i % 2 == 0);
        assertTrue("transition history must stay one layer deep, actual=" + transitionDepth(tab.getBackground()),
                transitionDepth(tab.getBackground()) <= 1);
    }

    @Test public void hostReplacedTransitionIsPreservedAsTheStartingBackground() throws Exception {
        CountingTab tab = new CountingTab(context);
        select(tab, true);
        TransitionDrawable host = new TransitionDrawable(new Drawable[]{
                new ColorDrawable(0xff102030), new ColorDrawable(0xff405060)});
        tab.setBackground(host);
        select(tab, false);
        TransitionDrawable next = (TransitionDrawable) tab.getBackground();
        assertSame("only WAEX-owned transitions may be flattened", host, next.getDrawable(0));
    }

    @Test public void unchangedNativeIndicatorDoesNotReplaceItsBackground() throws Exception {
        CountingTab indicator = new CountingTab(context);
        indicator.setId(INDICATOR_ID);
        invoke("hideNativeActiveIndicator", new Class<?>[]{View.class}, indicator);
        assertEquals(View.INVISIBLE, indicator.getVisibility());
        assertEquals(0f, indicator.getAlpha(), 0f);
        indicator.backgroundWrites = 0;
        for (int i = 0; i < 100; i++) {
            invoke("hideNativeActiveIndicator", new Class<?>[]{View.class}, indicator);
        }
        assertEquals("idle pre-draw must not allocate/replace indicator backgrounds", 0, indicator.backgroundWrites);
    }

    @Test public void hostRestoredIndicatorIsHiddenAgain() throws Exception {
        CountingTab indicator = new CountingTab(context);
        indicator.setId(INDICATOR_ID);
        invoke("hideNativeActiveIndicator", new Class<?>[]{View.class}, indicator);
        indicator.setVisibility(View.VISIBLE);
        indicator.setAlpha(1f);
        invoke("hideNativeActiveIndicator", new Class<?>[]{View.class}, indicator);
        assertEquals(View.INVISIBLE, indicator.getVisibility());
        assertEquals(0f, indicator.getAlpha(), 0f);
    }

    @Test public void zeroHeightContainerDoesNotPollTheMainLooper() throws Exception {
        FrameLayout container = addContainer(0);
        root.delayedPosts = 0;
        position(root, container);
        position(root, container);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
        assertEquals("empty bar must wait for layout, not 100 ms polling", 0, root.delayedPosts);
    }

    @Test public void firstNonzeroLayoutPositionsFabWithoutWaitingForATimer() throws Exception {
        FrameLayout container = addContainer(0);
        View fab = addFab();
        position(root, container);
        container.layout(0, 0, 400, 68);
        assertEquals(expectedOffset(container), fab.getTranslationY(), 0.001f);
    }

    @Test public void detachedEmptyContainerSchedulesNoFurtherWork() throws Exception {
        FrameLayout container = addContainer(0);
        position(root, container);
        root.removeView(container);
        root.delayedPosts = 0;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
        assertEquals("detached container must not reschedule on its former root", 0, root.delayedPosts);
    }

    @Test public void measuredContainerPreservesFabOffset() throws Exception {
        FrameLayout container = addContainer(68);
        View fab = addFab();
        position(root, container);
        assertEquals(expectedOffset(container), fab.getTranslationY(), 0.001f);
    }

    private FrameLayout addContainer(int height) {
        FrameLayout container = new FrameLayout(context);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(400, height);
        params.bottomMargin = 22;
        root.addView(container, params);
        layout(root, 400, 800);
        return container;
    }

    private View addFab() {
        View fab = new View(context);
        fab.setId(FAB_ID);
        root.addView(fab, new FrameLayout.LayoutParams(40, 40));
        return fab;
    }

    private static float expectedOffset(ViewGroup container) {
        return -(container.getHeight() + ((ViewGroup.MarginLayoutParams) container.getLayoutParams()).bottomMargin
                + 12f * container.getResources().getDisplayMetrics().density);
    }

    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static void position(ViewGroup root, ViewGroup container) throws Exception {
        invoke("positionFabsAboveBar", new Class<?>[]{ViewGroup.class, ViewGroup.class}, root, container);
    }

    private static void select(View view, boolean checked) throws Exception {
        invoke("setTabChecked", new Class<?>[]{View.class, boolean.class}, view, checked);
    }

    private static void invoke(String name, Class<?>[] parameters, Object... args) throws Exception {
        Method method = FloatingBottomBarHook.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        method.invoke(null, args);
    }

    private static void booleanField(String name, boolean value) throws Exception {
        Field field = FloatingBottomBarHook.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(null, value);
    }

    private static int transitionDepth(Drawable drawable) {
        if (!(drawable instanceof TransitionDrawable)) return 0;
        TransitionDrawable transition = (TransitionDrawable) drawable;
        int depth = 0;
        for (int i = 0; i < transition.getNumberOfLayers(); i++) {
            depth = Math.max(depth, transitionDepth(transition.getDrawable(i)));
        }
        return 1 + depth;
    }

    private static final class CountingRoot extends FrameLayout {
        int delayedPosts;
        CountingRoot(Context context) { super(context); }
        @Override public boolean postDelayed(Runnable action, long delay) {
            delayedPosts++;
            return super.postDelayed(action, delay);
        }
    }

    private static final class CountingTab extends FrameLayout implements Checkable {
        int invalidations, drawableRefreshes, backgroundWrites;
        boolean checked;
        CountingTab(Context context) { super(context); }
        @Override public void invalidate() { invalidations++; super.invalidate(); }
        @Override public void refreshDrawableState() { drawableRefreshes++; super.refreshDrawableState(); }
        @Override public void setBackground(Drawable drawable) { backgroundWrites++; super.setBackground(drawable); }
        @Override public boolean isChecked() { return checked; }
        @Override public void setChecked(boolean value) { checked = value; refreshDrawableState(); }
        @Override public void toggle() { setChecked(!checked); }
    }
}
