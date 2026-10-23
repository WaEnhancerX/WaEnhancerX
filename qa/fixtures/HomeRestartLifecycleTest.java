package com.waenhancer.xposed.features.homescreen;

import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.view.WindowManager;
import com.waenhancer.xposed.bridge.client.PreferenceBridgeClient;
import com.waenhancer.xposed.core.components.WaexBottomSheet;
import com.waenhancer.xposed.utils.WhatsAppProcessRestarter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.shadows.ShadowViewGroup;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;
import static org.junit.Assert.*;
import static org.robolectric.util.reflector.Reflector.reflector;

/** Full production hook and bridge; only the process termination endpoint is replaced. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 34}, manifest = Config.NONE,
        shadows = {HomeRestartLifecycleTest.RejectableView.class, HomeRestartLifecycleTest.RejectableHandler.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class HomeRestartLifecycleTest {
    public static class TestActivity extends Activity {}

    /** Targeted posting failure; every accepted call uses Robolectric's real View path. */
    @Implements(className = "com.android.internal.policy.DecorView", isInAndroidSdk = false)
    public static class RejectableView extends ShadowViewGroup {
        static View target;
        static int remaining;
        @Implementation @Override protected boolean postDelayed(Runnable action, long delay) {
            if (realView == target && remaining > 0) { remaining--; return false; }
            return super.postDelayed(action, delay);
        }
    }

    /** Faults affect only this hook's callbacks; accepted messages use Android's queue. */
    @Implements(Handler.class)
    public static class RejectableHandler {
        @RealObject Handler handler;
        static volatile int reject, throwRemaining;
        static final AtomicInteger attempted = new AtomicInteger();
        static final AtomicInteger accepted = new AtomicInteger();
        @ForType(Handler.class) interface DirectHandler {
            @Direct boolean sendMessageAtTime(Message message, long uptime);
        }
        @Implementation protected boolean sendMessageAtTime(Message message, long uptime) {
            Runnable callback = message.getCallback();
            boolean owned = callback != null && callback.getClass().getName()
                    .startsWith(HomeScreenHeaderActionsHook.class.getName());
            if (owned) attempted.incrementAndGet();
            if (owned && reject > 0) { reject--; return false; }
            if (owned && throwRemaining > 0) {
                throwRemaining--;
                throw new IllegalStateException("Injected handler failure");
            }
            boolean posted = reflector(DirectHandler.class, handler).sendMessageAtTime(message, uptime);
            if (owned && posted) accepted.incrementAndGet();
            return posted;
        }
    }

    /** Controlled acknowledgement boundary; production PreferenceBridgeClient remains intact. */
    public static class AcknowledgingProvider extends ContentProvider {
        static volatile CountDownLatch entered, release, completed;
        static volatile boolean success;
        static volatile boolean calledOnMain;
        static volatile Bundle lastRequest;
        static Bundle saved;
        static final AtomicInteger writes = new AtomicInteger();
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            if ("get_all_preferences".equals(method)) {
                Bundle result = new Bundle();
                synchronized (AcknowledgingProvider.class) { result.putBundle("values", new Bundle(saved)); }
                return result;
            }
            if (!"apply_preferences".equals(method)) throw new AssertionError("Unexpected method " + method);
            lastRequest = extras;
            writes.incrementAndGet();
            calledOnMain = Looper.myLooper() == Looper.getMainLooper();
            entered.countDown();
            try { assertTrue("Provider release timed out", release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            Bundle response = new Bundle();
            if (success) synchronized (AcknowledgingProvider.class) {
                ArrayList<Bundle> operations = extras.getParcelableArrayList("operations");
                assertNotNull(operations);
                for (Bundle operation : operations) {
                    assertEquals("boolean", operation.getString("type"));
                    saved.putBoolean(operation.getString("key"), operation.getBoolean("value"));
                }
            }
            response.putBoolean("success", success);
            completed.countDown();
            return response;
        }
        @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o) { return null; }
        @Override public String getType(Uri u) { return null; }
        @Override public Uri insert(Uri u,ContentValues v) { return null; }
        @Override public int delete(Uri u,String s,String[] a) { return 0; }
        @Override public int update(Uri u,ContentValues v,String s,String[] a) { return 0; }
    }

    private final List<ActivityController<TestActivity>> controllers = new ArrayList<>();
    private Context context;

    @Before public void before() throws Exception {
        context = RuntimeEnvironment.getApplication();
        WhatsAppProcessRestarter.reset();
        WaexBottomSheet.reset();
        RejectableView.target = null;
        RejectableView.remaining = 0;
        RejectableHandler.reject = 0;
        RejectableHandler.throwRemaining = 0;
        RejectableHandler.accepted.set(0);
        RejectableHandler.attempted.set(0);
        de.robv.android.xposed.XposedBridge.logs.clear();
        AcknowledgingProvider.entered = new CountDownLatch(1);
        AcknowledgingProvider.release = new CountDownLatch(1);
        AcknowledgingProvider.completed = new CountDownLatch(1);
        AcknowledgingProvider.success = true;
        AcknowledgingProvider.calledOnMain = false;
        AcknowledgingProvider.lastRequest = null;
        AcknowledgingProvider.saved = new Bundle();
        AcknowledgingProvider.writes.set(0);
        resetPending();
    }

    @After public void after() throws Exception {
        AcknowledgingProvider.release.countDown();
        for (ActivityController<TestActivity> controller : controllers) {
            if (!controller.get().isDestroyed()) {
                Activity activity = controller.get();
                View decor = activity.getWindow().getDecorView();
                // A test deliberately removed this window. Restore it so the
                // controller can perform its normal destroy/window cleanup once.
                if (!decor.isAttachedToWindow()) {
                    ((WindowManager) activity.getSystemService(Context.WINDOW_SERVICE))
                            .addView(decor, activity.getWindow().getAttributes());
                }
                controller.pause().stop().destroy();
            }
        }
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        resetPending();
        WaexBottomSheet.reset();
        RejectableView.target = null;
    }

    private static void resetPending() throws Exception {
        Field field = HomeScreenHeaderActionsHook.class.getDeclaredField("RESTART_SCHEDULED");
        field.setAccessible(true);
        Object pending = field.get(null);
        if (pending instanceof AtomicBoolean) ((AtomicBoolean) pending).set(false);
        else if (pending instanceof AtomicReference<?>) ((AtomicReference<?>) pending).set(null);
    }

    private ActivityController<TestActivity> activity() {
        ActivityController<TestActivity> controller = Robolectric.buildActivity(TestActivity.class).setup().visible();
        controllers.add(controller);
        assertTrue("Fixture activity must be attached", controller.get().getWindow().getDecorView().isAttachedToWindow());
        return controller;
    }

    private HomeScreenHeaderActionsHook hook(SharedPreferences preferences) {
        return new HomeScreenHeaderActionsHook(context, getClass().getClassLoader(), preferences);
    }

    private HomeScreenHeaderActionsHook hook() {
        return hook(context.getSharedPreferences("home_restart", Context.MODE_PRIVATE));
    }

    private static void call(HomeScreenHeaderActionsHook hook, String method, Activity activity) throws Exception {
        Method target = HomeScreenHeaderActionsHook.class.getDeclaredMethod(method, Activity.class);
        target.setAccessible(true);
        try { target.invoke(hook, activity); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            if (e.getCause() instanceof Error) throw (Error) e.getCause();
            throw e;
        }
    }

    private static void deliver() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    }

    private PreferenceBridgeClient bridge() {
        ProviderInfo info = new ProviderInfo();
        info.authority = PreferenceBridgeClient.AUTHORITY;
        Robolectric.buildContentProvider(AcknowledgingProvider.class).create(info);
        return new PreferenceBridgeClient(context, null);
    }

    private static void awaitPosted(int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (RejectableHandler.accepted.get() < count && System.nanoTime() < deadline) Thread.sleep(1L);
        assertTrue("The completion callback was never posted", RejectableHandler.accepted.get() >= count);
    }

    private static void releaseWriteAndDeliver(int postedCount) throws Exception {
        AcknowledgingProvider.release.countDown();
        assertTrue(AcknowledgingProvider.completed.await(2, TimeUnit.SECONDS));
        awaitPosted(postedCount);
        deliver();
    }

    private void assertToggle(String prompt, String key, boolean previous, boolean expected) throws Exception {
        AcknowledgingProvider.saved.putBoolean(key, previous);
        PreferenceBridgeClient preferences = bridge();
        call(hook(preferences), prompt, activity().get());
        WaexBottomSheet.confirm();
        assertTrue(AcknowledgingProvider.entered.await(2, TimeUnit.SECONDS));
        assertFalse(AcknowledgingProvider.calledOnMain);
        deliver();
        assertEquals("No restart before this write is acknowledged", 0, WhatsAppProcessRestarter.calls.size());
        releaseWriteAndDeliver(1);
        assertEquals(1, WhatsAppProcessRestarter.calls.size());
        assertEquals(expected, preferences.getBoolean(key, !expected));
        ArrayList<Bundle> operations = AcknowledgingProvider.lastRequest.getParcelableArrayList("operations");
        assertEquals(1, operations.size());
        assertEquals(key, operations.get(0).getString("key"));
        assertEquals(expected, operations.get(0).getBoolean("value"));
    }

    @Test public void aliveRequestIsDeferredAndDuplicateCallbacksDoNotRestartTwice() throws Exception {
        Activity activity = activity().get();
        HomeScreenHeaderActionsHook hook = hook();
        call(hook, "forceRestartWhatsApp", activity);
        call(hook, "forceRestartWhatsApp", activity);
        assertEquals(0, WhatsAppProcessRestarter.calls.size());
        deliver();
        assertEquals(1, WhatsAppProcessRestarter.calls.size());
        assertSame(activity, WhatsAppProcessRestarter.calls.get(0).get());
    }

    @Test public void rejectedDecorPostDoesNotPermanentlyBlockAnotherRequest() throws Exception {
        Activity activity = activity().get();
        HomeScreenHeaderActionsHook hook = hook();
        RejectableView.target = activity.getWindow().getDecorView();
        RejectableView.remaining = 1;
        call(hook, "forceRestartWhatsApp", activity);
        call(hook, "forceRestartWhatsApp", activity);
        deliver();
        assertEquals("Rejected enqueue must leave a usable restart action", 1, WhatsAppProcessRestarter.calls.size());
    }

    @Test public void destroyedActivityIsNotRestartedAndReplacementCanRequestAgain() throws Exception {
        ActivityController<TestActivity> old = activity();
        HomeScreenHeaderActionsHook hook = hook();
        call(hook, "forceRestartWhatsApp", old.get());
        old.pause().stop().destroy();
        deliver();
        assertEquals("A destroyed Activity must not execute the restart", 0, WhatsAppProcessRestarter.calls.size());
        Activity current = activity().get();
        call(hook, "forceRestartWhatsApp", current);
        deliver();
        assertEquals(1, WhatsAppProcessRestarter.calls.size());
        assertSame(current, WhatsAppProcessRestarter.calls.get(0).get());
    }

    @Test public void detachedDecorCancelsTheRequestAndDoesNotBlockNextActivity() throws Exception {
        Activity old = activity().get();
        HomeScreenHeaderActionsHook hook = hook();
        call(hook, "forceRestartWhatsApp", old);
        ((WindowManager) old.getSystemService(Context.WINDOW_SERVICE)).removeViewImmediate(old.getWindow().getDecorView());
        assertFalse(old.getWindow().getDecorView().isAttachedToWindow());
        deliver();
        assertEquals("Detached decor must not trigger a stale restart", 0, WhatsAppProcessRestarter.calls.size());
        Activity current = activity().get();
        call(hook, "forceRestartWhatsApp", current);
        deliver();
        assertEquals(1, WhatsAppProcessRestarter.calls.size());
        assertSame(current, WhatsAppProcessRestarter.calls.get(0).get());
    }

    @Test public void alreadyDestroyedActivityDoesNotConsumeTheNextValidRequest() throws Exception {
        ActivityController<TestActivity> old = activity();
        old.pause().stop().destroy();
        HomeScreenHeaderActionsHook hook = hook();
        call(hook, "forceRestartWhatsApp", old.get());
        Activity current = activity().get();
        call(hook, "forceRestartWhatsApp", current);
        deliver();
        assertEquals(1, WhatsAppProcessRestarter.calls.size());
        assertSame(current, WhatsAppProcessRestarter.calls.get(0).get());
    }

    @Test public void changingGhostWaitsForTheRealBridgeAcknowledgementBeyond350ms() throws Exception {
        PreferenceBridgeClient preferences = bridge();
        Activity activity = activity().get();
        HomeScreenHeaderActionsHook hook = hook(preferences);
        call(hook, "promptGhostModeSheet", activity);
        WaexBottomSheet.confirm();
        assertTrue("Write did not reach provider", AcknowledgingProvider.entered.await(2, TimeUnit.SECONDS));
        assertFalse("Persistence must not block the UI", AcknowledgingProvider.calledOnMain);
        deliver();
        assertEquals("Elapsed time does not acknowledge an asynchronous preference write", 0, WhatsAppProcessRestarter.calls.size());
        releaseWriteAndDeliver(1);
        assertEquals(1, WhatsAppProcessRestarter.calls.size());
        assertTrue(preferences.getBoolean("ghostmode_active", false));
    }

    @Test public void rejectedMainPostReportsFailureAndAllowsRetry() throws Exception {
        HomeScreenHeaderActionsHook hook = hook();
        Activity activity = activity().get();
        RejectableHandler.reject = 1;
        call(hook, "forceRestartWhatsApp", activity);
        assertEquals("Restart operation unavailable", ShadowToast.getTextOfLatestToast());
        call(hook, "forceRestartWhatsApp", activity);
        deliver();
        assertEquals(1, WhatsAppProcessRestarter.calls.size());
    }

    @Test public void throwingMainPostDoesNotCrashOrConsumeTheNextRequest() throws Exception {
        HomeScreenHeaderActionsHook hook = hook();
        Activity activity = activity().get();
        RejectableHandler.throwRemaining = 1;
        try { call(hook, "forceRestartWhatsApp", activity); }
        catch (RuntimeException failure) { throw new AssertionError("Posting failure escaped the menu callback", failure); }
        assertEquals("Restart operation unavailable", ShadowToast.getTextOfLatestToast());
        call(hook, "forceRestartWhatsApp", activity);
        deliver();
        assertEquals(1, WhatsAppProcessRestarter.calls.size());
    }

    @Test public void rejectedPreferenceWriteIsVisibleAndTheSameActionCanRetry() throws Exception {
        PreferenceBridgeClient preferences = bridge();
        HomeScreenHeaderActionsHook hook = hook(preferences);
        Activity activity = activity().get();
        AcknowledgingProvider.success = false;
        call(hook, "promptGhostModeSheet", activity);
        WaexBottomSheet.confirm();
        assertTrue(AcknowledgingProvider.entered.await(2, TimeUnit.SECONDS));
        releaseWriteAndDeliver(1);
        assertEquals(0, WhatsAppProcessRestarter.calls.size());
        assertEquals("Unable to save setting", ShadowToast.getTextOfLatestToast());
        assertFalse(preferences.getBoolean("ghostmode_active", false));
        AcknowledgingProvider.success = true;
        call(hook, "promptGhostModeSheet", activity);
        WaexBottomSheet.confirm();
        awaitPosted(2);
        deliver();
        assertEquals(1, WhatsAppProcessRestarter.calls.size());
        assertTrue(preferences.getBoolean("ghostmode_active", false));
    }

    @Test public void ghostDeactivationIsAcknowledgedBeforeRestart() throws Exception {
        assertToggle("promptGhostModeSheet", "ghostmode_active", true, false);
    }

    @Test public void freezeActivationIsAcknowledgedBeforeRestart() throws Exception {
        assertToggle("promptFreezeLastSeenSheet", "freeze_last_seen_active", false, true);
    }

    @Test public void freezeDeactivationIsAcknowledgedBeforeRestart() throws Exception {
        assertToggle("promptFreezeLastSeenSheet", "freeze_last_seen_active", true, false);
    }

    @Test public void dndActivationIsAcknowledgedBeforeRestart() throws Exception {
        assertToggle("promptDndModeSheet", "dnd_mode", false, true);
    }

    @Test public void dndDeactivationIsAcknowledgedBeforeRestart() throws Exception {
        assertToggle("promptDndModeSheet", "dnd_mode", true, false);
    }

    @Test public void differentSettingWhileWaitingIsExplicitlyRejectedThenCanRetry() throws Exception {
        PreferenceBridgeClient preferences = bridge();
        HomeScreenHeaderActionsHook hook = hook(preferences);
        Activity activity = activity().get();
        call(hook, "promptGhostModeSheet", activity);
        WaexBottomSheet.confirm();
        assertTrue(AcknowledgingProvider.entered.await(2, TimeUnit.SECONDS));
        call(hook, "promptFreezeLastSeenSheet", activity);
        WaexBottomSheet.confirm();
        assertEquals("A restart is already pending. This change was not saved; try again afterward.",
                ShadowToast.getTextOfLatestToast());
        assertEquals(1, AcknowledgingProvider.writes.get());
        assertFalse(preferences.getBoolean("freeze_last_seen_active", false));
        releaseWriteAndDeliver(1);
        assertEquals(1, WhatsAppProcessRestarter.calls.size());
        assertTrue(preferences.getBoolean("ghostmode_active", false));
        assertFalse(preferences.getBoolean("freeze_last_seen_active", false));
        call(hook, "promptFreezeLastSeenSheet", activity);
        WaexBottomSheet.confirm();
        awaitPosted(2);
        deliver();
        assertEquals(2, WhatsAppProcessRestarter.calls.size());
        assertEquals(2, AcknowledgingProvider.writes.get());
        assertTrue(preferences.getBoolean("freeze_last_seen_active", false));
    }

    @Test public void restartEndpointExceptionReleasesTheGuardAndAllowsRetry() throws Exception {
        HomeScreenHeaderActionsHook hook = hook();
        Activity activity = activity().get();
        WhatsAppProcessRestarter.fail = true;
        call(hook, "forceRestartWhatsApp", activity);
        try { deliver(); }
        catch (RuntimeException failure) { throw new AssertionError("Restart endpoint failure escaped to main", failure); }
        assertEquals("Restart operation unavailable", ShadowToast.getTextOfLatestToast());
        WhatsAppProcessRestarter.fail = false;
        call(hook, "forceRestartWhatsApp", activity);
        deliver();
        assertEquals(2, WhatsAppProcessRestarter.calls.size());
    }

    @Test public void reentrantMenuCallbacksDuringRestartAreDeduplicated() throws Exception {
        HomeScreenHeaderActionsHook hook = hook();
        Activity activity = activity().get();
        WhatsAppProcessRestarter.onRestart = () -> {
            try { call(hook, "forceRestartWhatsApp", activity); }
            catch (Exception failure) { throw new AssertionError(failure); }
        };
        call(hook, "forceRestartWhatsApp", activity);
        deliver();
        assertEquals(1, WhatsAppProcessRestarter.calls.size());
    }

    @Test public void destroyWhileWriteIsBlockedAllowsNewRequestAndIgnoresLateResult() throws Exception {
        PreferenceBridgeClient preferences = bridge();
        HomeScreenHeaderActionsHook hook = hook(preferences);
        ActivityController<TestActivity> old = activity();
        call(hook, "promptGhostModeSheet", old.get());
        WaexBottomSheet.confirm();
        assertTrue(AcknowledgingProvider.entered.await(2, TimeUnit.SECONDS));
        old.pause().stop().destroy();
        Activity replacement = activity().get();
        call(hook, "forceRestartWhatsApp", replacement);
        AcknowledgingProvider.release.countDown();
        assertTrue(AcknowledgingProvider.completed.await(2, TimeUnit.SECONDS));
        deliver();
        assertEquals(1, WhatsAppProcessRestarter.calls.size());
        assertSame(replacement, WhatsAppProcessRestarter.calls.get(0).get());
    }

    @Test public void secondaryCleanupPostFailureDoesNotHideTheFailedRestartOrBlockRetry() throws Exception {
        HomeScreenHeaderActionsHook hook = hook(bridge());
        Activity activity = activity().get();
        call(hook, "promptGhostModeSheet", activity);
        WaexBottomSheet.confirm();
        assertTrue(AcknowledgingProvider.entered.await(2, TimeUnit.SECONDS));
        RejectableHandler.throwRemaining = 2;
        AcknowledgingProvider.release.countDown();
        assertTrue(AcknowledgingProvider.completed.await(2, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (de.robv.android.xposed.XposedBridge.logs.stream().noneMatch(s -> s.contains("Could not enqueue restart"))
                && System.nanoTime() < deadline) Thread.sleep(1L);
        assertTrue("A secondary cleanup exception hid the posting failure",
                de.robv.android.xposed.XposedBridge.logs.stream().anyMatch(s -> s.contains("Could not enqueue restart")));
        assertEquals(0, WhatsAppProcessRestarter.calls.size());
        RejectableHandler.throwRemaining = 0;
        call(hook, "forceRestartWhatsApp", activity);
        deliver();
        assertEquals(1, WhatsAppProcessRestarter.calls.size());
    }
}
