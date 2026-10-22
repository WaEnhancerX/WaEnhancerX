package fixture;

import android.app.Application;
import android.content.*;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import com.waenhancer.xposed.MainHook;
import com.waenhancer.xposed.core.FeatureRegistry;
import com.waenhancer.xposed.core.VersionGuard;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class InjectionIoRuntimeChecks {
    static int checks;
    static final List<String> failures = new ArrayList<>();
    static void check(boolean value, String message) {
        checks++;
        if (!value) failures.add(message);
    }
    static void await(CountDownLatch latch, String name) throws Exception {
        if (!latch.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture event missing: " + name);
    }
    static boolean returned(Future<?> call) throws Exception {
        try { call.get(750, TimeUnit.MILLISECONDS); return true; }
        catch (TimeoutException blocked) { return false; }
    }
    static final class Observation {
        final String method, thread, status, bridge;
        final long timestamp;
        Observation(String method, Bundle extras) {
            this.method = method; thread = Thread.currentThread().getName();
            status = extras == null ? "" : extras.getString("runtime_status");
            bridge = extras == null ? "" : extras.getString("bridge_status");
            timestamp = "report_runtime_health".equals(method) ? extras.getLong("timestamp") : 0;
        }
    }
    static final class Resolver extends ContentResolver {
        volatile String blockedMethod, failedMethod;
        volatile CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final List<Observation> observations = new CopyOnWriteArrayList<>();
        final AtomicInteger preferenceReads = new AtomicInteger();
        final CountDownLatch terminal = new CountDownLatch(1), updated = new CountDownLatch(1), firstRead = new CountDownLatch(1);
        public void registerContentObserver(Uri uri, boolean descendants, ContentObserver observer) {}
        public Bundle call(Uri uri, String method, String arg, Bundle extras) {
            observations.add(new Observation(method, extras));
            if (method.equals(failedMethod)) throw new SecurityException("synthetic provider denial");
            if (method.equals(blockedMethod)) {
                entered.countDown();
                try { if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("unreleased fixture"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }
            Bundle response = new Bundle(); response.putBoolean("success", true);
            if (method.equals("get_all_preferences")) {
                int reads = preferenceReads.incrementAndGet();
                Bundle values = new Bundle(); values.putBoolean("fresh", true); values.putBoolean("show_hook_toast", false);
                response.putBundle("values", values);
                firstRead.countDown();
                if (reads >= 2) updated.countDown();
            } else if (method.equals("report_runtime_health")) {
                String status = extras.getString("runtime_status");
                if (Set.of("READY", "INITIALIZATION_FAILED", "UNSUPPORTED_VERSION").contains(status)) terminal.countDown();
            }
            return response;
        }
        void block(String method) {
            entered = new CountDownLatch(1); release = new CountDownLatch(1); blockedMethod = method;
        }
        void unblock() { blockedMethod = null; release.countDown(); }
        long count(String method) { return observations.stream().filter(o -> o.method.equals(method)).count(); }
    }
    static final class App extends Application {
        final Resolver resolver = new Resolver();
        public ContentResolver getContentResolver() { return resolver; }
    }
    static final class Env implements AutoCloseable {
        final App app = new App();
        final Resolver resolver = app.resolver;
        final ExecutorService ui = Executors.newSingleThreadExecutor(r -> new Thread(r, "Fixture-UI"));
        Env() throws Throwable {
            XC_LoadPackage.LoadPackageParam target = new XC_LoadPackage.LoadPackageParam();
            target.packageName = target.processName = "com.whatsapp"; target.classLoader = getClass().getClassLoader();
            new MainHook().handleLoadPackage(target);
        }
        void attach() throws Exception {
            ui.submit(() -> XposedHelpers.hooks.get("android.app.Application#attach").runAfter(app, app)).get(4, TimeUnit.SECONDS);
        }
        Future<?> start() { return ui.submit(() -> XposedHelpers.hooks.get("android.app.Application#onCreate").runAfter(app)); }
        void ready() throws Exception { attach(); start().get(4, TimeUnit.SECONDS); await(resolver.terminal, "terminal report"); }
        public void close() { resolver.unblock(); ui.shutdownNow(); }
    }
    static void blockedStartup(String method) throws Throwable {
        try (Env e = new Env()) {
            e.resolver.block(method); e.attach();
            Future<?> call = e.start(); await(e.resolver.entered, method);
            boolean nonBlocking = returned(call);
            long uiFinished = System.currentTimeMillis();
            check(nonBlocking, "optional " + method + " blocked Application.onCreate");
            if (nonBlocking) check(FeatureRegistry.initialized.get() == 1, "hooks did not finish while optional I/O was blocked");
            // Ensure a timestamp manufactured at delayed delivery cannot accidentally equal the enqueue time.
            while (System.currentTimeMillis() <= uiFinished) Thread.onSpinWait();
            e.resolver.unblock(); call.get(4, TimeUnit.SECONDS); await(e.resolver.terminal, "terminal report after release");
            check(FeatureRegistry.initialized.get() == 1, "feature initializer count changed");
            check(e.resolver.preferenceReads.get() == 1, "startup changed authoritative preference reads");
            check(e.resolver.observations.stream().filter(o -> !o.method.equals("get_all_preferences"))
                    .noneMatch(o -> o.thread.equals("Fixture-UI")), "runtime IPC still executed on UI");
            if (nonBlocking && method.equals("register_hooked_package")) {
                List<Observation> done = e.resolver.observations;
                int inventory = -1, ready = -1;
                for (int i = 0; i < done.size(); i++) {
                    if (done.get(i).method.equals("register_injected_hooks")) inventory = i;
                    if ("READY".equals(done.get(i).status)) ready = i;
                }
                check(inventory >= 0 && ready > inventory, "READY overtook queued hook inventory");
                check(ready >= 0 && done.get(ready).timestamp <= uiFinished,
                        "delayed report replaced observation time with delivery time");
            }
        }
    }
    static void heartbeat() throws Throwable {
        try (Env e = new Env()) {
            e.ready(); e.resolver.block("report_runtime_health");
            Runnable beat = Handler.delayed.poll(4, TimeUnit.SECONDS);
            if (beat == null) throw new IllegalStateException("heartbeat not registered");
            Future<?> call = e.ui.submit(beat); await(e.resolver.entered, "heartbeat IPC");
            check(returned(call), "heartbeat blocked the main looper");
            e.resolver.unblock(); call.get(4, TimeUnit.SECONDS);
            check(Handler.delayed.size() == 1, "heartbeat stopped or multiplied its schedule");
            check(FeatureRegistry.initialized.get() == 1, "heartbeat reinstalled features");
        }
    }
    static void preferences() throws Throwable {
        try (Env e = new Env()) {
            e.ready(); e.resolver.block("get_all_preferences");
            BroadcastReceiver receiver = e.app.receivers.get("com.waenhancer.action.PREFERENCES_CHANGED");
            if (receiver == null) throw new IllegalStateException("preference receiver missing");
            Future<?> call = e.ui.submit(() -> receiver.onReceive(e.app, new Intent("preferences")));
            await(e.resolver.entered, "live preference IPC");
            check(returned(call), "live preference broadcast blocked the main looper");
            e.resolver.unblock(); call.get(4, TimeUnit.SECONDS); await(e.resolver.updated, "live preference completion");
            check(e.resolver.preferenceReads.get() >= 2, "asynchronous refresh lost configuration changes");
            check(FeatureRegistry.initialized.get() == 1, "live refresh reinstalled features");
        }
    }
    static void initialSync() throws Throwable {
        try (Env e = new Env()) {
            e.resolver.block("get_all_preferences"); e.attach(); await(e.resolver.entered, "initial provider read");
            Future<?> call = e.start();
            check(!returned(call), "hooks consumed disk preferences before authoritative initial read finished");
            check(FeatureRegistry.initialized.get() == 0, "security-sensitive initialization ran early");
            e.resolver.unblock(); call.get(4, TimeUnit.SECONDS); await(e.resolver.terminal, "terminal after initial preferences");
            check(FeatureRegistry.initialized.get() == 1, "initialization failed to resume");
        }
    }
    static void interruptedInitialSync() throws Throwable {
        try (Env e = new Env()) {
            e.resolver.block("get_all_preferences"); e.attach(); await(e.resolver.entered, "initial provider read");
            AtomicReference<Thread> startupThread = new AtomicReference<>();
            AtomicBoolean interruptPreserved = new AtomicBoolean();
            CountDownLatch started = new CountDownLatch(1);
            Future<?> call = e.ui.submit(() -> {
                startupThread.set(Thread.currentThread()); started.countDown();
                try { XposedHelpers.hooks.get("android.app.Application#onCreate").runAfter(e.app); }
                finally {
                    interruptPreserved.set(Thread.currentThread().isInterrupted());
                    // This is a fixture-owned executor: record the flag before cleaning it up.
                    Thread.interrupted();
                }
            });
            await(started, "startup waiter"); startupThread.get().interrupt();
            check(returned(call), "interrupted startup waited for the still-blocked provider");
            check(interruptPreserved.get(), "startup consumed the thread interruption");
            check(FeatureRegistry.attempted.get() == 0, "interrupted startup attempted features before initial sync completed");
            check(e.resolver.preferenceReads.get() == 0, "interruption completed or cancelled the blocked provider read");
            e.resolver.unblock(); call.get(4, TimeUnit.SECONDS); await(e.resolver.firstRead, "uncancelled initial preference read");
            await(e.resolver.terminal, "interrupted startup terminal report");
            check(e.resolver.preferenceReads.get() == 1, "interruption lost or duplicated the initial preference read");
            check(FeatureRegistry.attempted.get() == 0, "features were attempted despite interrupted initialization");
            check(e.resolver.observations.stream().anyMatch(o -> "INITIALIZATION_FAILED".equals(o.status)),
                    "interrupted initialization did not report failure");
            check(e.resolver.observations.stream().noneMatch(o -> "READY".equals(o.status)),
                    "interrupted initialization reported READY");
        }
    }
    static void providerFallback() throws Throwable {
        // A completed, failed provider read has an established disk-fallback contract.
        // This is different from interrupting a waiter while the read is still pending.
        FeatureRegistry.expectedFresh = false;
        try (Env e = new Env()) {
            e.resolver.failedMethod = "get_all_preferences";
            e.attach(); e.start().get(4, TimeUnit.SECONDS); await(e.resolver.terminal, "disk fallback terminal report");
            check(e.resolver.count("get_all_preferences") == 1, "fallback skipped or repeated the provider query");
            check(FeatureRegistry.attempted.get() == 1 && FeatureRegistry.initialized.get() == 1,
                    "completed provider failure prevented the existing disk-fallback initialization");
            check(e.resolver.observations.stream().anyMatch(o -> "READY".equals(o.status) && "DISK_FALLBACK".equals(o.bridge)),
                    "completed provider failure lost the DISK_FALLBACK source");
            check(e.resolver.observations.stream().noneMatch(o -> "INITIALIZATION_FAILED".equals(o.status)),
                    "ordinary provider fallback was treated as interrupted initialization");
        }
    }
    static void simple(String scenario) throws Throwable {
        if (scenario.equals("unsupported")) VersionGuard.allowed = false;
        if (scenario.equals("initialization_failure")) FeatureRegistry.fail = true;
        try (Env e = new Env()) {
            if (scenario.equals("optional_failure")) e.resolver.failedMethod = "register_hooked_package";
            if (!scenario.equals("fallback")) e.attach();
            e.start().get(4, TimeUnit.SECONDS); await(e.resolver.terminal, "terminal state");
            if (scenario.equals("duplicate")) {
                e.ui.submit(() -> XposedHelpers.hooks.get("android.app.Instrumentation#callApplicationOnCreate")
                        .runAfter(new Object(), e.app)).get(4, TimeUnit.SECONDS);
            }
            int expectedCount = scenario.equals("unsupported") ? 0 : 1;
            String expectedStatus = scenario.equals("unsupported") ? "UNSUPPORTED_VERSION"
                    : scenario.equals("initialization_failure") ? "INITIALIZATION_FAILED" : "READY";
            check(FeatureRegistry.initialized.get() == expectedCount, "unexpected number of initializations");
            check(e.resolver.observations.stream().anyMatch(o -> expectedStatus.equals(o.status)), "terminal state lost");
            if (!expectedStatus.equals("READY"))
                check(e.resolver.observations.stream().noneMatch(o -> "READY".equals(o.status)), "failure reported as READY");
            check(e.resolver.preferenceReads.get() == 1, "initial sync duplicated");
            if (scenario.equals("duplicate")) check(e.resolver.count("register_hooked_package") == 1, "fallback registered duplicate runtime");
        }
    }
    public static void main(String[] args) throws Throwable {
        String scenario = args[0];
        switch (scenario) {
            case "package": blockedStartup("register_hooked_package"); break;
            case "hooks": blockedStartup("register_injected_hooks"); break;
            case "update": blockedStartup("request_update_check"); break;
            case "heartbeat": heartbeat(); break;
            case "preferences": preferences(); break;
            case "initial_sync": initialSync(); break;
            case "interrupted_initial_sync": interruptedInitialSync(); break;
            case "provider_fallback": providerFallback(); break;
            default: simple(scenario);
        }
        System.out.println("RESULT " + scenario + ": checks=" + checks + ", failures=" + failures.size());
        for (String failure : failures) System.out.println("FAIL: " + failure);
        if (!failures.isEmpty()) System.exit(1);
    }
}
