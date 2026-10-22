package com.waenhancer.xposed.features.privacy;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Fragment;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.waenhancer.utils.ContactNameResolver;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.Shadows;
import static org.junit.Assert.*;

/** Whole production selector, contract and phone mapper; native host/Xposed boundaries are fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 34}, manifest = Config.NONE, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class CallTypeAndroidTest {
    private static final String PHONE = "15551234567@s.whatsapp.net";
    private static final String LID = "9876543210@lid";
    private Application app;
    private SharedPreferences prefs;
    private CallTypeControllerHook hook;
    private ActivityController<Conversation> activityController;
    private Conversation activity;
    private final List<CountDownLatch> releases = new ArrayList<>();

    public enum State { DESTROYED, INITIALIZED, CREATED, STARTED, RESUMED }
    public abstract static class Lifecycle { public abstract State state(); }
    public static class Registry extends Lifecycle {
        public State value = State.RESUMED;
        @Override public State state() { return value; }
    }
    public static class Jid {
        private final String raw;
        Jid(String raw) { this.raw = raw; }
        public String getRawString() { return raw; }
    }
    public static class ChatJid extends Jid { ChatJid(String raw) { super(raw); } }
    public interface CurrentChat { ChatJid getChatJid(); }
    public interface ConversationApi extends CurrentChat {
        void voice(boolean a, boolean b, boolean c);
        void video(boolean a, boolean b, boolean c);
    }
    public static class Conversation extends Activity implements ConversationApi {
        public Registry registry = new Registry();
        public ChatJid peer = new ChatJid(PHONE);
        public final List<String> calls = new ArrayList<>();
        public final List<Intent> intents = new ArrayList<>();
        public RuntimeException dialFailure;
        public boolean throwOriginal, throwPeer;
        public int additions;
        public Lifecycle getLifecycle() { return registry; }
        public ChatJid getChatJid() {
            if (throwPeer) throw new IllegalStateException("fixture peer unavailable");
            return peer;
        }
        @Override public void voice(boolean a, boolean b, boolean c) {
            calls.add("voice:" + a + ":" + b + ":" + c);
            if (throwOriginal) throw new IllegalStateException("fixture original failed");
        }
        @Override public void video(boolean a, boolean b, boolean c) {
            calls.add("video:" + a + ":" + b + ":" + c);
            if (throwOriginal) throw new IllegalStateException("fixture original failed");
        }
        public void addToCall() { additions++; }
        @Override public void startActivity(Intent intent) {
            if (dialFailure != null) throw dialFailure;
            intents.add(new Intent(intent)); // No external Activity or real call is started.
        }
        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            FrameLayout root = new FrameLayout(this);
            root.setId(0x123456);
            setContentView(root);
        }
        @Override protected void onResume() { super.onResume(); registry.value = State.RESUMED; }
        @Override protected void onPause() { registry.value = State.STARTED; super.onPause(); }
        @Override protected void onDestroy() { registry.value = State.DESTROYED; super.onDestroy(); }
    }
    public static class ConversationFragment extends Fragment {
        public Adapter controller;
        public final Registry registry = new Registry();
        @Override public View onCreateView(LayoutInflater inflater, ViewGroup parent, Bundle state) {
            controller = new Adapter(getActivity(), this);
            return controller;
        }
        @Override public void onResume() { super.onResume(); registry.value = State.RESUMED; }
        @Override public void onPause() { registry.value = State.STARTED; super.onPause(); }
        @Override public void onDestroy() { registry.value = State.DESTROYED; super.onDestroy(); }
    }
    public static class Adapter extends LinearLayout implements ConversationApi {
        public final ConversationFragment fragment;
        public ChatJid peer = new ChatJid(PHONE);
        public final List<String> calls = new ArrayList<>();
        Adapter(Context context, ConversationFragment fragment) {
            super(context); this.fragment = fragment; setMinimumHeight(100); setMinimumWidth(100);
        }
        public ChatJid getChatJid() { return peer; }
        public Lifecycle getLifecycle() { return fragment.registry; }
        public Fragment getHost() { return fragment; }
        public Activity getActivityNullable() { return (Activity) getContext(); }
        public void voice(boolean a, boolean b, boolean c) { calls.add("voice:" + a + ":" + b + ":" + c); }
        public void video(boolean a, boolean b, boolean c) { calls.add("video:" + a + ":" + b + ":" + c); }
    }
    public static class AmbiguousFragment extends Fragment { public Adapter one, two; }
    public interface WrongCalls extends CurrentChat {
        int voice(boolean a, boolean b, boolean c);
        void video(boolean a, boolean b, boolean c);
    }
    public static class NativeConversation extends Conversation {
        @Override public native void voice(boolean a, boolean b, boolean c);
        @Override public void video(boolean a, boolean b, boolean c) {}
    }
    public static class ExtraLifecycle extends Lifecycle {
        public State state() { return State.RESUMED; }
        public State duplicate() { return State.RESUMED; }
    }
    public static class AmbiguousLifecycleConversation extends Conversation {
        @Override public ExtraLifecycle getLifecycle() { return new ExtraLifecycle(); }
        @Override public void voice(boolean a, boolean b, boolean c) {}
        @Override public void video(boolean a, boolean b, boolean c) {}
    }

    @Before public void setup() {
        XposedBridge.reset();
        app = RuntimeEnvironment.getApplication();
        prefs = app.getSharedPreferences("selector", Context.MODE_PRIVATE);
        assertTrue(prefs.edit().clear().putBoolean(CallTypeControllerHook.ENABLED, true).commit());
        app.deleteDatabase("msgstore.db");
    }
    @After public void cleanup() {
        for (CountDownLatch latch : releases) latch.countDown();
        if (prefs != null) prefs.edit().putBoolean(CallTypeControllerHook.ENABLED, false).commit();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        if (activityController != null) activityController.pause().stop().destroy();
        app.deleteDatabase("msgstore.db");
        XposedBridge.reset();
    }
    private static List<Method> declarations(Class<?> api) throws Exception {
        return Arrays.asList(api.getMethod("voice", boolean.class, boolean.class, boolean.class),
                api.getMethod("video", boolean.class, boolean.class, boolean.class));
    }
    private static CallTypeHostContract contract() throws Exception {
        return CallTypeHostContract.resolve(declarations(ConversationApi.class),
                Conversation.class, ConversationFragment.class, Jid.class);
    }
    private void install(CallTypeControllerHook.PhoneLookup lookup) throws Throwable {
        hook = new CallTypeControllerHook(app, getClass().getClassLoader(), prefs, CallTypeAndroidTest::contract, lookup);
        hook.hook();
        activityController = Robolectric.buildActivity(Conversation.class).create().start().resume().visible();
        activity = activityController.get();
        assertTrue(activity.getWindow().getDecorView().isAttachedToWindow());
    }
    private void install() throws Throwable {
        install((context, lid) -> ContactNameResolver.INSTANCE.resolveLidToPhone(context, lid));
    }
    private static Object fire(Object owner, String name, boolean... values) throws Throwable {
        Method method = owner.getClass().getDeclaredMethod(name, boolean.class, boolean.class, boolean.class);
        return XposedBridge.dispatch(method, owner, values[0], values[1], values[2]);
    }
    private AlertDialog dialog() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }
    private void choose(int button) {
        assertTrue(dialog().getButton(button).performClick());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private void idleUntil(java.util.function.BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5); // Wait only in the test driver, never production UI.
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
        assertTrue("asynchronous lookup completed", done.getAsBoolean());
    }
    private void seedMapping(String... phones) {
        try (SQLiteDatabase db = app.openOrCreateDatabase("msgstore.db", 0, null)) {
            db.execSQL("CREATE TABLE jid (_id INTEGER PRIMARY KEY, raw_string TEXT)");
            db.execSQL("CREATE TABLE jid_map (lid_row_id INTEGER, jid_row_id INTEGER)");
            db.execSQL("INSERT INTO jid VALUES(1,?)", new Object[]{LID});
            for (int i = 0; i < phones.length; i++) {
                db.execSQL("INSERT INTO jid VALUES(?,?)", new Object[]{i + 2, phones[i]});
                db.execSQL("INSERT INTO jid_map VALUES(1,?)", new Object[]{i + 2});
            }
        }
    }
    private Adapter attachFragment() {
        ConversationFragment fragment = new ConversationFragment();
        activity.getFragmentManager().beginTransaction().replace(0x123456, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(fragment.controller.isAttachedToWindow());
        assertTrue(fragment.controller.isShown());
        return fragment.controller;
    }
    private static ThreadPoolExecutor worker(CallTypeControllerHook hook) throws Exception {
        Field field = CallTypeControllerHook.class.getDeclaredField("worker");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(hook);
    }

    @Test public void installsAllFourOperationsOnce() throws Throwable {
        install();
        assertEquals(4, XposedBridge.registrations);
        assertEquals(4, XposedBridge.hooks.size());
        hook.hook();
        assertEquals(4, XposedBridge.registrations);
    }
    @Test public void disabledStartupDoesNoDiscoveryOrHookRegistration() throws Throwable {
        AtomicInteger discoveries = new AtomicInteger();
        prefs.edit().putBoolean(CallTypeControllerHook.ENABLED, false).commit();
        hook = new CallTypeControllerHook(app, getClass().getClassLoader(), prefs,
                () -> { discoveries.incrementAndGet(); return contract(); }, (context, lid) -> null);
        hook.hook();
        assertEquals(0, discoveries.get());
        assertEquals(0, XposedBridge.registrations);
        assertEquals(0, worker(hook).getPoolSize());
        prefs.edit().putBoolean(CallTypeControllerHook.ENABLED, true).commit();
        assertEquals(0, XposedBridge.registrations);
        // A new startup/explicit hook pass resolves once, matching the existing restart contract.
        hook.hook();
        assertEquals(1, discoveries.get());
        assertEquals(4, XposedBridge.registrations);
    }
    @Test public void disabledOrWrongPreferenceTypeLeavesTheOriginalUntouched() throws Throwable {
        AtomicInteger lookups = new AtomicInteger();
        install((context, lid) -> { lookups.incrementAndGet(); return "15551234567"; });
        prefs.edit().putBoolean(CallTypeControllerHook.ENABLED, false).commit();
        assertNull(fire(activity, "voice", true, false, true));
        prefs.edit().putString(CallTypeControllerHook.ENABLED, "true").commit();
        assertNull(fire(activity, "video", false, true, false));
        assertEquals(Arrays.asList("voice:true:false:true", "video:false:true:false"), activity.calls);
        assertEquals(0, lookups.get());
    }
    @Test public void whatsappChoiceKeepsEveryVoiceArgumentAndVoidReturn() throws Throwable {
        install();
        for (int i = 0; i < 8; i++) {
            boolean a = (i & 1) != 0, b = (i & 2) != 0, c = (i & 4) != 0;
            assertNull(fire(activity, "voice", a, b, c));
            assertEquals(i, activity.calls.size());
            choose(AlertDialog.BUTTON_NEGATIVE);
            assertEquals("voice:" + a + ":" + b + ":" + c, activity.calls.get(i));
        }
        assertEquals(8, XposedBridge.originals);
        assertTrue(activity.intents.isEmpty());
    }
    @Test public void videoAndFragmentOperationsResumeTheirOwnOriginal() throws Throwable {
        install();
        fire(activity, "video", true, false, true);
        choose(AlertDialog.BUTTON_NEGATIVE);
        Adapter adapter = attachFragment();
        fire(adapter, "voice", false, true, true);
        choose(AlertDialog.BUTTON_NEGATIVE);
        fire(adapter, "video", true, true, false);
        choose(AlertDialog.BUTTON_NEGATIVE);
        assertEquals(Arrays.asList("video:true:false:true"), activity.calls);
        assertEquals(Arrays.asList("voice:false:true:true", "video:true:true:false"), adapter.calls);
        assertEquals(3, XposedBridge.originals);
    }
    @Test public void phoneChoiceOnlyOpensAConfirmingDialer() throws Throwable {
        install();
        fire(activity, "voice", false, false, false);
        choose(AlertDialog.BUTTON_POSITIVE);
        assertEquals(1, activity.intents.size());
        Intent intent = activity.intents.get(0);
        assertEquals(Intent.ACTION_DIAL, intent.getAction());
        assertEquals("tel", intent.getData().getScheme());
        assertEquals("+15551234567", intent.getData().getSchemeSpecificPart());
        assertNull(intent.getComponent());
        assertEquals(0, XposedBridge.originals);
        assertTrue(activity.calls.isEmpty());
    }
    @Test public void cancelAndDismissNeverCallEitherDestination() throws Throwable {
        install();
        fire(activity, "voice", false, false, false);
        choose(AlertDialog.BUTTON_NEUTRAL);
        fire(activity, "video", false, false, false);
        dialog().cancel();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        fire(activity, "voice", true, true, true);
        dialog().dismiss();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(activity.intents.isEmpty());
        assertEquals(0, XposedBridge.originals);
    }
    @Test public void missingDialerKeepsWhatsappAvailableWithoutCallingItAutomatically() throws Throwable {
        install();
        activity.dialFailure = new ActivityNotFoundException("fixture has no dialer");
        fire(activity, "voice", true, false, false);
        choose(AlertDialog.BUTTON_POSITIVE);
        assertTrue(dialog().isShowing());
        assertFalse(dialog().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        assertEquals(0, XposedBridge.originals);
        choose(AlertDialog.BUTTON_NEGATIVE);
        assertEquals(Arrays.asList("voice:true:false:false"), activity.calls);
        assertTrue(activity.intents.isEmpty());
    }
    @Test public void dialerSecurityFailureCanBeCanceled() throws Throwable {
        install();
        activity.dialFailure = new SecurityException("fixture blocked dialer");
        fire(activity, "video", false, false, true);
        choose(AlertDialog.BUTTON_POSITIVE);
        choose(AlertDialog.BUTTON_NEUTRAL);
        assertTrue(activity.calls.isEmpty());
        assertTrue(activity.intents.isEmpty());
    }
    @Test public void groupsUnsupportedNamespacesAndNonNumbersFallThrough() throws Throwable {
        AtomicInteger lookups = new AtomicInteger();
        install((context, lid) -> { lookups.incrementAndGet(); return null; });
        String[] peers = {"12345-123456@g.us", "11111111@newsletter", "12345", "status@broadcast",
                "15551234567@lid.evil", "1234@s.whatsapp.net", "0123456789@s.whatsapp.net", "x@s.whatsapp.net"};
        for (String peer : peers) {
            activity.peer = new ChatJid(peer);
            fire(activity, "voice", false, false, true);
        }
        assertEquals(peers.length, XposedBridge.originals);
        assertEquals(0, lookups.get());
        assertTrue(activity.intents.isEmpty());
    }
    @Test public void knownLidUsesTheExactCurrentDatabaseEdgeOffTheUiThread() throws Throwable {
        seedMapping(PHONE);
        AtomicBoolean background = new AtomicBoolean();
        install((context, lid) -> {
            background.set(Looper.myLooper() != Looper.getMainLooper());
            return ContactNameResolver.INSTANCE.resolveLidToPhone(context, lid);
        });
        activity.peer = new ChatJid(LID);
        fire(activity, "voice", false, true, false);
        idleUntil(() -> ShadowAlertDialog.getLatestAlertDialog() != null
                && ShadowAlertDialog.getLatestAlertDialog().isShowing());
        choose(AlertDialog.BUTTON_POSITIVE);
        assertTrue(background.get());
        assertEquals("tel", activity.intents.get(0).getData().getScheme());
        assertEquals("+15551234567", activity.intents.get(0).getData().getSchemeSpecificPart());
        assertEquals(0, XposedBridge.originals);
    }
    @Test public void ambiguousLidCannotSelectOneOfTwoPhoneNumbers() throws Throwable {
        seedMapping(PHONE, "15559876543@s.whatsapp.net");
        install();
        activity.peer = new ChatJid(LID);
        fire(activity, "video", false, false, true);
        idleUntil(() -> !activity.calls.isEmpty());
        assertEquals(Arrays.asList("video:false:false:true"), activity.calls);
        assertTrue(activity.intents.isEmpty());
    }
    @Test public void unmappedLidNeverDialsItsNumericUserPart() throws Throwable {
        install();
        activity.peer = new ChatJid(LID);
        fire(activity, "voice", true, false, false);
        idleUntil(() -> !activity.calls.isEmpty());
        assertEquals(1, XposedBridge.originals);
        assertTrue(activity.intents.isEmpty());
    }
    @Test public void invalidPhoneLookupResultContinuesOnlyWhatsapp() throws Throwable {
        install((context, lid) -> "1555;999");
        activity.peer = new ChatJid(LID);
        fire(activity, "voice", false, false, false);
        idleUntil(() -> !activity.calls.isEmpty());
        assertEquals(1, XposedBridge.originals);
        assertTrue(activity.intents.isEmpty());
    }
    @Test public void changedPeerInvalidatesBothChoices() throws Throwable {
        install();
        fire(activity, "voice", false, true, false);
        activity.peer = new ChatJid("15550000000@s.whatsapp.net");
        choose(AlertDialog.BUTTON_POSITIVE);
        fire(activity, "video", true, false, true);
        activity.peer = new ChatJid(PHONE);
        choose(AlertDialog.BUTTON_NEGATIVE);
        assertEquals(0, XposedBridge.originals);
        assertTrue(activity.intents.isEmpty());
    }
    @Test public void activityPauseDismissesTheSelectorAndInvalidatesItsButtons() throws Throwable {
        install();
        fire(activity, "voice", false, false, false);
        AlertDialog dialog = dialog();
        activityController.pause();
        assertFalse(dialog.isShowing());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertTrue(activity.intents.isEmpty());
        assertEquals(0, XposedBridge.originals);
        activityController.resume();
    }
    @Test public void fragmentDetachmentCancelsWithoutRelyingOnItsRetainedActivity() throws Throwable {
        install();
        Adapter adapter = attachFragment();
        fire(adapter, "voice", false, false, false);
        AlertDialog dialog = dialog();
        activity.getFragmentManager().beginTransaction().remove(adapter.fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        assertSame(activity, adapter.getActivityNullable()); // Mirrors the host Context fallback.
        assertFalse(dialog.isShowing());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertTrue(adapter.calls.isEmpty());
        assertEquals(0, XposedBridge.originals);
    }
    @Test public void nonResumedFragmentOrReplacedControllerCannotContinueAStaleChoice() throws Throwable {
        install();
        Adapter adapter = attachFragment();
        fire(adapter, "video", false, true, false);
        adapter.fragment.registry.value = State.STARTED;
        choose(AlertDialog.BUTTON_NEGATIVE);
        adapter.fragment.registry.value = State.RESUMED;
        fire(adapter, "voice", false, false, false);
        adapter.fragment.controller = new Adapter(activity, adapter.fragment);
        choose(AlertDialog.BUTTON_POSITIVE);
        assertTrue(adapter.calls.isEmpty());
        assertTrue(activity.intents.isEmpty());
    }
    @Test public void repeatedEventsKeepOneSelectorAndOneOriginalOperation() throws Throwable {
        install();
        fire(activity, "voice", true, false, true);
        AlertDialog first = dialog();
        for (int i = 0; i < 50; i++) fire(activity, "video", false, true, false);
        assertSame(first, dialog());
        choose(AlertDialog.BUTTON_NEGATIVE);
        assertEquals(Arrays.asList("voice:true:false:true"), activity.calls);
        assertEquals(1, XposedBridge.originals);
    }
    @Test public void disablingTheSettingCancelsAnAlreadyVisibleChoice() throws Throwable {
        install();
        fire(activity, "voice", false, false, false);
        AlertDialog dialog = dialog();
        prefs.edit().putBoolean(CallTypeControllerHook.ENABLED, false).commit();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(dialog.isShowing());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertEquals(0, XposedBridge.originals);
    }
    @Test public void thrownOriginalIsNotRetriedOrReplacedWithAPhoneCall() throws Throwable {
        install();
        activity.throwOriginal = true;
        fire(activity, "video", true, true, true);
        AlertDialog dialog = dialog();
        choose(AlertDialog.BUTTON_NEGATIVE);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertEquals(Arrays.asList("video:true:true:true"), activity.calls);
        assertEquals(1, XposedBridge.originals);
        assertTrue(activity.intents.isEmpty());
        assertTrue(XposedBridge.logs.stream().anyMatch(s -> s.contains("original call failed")));
    }
    @Test public void unavailablePeerAtChoiceCancelsInsteadOfGuessing() throws Throwable {
        install();
        fire(activity, "voice", false, false, false);
        activity.throwPeer = true;
        choose(AlertDialog.BUTTON_NEGATIVE);
        assertEquals(0, XposedBridge.originals);
    }
    @Test public void lookupTimeoutContinuesOnceAndIgnoresALateMapping() throws Throwable {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        releases.add(release);
        install((context, lid) -> {
            entered.countDown();
            boolean done = false;
            while (!done) try { release.await(); done = true; } catch (InterruptedException ignored) {}
            return "15551234567";
        });
        activity.peer = new ChatJid(LID);
        fire(activity, "voice", true, false, true);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CallTypeControllerHook.LOOKUP_TIMEOUT_MS));
        assertEquals(Arrays.asList("voice:true:false:true"), activity.calls);
        release.countDown();
        idleUntil(() -> workerUnchecked().getActiveCount() == 0);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, XposedBridge.originals);
        assertTrue(activity.intents.isEmpty());
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialog == null || !dialog.isShowing());
    }
    private ThreadPoolExecutor workerUnchecked() {
        try { return worker(hook); } catch (Exception failure) { throw new AssertionError(failure); }
    }
    @Test public void slowCanceledLookupsHaveOneWorkerAndAtMostOneQueuedTask() throws Throwable {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        releases.add(release);
        install((context, lid) -> {
            entered.countDown();
            boolean done = false;
            while (!done) try { release.await(); done = true; } catch (InterruptedException ignored) {}
            return "15551234567";
        });
        activity.peer = new ChatJid(LID);
        fire(activity, "voice", false, false, false);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        for (int i = 0; i < 50; i++) {
            prefs.edit().putBoolean(CallTypeControllerHook.ENABLED, false).commit();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            prefs.edit().putBoolean(CallTypeControllerHook.ENABLED, true).commit();
            fire(activity, "voice", false, false, false);
            assertTrue(worker(hook).getQueue().size() <= 1);
            assertTrue(worker(hook).getPoolSize() <= 1);
        }
        prefs.edit().putBoolean(CallTypeControllerHook.ENABLED, false).commit();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        release.countDown();
        idleUntil(() -> workerUnchecked().getActiveCount() == 0);
        assertEquals(0, XposedBridge.originals);
        assertTrue(activity.intents.isEmpty());
    }
    @Test public void navigationDuringLookupNeverCallsTheNewChat() throws Throwable {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        releases.add(release);
        install((context, lid) -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException ignored) {}
            return "15551234567";
        });
        activity.peer = new ChatJid(LID);
        fire(activity, "voice", false, false, false);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        activity.peer = new ChatJid(PHONE);
        release.countDown();
        idleUntil(() -> workerUnchecked().getActiveCount() == 0);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600));
        assertEquals(0, XposedBridge.originals);
        assertTrue(activity.intents.isEmpty());
    }
    @Test public void failedInstallationRollsBackAndCannotReactivateRetainedCallbacks() throws Throwable {
        XposedBridge.failRegistration = 3;
        XposedBridge.failUnhook = true;
        hook = new CallTypeControllerHook(app, getClass().getClassLoader(), prefs,
                CallTypeAndroidTest::contract, (context, lid) -> null);
        try { hook.hook(); fail("installation must fail"); } catch (IllegalStateException expected) {}
        assertEquals(3, XposedBridge.registrations);
        assertEquals(2, XposedBridge.hooks.size());
        XposedBridge.failRegistration = -1;
        try { hook.hook(); fail("failed installation must remain inert"); } catch (IllegalStateException expected) {}
        assertEquals(3, XposedBridge.registrations);
        activityController = Robolectric.buildActivity(Conversation.class).create().start().resume().visible();
        activity = activityController.get();
        fire(activity, "voice", true, false, false);
        assertEquals(Arrays.asList("voice:true:false:false"), activity.calls);
        assertEquals(1, XposedBridge.originals);
    }
    @Test public void badOrAmbiguousContractsCannotInstall() throws Exception {
        List<Method> calls = declarations(ConversationApi.class);
        try {
            CallTypeHostContract.resolve(calls, Conversation.class, AmbiguousFragment.class, Jid.class);
            fail("two adapters must be rejected");
        } catch (ReflectiveOperationException expected) {}
        try {
            CallTypeHostContract.resolve(declarations(WrongCalls.class), Conversation.class, ConversationFragment.class, Jid.class);
            fail("non-void call must be rejected");
        } catch (ReflectiveOperationException expected) {}
        try {
            CallTypeHostContract.resolve(calls, NativeConversation.class, ConversationFragment.class, Jid.class);
            fail("native callback must be rejected");
        } catch (ReflectiveOperationException expected) {}
        try {
            CallTypeHostContract.resolve(calls, AmbiguousLifecycleConversation.class, ConversationFragment.class, Jid.class);
            fail("ambiguous state getter must be rejected");
        } catch (ReflectiveOperationException expected) {}
        assertEquals(0, XposedBridge.registrations);
    }
    @Test public void unavailableNativeDiscoveryCannotPretendTheHookWasInstalled() throws Throwable {
        hook = new CallTypeControllerHook(app, getClass().getClassLoader(), prefs);
        try { hook.hook(); fail("native boundary unavailable"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("DexKit")); }
        assertEquals(0, XposedBridge.registrations);
    }
    @Test public void addToCallRemainsOutsideTheSelector() throws Throwable {
        install();
        XposedBridge.dispatch(Conversation.class.getDeclaredMethod("addToCall"), activity);
        assertEquals(1, activity.additions);
        assertEquals(1, XposedBridge.originals);
        assertTrue(activity.intents.isEmpty());
    }
}
