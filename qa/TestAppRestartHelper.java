package com.waenhancer.xposed.utils;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.widget.Toast;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.waenhancer.utils.WhatsAppPackageDetector;

/** Compiles and exercises the actual AppRestartHelper against Android/root doubles. */
public final class TestAppRestartHelper {
    private static int assertions;
    private static void ok(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeContext extends Context {
        final PackageManager pm = new PackageManager();
        final android.content.pm.ApplicationInfo app = new android.content.pm.ApplicationInfo();
        @Override public android.content.pm.ApplicationInfo getApplicationInfo() { return app; }
        volatile Intent broadcast;
        volatile Intent launch;
        volatile int broadcasts;
        volatile int launches;
        volatile boolean failLaunch;
        volatile boolean failBroadcast;
        volatile CountDownLatch awaited = new CountDownLatch(1);
        @Override public Context getApplicationContext() { return this; }
        @Override public PackageManager getPackageManager() { return pm; }
        @Override public String getPackageName() { return "com.waenhancer"; }
        @Override public Object getSystemService(String name) { return null; }
        @Override public void sendBroadcast(Intent intent) {
            broadcast = intent;
            broadcasts++;
            if (failBroadcast) throw new SecurityException("broadcast rejected");
        }
        @Override public void startActivity(Intent intent) {
            launches++;
            launch = intent;
            awaited.countDown();
            if (failLaunch) throw new SecurityException("background launch rejected");
        }
        void setTarget(String packageName) {
            pm.intent = new Intent("android.intent.action.MAIN")
                    .setComponent(new ComponentName(packageName, packageName + ".Launcher"));
            awaited = new CountDownLatch(1);
        }
    }

    private static void awaitIdle() throws Exception {
        java.lang.reflect.Field state = AppRestartHelper.class.getDeclaredField("RESTART_PENDING");
        state.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean flag =
                (java.util.concurrent.atomic.AtomicBoolean) state.get(null);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (flag.get() && System.nanoTime() < until) Thread.sleep(2L);
        ok(!flag.get(), "restart guard was not released");
    }

    private static void expectNoAction(FakeContext context, String name) {
        int previous = RootCommandRunner.commands.size();
        int attempts = context.pm.calls;
        AppRestartHelper.restartPackage(context, name, "Test");
        ok(previous == RootCommandRunner.commands.size(), "unexpected root command for " + name);
        ok(context.broadcasts == 0 && context.launches == 0, "unexpected activity for " + name);
        if (RestartTargetPolicy.isSupportedTarget(name, WhatsAppPackageDetector.SCOPE)) {
            ok(context.pm.calls == attempts + 1, "expected installer lookup");
        } else {
            ok(context.pm.calls == attempts, "invalid package requested launch intent");
        }
    }

    public static void main(String[] args) throws Exception {
        WhatsAppPackageDetector.SCOPE = new LinkedHashSet<>(Arrays.asList(args));
        RootCommandRunner.mode = "denied";
        FakeContext context = new FakeContext();
        context.pm.intent = null;
        expectNoAction(context, "com.whatsapp");  // No launch intent -> do not stop WhatsApp.
        expectNoAction(context, "com.android.settings");
        expectNoAction(context, "com.whatsapp;reboot");
        expectNoAction(context, "com.waenhancer");
        expectNoAction(context, "com.unknown.whatsapp");
        context.pm.intent = new Intent("android.intent.action.MAIN")
                .setComponent(new ComponentName("com.android.settings", "SomeActivity"));
        expectNoAction(context, "com.whatsapp"); // Mismatched launcher -> no root.

        FakeContext broadcastFallback = new FakeContext();
        broadcastFallback.setTarget("com.whatsapp");
        int oldRoot = RootCommandRunner.commands.size();
        AppRestartHelper.restartPackage(broadcastFallback, "com.whatsapp", "WhatsApp");
        ok(broadcastFallback.awaited.await(2, TimeUnit.SECONDS), "no-root fallback never launched");
        ok(broadcastFallback.broadcasts == 1, "no-root fallback must broadcast once");
        ok(broadcastFallback.launches == 1, "no-root fallback opens selected package once");
        ok("com.waenhancer.WHATSAPP.RESTART".equals(broadcastFallback.broadcast.getAction()), "restart action changed");
        ok("com.whatsapp".equals(broadcastFallback.broadcast.getPackage()), "broadcast targets wrong package");
        ok("com.whatsapp".equals(broadcastFallback.broadcast.getStringExtra("PKG")), "missing exact package extra");
        ok("com.whatsapp".equals(broadcastFallback.launch.getPackage()), "wrong fallback launch package");
        ok((broadcastFallback.launch.getFlags() & (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)) ==
                (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK), "missing Activity flags");
        ok(RootCommandRunner.commands.size() == oldRoot + 1, "no-root branch launched activity launch");
        awaitIdle();

        RootCommandRunner.mode = "root";
        RootCommandRunner.commands.clear();
        FakeContext root = new FakeContext();
        root.setTarget("com.whatsapp.w4b");
        int status0 = Toast.MESSAGES.size();
        AppRestartHelper.restartPackage(root, "com.whatsapp.w4b", "WhatsApp Business");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (Toast.MESSAGES.size() <= status0 + 1 && System.nanoTime() < deadline) Thread.sleep(5L);
        ok(RootCommandRunner.commands.size()==2, "root should run force-stop then activity launch");
        ok(RootCommandRunner.commands.get(0).equals("am force-stop --user 0 'com.whatsapp.w4b'"), "root stop quoting");
        ok(RootCommandRunner.commands.get(1).equals(
                "am start -W --user 0 -n 'com.whatsapp.w4b/com.whatsapp.w4b.Launcher' -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10008000"), "root activity launch quoting");
        ok(root.broadcasts == 0 && root.launches == 0, "do not kill newly launched root process");
        awaitIdle();
        ok(AppRestartHelper.hasRootAccess(), "root ID helper");
        RootCommandRunner.mode = "denied";
        ok(!AppRestartHelper.hasRootAccess(), "no-root ID helper");

        RootCommandRunner.mode = "stoponly";
        RootCommandRunner.commands.clear();
        FakeContext clone = new FakeContext();
        clone.setTarget("com.gbwhatsapp");
        AppRestartHelper.restartPackage(clone, "com.gbwhatsapp", "GBWhatsApp");
        ok(clone.awaited.await(2, TimeUnit.SECONDS), "stopped clone should be relaunched");
        ok(clone.broadcasts == 0, "broadcast after successful force-stop would kill relaunch");
        ok(clone.launches == 1, "failed activity launch should fall back to normal launcher");
        ok("com.gbwhatsapp".equals(clone.launch.getPackage()), "clone package mutated");
        ok(RootCommandRunner.commands.size() == 2, "expected stop and attempted activity launch");
        awaitIdle();

        RootCommandRunner.mode = "denied";
        FakeContext rejected = new FakeContext();
        rejected.setTarget("com.whatsapp");
        rejected.failLaunch = true;
        AppRestartHelper.restartPackage(rejected, "com.whatsapp", "WhatsApp");
        ok(rejected.awaited.await(2, TimeUnit.SECONDS), "failed Activity should be handled");
        awaitIdle();
        ok(Toast.MESSAGES.stream().anyMatch(s -> s.contains("Could not reopen WhatsApp")), "launch failure not reported");

        RootCommandRunner.mode = "blocked";
        RootCommandRunner.entered = new CountDownLatch(1);
        RootCommandRunner.release = new CountDownLatch(1);
        FakeContext simultaneous = new FakeContext();
        simultaneous.setTarget("com.whatsapp");
        RootCommandRunner.commands.clear();
        AppRestartHelper.restartPackage(simultaneous, "com.whatsapp", "WhatsApp");
        ok(RootCommandRunner.entered.await(2, TimeUnit.SECONDS), "root worker never entered");
        int numberBefore = simultaneous.pm.calls;
        AppRestartHelper.restartPackage(simultaneous, "com.whatsapp", "WhatsApp");
        ok(simultaneous.pm.calls == numberBefore + 1, "missing safe launcher check on duplicate request");
        ok(RootCommandRunner.commands.size() == 1, "second root command should be suppressed");
        RootCommandRunner.release.countDown();
        ok(simultaneous.awaited.await(2, TimeUnit.SECONDS), "held worker never completed");
        ok(simultaneous.broadcasts == 1 && simultaneous.launches == 1,
                "duplicate requests triggered too many broadcasts/launches");
        awaitIdle();
        WhatsAppPackageDetector.SCOPE = new LinkedHashSet<>(Arrays.asList("com.whatsapp"));
        for (String mode : new String[]{"denied", "stoponly", "root"}) {
            RootCommandRunner.mode = mode;
            RootCommandRunner.commands.clear();
            FakeContext bulk = new FakeContext();
            bulk.setTarget("com.whatsapp");
            AppRestartHelper.restartWhatsAppAndModule(bulk);
            awaitIdle();
            ok(bulk.launches == (mode.equals("root") ? 0 : 1), "bulk launch recovery: " + mode);
            ok(bulk.broadcasts == (mode.equals("denied") ? 1 : 0), "bulk second-kill guard: " + mode);
            ok(RootCommandRunner.commands.size() == (mode.equals("denied") ? 1 : 2), "bulk commands: " + mode);
        }
        RootCommandRunner.mode = "denied";
        FakeContext refusedBroadcast = new FakeContext();
        refusedBroadcast.setTarget("com.whatsapp");
        refusedBroadcast.failBroadcast = true;
        AppRestartHelper.restartWhatsAppAndModule(refusedBroadcast);
        awaitIdle();
        ok(refusedBroadcast.launches == 1, "bulk broadcast failure prevented fallback launch");
        RootCommandRunner.mode = "root";
        android.os.Handler.reject = true;
        try {
            FakeContext rejectedPost = new FakeContext();
            rejectedPost.setTarget("com.whatsapp");
            AppRestartHelper.restartWhatsAppAndModule(rejectedPost);
            awaitIdle();
            ok(rejectedPost.launches == 0, "rejected callback still ran");
        } finally { android.os.Handler.reject = false; }
        System.out.println("PASS: " + assertions + " AppRestartHelper launch, authorization and duplicate-request checks");
    }
}
