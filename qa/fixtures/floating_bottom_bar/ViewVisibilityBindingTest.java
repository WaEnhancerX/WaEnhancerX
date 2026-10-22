package com.waenhancer.xposed.features.customization;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import com.waenhancer.xposed.features.homescreen.ChatListCustomizationsHook;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 34}, manifest = Config.NONE)
public class ViewVisibilityBindingTest {
    private ActivityController<Activity> activity;
    private Context context;
    private SharedPreferences preferences;
    private FrameLayout root;

    @Before public void setup() {
        activity = Robolectric.buildActivity(Activity.class).setup().visible();
        context = activity.get();
        root = new FrameLayout(context);
        activity.get().setContentView(root);
        preferences = context.getSharedPreferences("view-hide", Context.MODE_PRIVATE);
        preferences.edit().clear().putBoolean("metaai", true).putBoolean("channels_enhancements", true).commit();
        DexSearchEngine.reset();
        XposedBridge.reset();
        DexSearchEngine.metaTarget = MetaView.class;
        DexSearchEngine.channelTarget = ChannelView.class;
    }

    @After public void teardown() { activity.pause().stop().destroy(); }

    @Test public void metaAiIsHiddenAfterHostBindingRestoresVisibility() throws Throwable {
        installMeta();
        CountingView view = new MetaView(context);
        XposedBridge.dispatchConstructor(view);
        assertEquals(View.GONE, view.getVisibility());
        root.addView(view);
        view.setVisibility(View.VISIBLE);
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals("host binding must not restore enabled Meta AI", View.GONE, view.getVisibility());
    }

    @Test public void channelRecommendationsAreHiddenAfterHostRebind() throws Throwable {
        installChannels();
        CountingView view = new ChannelView(context);
        XposedBridge.dispatchConstructor(view);
        root.addView(view);
        view.setVisibility(View.VISIBLE);
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals("host rebind must not restore enabled recommendations", View.GONE, view.getVisibility());
    }

    @Test public void channelHidingPreservesHostLayoutParameters() throws Throwable {
        installChannels();
        CountingView view = new ChannelView(context);
        ViewGroup.LayoutParams params = new FrameLayout.LayoutParams(190, 72);
        view.setLayoutParams(params);
        XposedBridge.dispatchConstructor(view);
        assertSame(params, view.getLayoutParams());
        assertEquals("GONE is enough; host width must survive", 190, params.width);
        assertEquals(72, params.height);
    }

    @Test public void disabledPreferenceAllowsTheNextHostBind() throws Throwable {
        installMeta();
        CountingView view = new MetaView(context);
        XposedBridge.dispatchConstructor(view);
        root.addView(view);
        preferences.edit().putBoolean("metaai", false).commit();
        view.setVisibility(View.VISIBLE);
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals(View.VISIBLE, view.getVisibility());
    }

    @Test public void detachStopsReconciliationAndReattachResumesIt() throws Throwable {
        installMeta();
        CountingView view = new MetaView(context);
        XposedBridge.dispatchConstructor(view);
        root.addView(view);
        root.removeView(view);
        view.setVisibility(View.VISIBLE);
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals("old root must not mutate its detached View", View.VISIBLE, view.getVisibility());
        root.addView(view);
        assertEquals("reattach must reconcile exactly this resolved View", View.GONE, view.getVisibility());
    }

    @Test public void unchangedHiddenViewDoesNotInvalidateOnEveryLayout() throws Throwable {
        installMeta();
        CountingView view = new MetaView(context);
        XposedBridge.dispatchConstructor(view);
        root.addView(view);
        view.visibilityWrites = 0;
        for (int i = 0; i < 100; i++) root.getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals(0, view.visibilityWrites);
    }

    @Test public void duplicateConstructorCallbacksRegisterOnlyOneGuard() throws Throwable {
        installMeta();
        CountingView view = new MetaView(context);
        XposedBridge.dispatchConstructor(view);
        XposedBridge.dispatchConstructor(view);
        int listenersBeforeAttach = globalLayoutListenerCount(root.getViewTreeObserver());
        root.addView(view);
        assertEquals("constructor chaining must register exactly one local observer",
                listenersBeforeAttach + 1, globalLayoutListenerCount(root.getViewTreeObserver()));
        view.setVisibility(View.VISIBLE);
        view.visibilityWrites = 0;
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals("one explicit target produces one hide operation", 1, view.visibilityWrites);
    }

    @Test public void disabledMetaAiAvoidsItsDexQuery() throws Throwable {
        preferences.edit().putBoolean("metaai", false).commit();
        installMeta();
        assertEquals("disabled Meta AI must not scan during startup", 0, DexSearchEngine.metaQueries);
    }

    @Test public void missingMetaAiTargetIsReported() {
        DexSearchEngine.metaTarget = null;
        assertThrows(IllegalStateException.class, this::installMeta);
    }

    @Test public void missingChannelTargetIsReported() {
        DexSearchEngine.channelTarget = null;
        assertThrows(IllegalStateException.class, this::installChannels);
    }

    @Test public void nonViewMetaAiTargetIsRejected() {
        DexSearchEngine.metaTarget = Object.class;
        assertThrows(IllegalStateException.class, this::installMeta);
    }

    @Test public void nonViewChannelTargetIsRejected() {
        DexSearchEngine.channelTarget = Object.class;
        assertThrows(IllegalStateException.class, this::installChannels);
    }

    private void installMeta() throws Throwable {
        new ChatListCustomizationsHook(context, getClass().getClassLoader(), preferences).hook();
    }

    private void installChannels() {
        new ChannelRecommendationsFilterHook(context, getClass().getClassLoader(), preferences).hook();
    }

    private static int globalLayoutListenerCount(ViewTreeObserver observer) throws Exception {
        // Inspect the real API 24/28/34 observer list. Counting only setVisibility
        // writes would miss duplicate listeners whose second callback sees GONE.
        Field field = ViewTreeObserver.class.getDeclaredField("mOnGlobalLayoutListeners");
        field.setAccessible(true);
        Object listeners = field.get(observer);
        if (listeners == null) return 0;
        Method size = listeners.getClass().getDeclaredMethod("size");
        size.setAccessible(true);
        return (Integer) size.invoke(listeners);
    }

    public static class CountingView extends FrameLayout {
        int visibilityWrites;
        public CountingView(Context context) { super(context); }
        @Override public void setVisibility(int visibility) { visibilityWrites++; super.setVisibility(visibility); }
    }
    public static final class MetaView extends CountingView { public MetaView(Context c) { super(c); } }
    public static final class ChannelView extends CountingView { public ChannelView(Context c) { super(c); } }
}
