package com.waenhancer.xposed.utils;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import com.waenhancer.update.UpdateDownloader;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
import org.robolectric.annotation.RealObject;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowApplicationPackageManager;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowProcess;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;
import static org.junit.Assert.*;
import static org.robolectric.util.reflector.Reflector.reflector;

/** Framework Context/Intent/PackageManager/Handler; explicit UID metadata and root endpoints. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 34}, manifest = Config.NONE,
        shadows = WorkProfileRuntimeTest.FaultablePackageManager.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class WorkProfileRuntimeTest {
    private Application app;
    private ShadowPackageManager packages;
    private final Map<String, PackageInfo> installed = new LinkedHashMap<>();
    private final List<ActivityController<ProfileActivity>> activities = new ArrayList<>();
    private Set<Thread> originalThreads;

    /** Only two fault-injection cases alter the launch result; normal calls use Android code. */
    @Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
    public static class FaultablePackageManager extends ShadowApplicationPackageManager {
        @RealObject PackageManager packages;
        static String overriddenPackage;
        static ComponentName overriddenComponent;
        @ForType(className = "android.app.ApplicationPackageManager") interface DirectPackageManager {
            @Direct Intent getLaunchIntentForPackage(String packageName);
        }
        @Implementation protected Intent getLaunchIntentForPackage(String packageName) {
            if (packageName.equals(overriddenPackage)) {
                return new Intent(Intent.ACTION_MAIN).setComponent(overriddenComponent);
            }
            return reflector(DirectPackageManager.class, packages).getLaunchIntentForPackage(packageName);
        }
    }

    /** The framework Context remains real; this fixture supplies the manager's per-profile UID. */
    private static class ProfileContext extends ContextWrapper {
        final int uid;
        final List<Intent> starts = new ArrayList<>();
        ProfileContext(Context base, int uid) { super(base); this.uid = uid; }
        @Override public Context getApplicationContext() { return this; }
        @Override public ApplicationInfo getApplicationInfo() {
            ApplicationInfo info = new ApplicationInfo(super.getApplicationInfo());
            info.uid = uid;
            return info;
        }
        @Override public void startActivity(Intent intent) {
            starts.add(new Intent(intent));
            super.startActivity(intent);
        }
    }

    public static class ProfileActivity extends Activity {
        int profileUid = 12345;
        @Override public ApplicationInfo getApplicationInfo() {
            ApplicationInfo info = new ApplicationInfo(super.getApplicationInfo());
            info.uid = profileUid;
            return info;
        }
    }

    private static Object field(String name) throws Exception {
        Field field = AppRestartHelper.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void awaitWorker() throws Exception {
        ((ExecutorService) field("EXECUTOR")).submit(() -> {}).get(3, TimeUnit.SECONDS);
    }

    private static void drainMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    }

    @Before public void setUp() throws Exception {
        app = RuntimeEnvironment.getApplication();
        packages = Shadows.shadowOf(app.getPackageManager());
        RootCommandRunner.reset();
        originalThreads = Thread.getAllStackTraces().keySet();
        FaultablePackageManager.overriddenPackage = null;
        FaultablePackageManager.overriddenComponent = null;
        ShadowProcess.setUid(12345);
        ((Handler) field("MAIN")).removeCallbacksAndMessages(null);
        ((AtomicBoolean) field("RESTART_PENDING")).set(false);
    }

    @After public void tearDown() throws Exception {
        RootCommandRunner.release.countDown();
        awaitWorker();
        awaitUpdaterThreads(originalThreads);
        // Bulk restart's final self-termination callback is outside this fixture.
        ((Handler) field("MAIN")).removeCallbacksAndMessages(null);
        ((AtomicBoolean) field("RESTART_PENDING")).set(false);
        for (ActivityController<ProfileActivity> activity : activities) activity.pause().stop().destroy();
    }

    private static void awaitUpdaterThreads(Set<Thread> before) throws Exception {
        // Thread.start() returns only after registration. A completed updater has
        // already recorded its commands; an active one is joined before assertions.
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread != Thread.currentThread() && !thread.isDaemon() && !before.contains(thread)) {
                thread.join(3000);
                assertFalse("Updater fixture did not finish", thread.isAlive());
            }
        }
    }

    private void launcher(String queriedPackage, String componentPackage, String activity) {
        ActivityInfo info = new ActivityInfo();
        info.packageName = componentPackage;
        info.name = activity;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = componentPackage;
        info.applicationInfo.uid = 23456;
        info.exported = true;
        ResolveInfo resolved = new ResolveInfo();
        resolved.activityInfo = info;
        PackageInfo pkg = new PackageInfo();
        pkg.packageName = queriedPackage;
        pkg.applicationInfo = new ApplicationInfo(info.applicationInfo);
        pkg.applicationInfo.packageName = queriedPackage;
        installed.put(queriedPackage, pkg);
        packages.installPackage(pkg);
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(queriedPackage);
        packages.addResolveInfoForIntent(query, resolved);
        if (!queriedPackage.equals(componentPackage)) {
            FaultablePackageManager.overriddenPackage = queriedPackage;
            FaultablePackageManager.overriddenComponent = new ComponentName(componentPackage, activity);
        }
    }

    private void launcher(String pkg) { launcher(pkg, pkg, pkg + ".Launcher"); }

    private ProfileContext context(int uid) {
        ShadowProcess.setUid(uid < 0 ? 12345 : uid);
        for (PackageInfo info : installed.values()) {
            info.applicationInfo.uid = (Math.max(0, uid) / 100_000) * 100_000 + 23456;
            packages.installPackage(info);
        }
        return new ProfileContext(app, uid);
    }

    private void restart(ProfileContext context, String pkg) throws Exception {
        AppRestartHelper.restartPackage(context, pkg, pkg);
        awaitWorker();
        drainMain();
        assertFalse("Restart gate must be released", ((AtomicBoolean) field("RESTART_PENDING")).get());
    }

    private void assertRootCommands(int user, String pkg, String activity) {
        assertEquals(2, RootCommandRunner.commands.size());
        assertEquals("am force-stop --user " + user + " '" + pkg + "'", RootCommandRunner.commands.get(0));
        assertEquals("am start -W --user " + user + " -n '" + pkg + "/" + activity
                + "' -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10008000",
                RootCommandRunner.commands.get(1));
        assertFalse(RootCommandRunner.commands.stream().anyMatch(command -> command.startsWith("monkey")));
    }

    @Test public void primaryRestartPinsBothCommandsToOwnerZero() throws Exception {
        launcher("com.whatsapp");
        ProfileContext context = context(12345);
        restart(context, "com.whatsapp");
        assertRootCommands(0, "com.whatsapp", "com.whatsapp.Launcher");
        assertTrue(context.starts.isEmpty());
        assertTrue(Shadows.shadowOf(app).getBroadcastIntents().isEmpty());
    }

    @Test public void workProfileRestartUsesProfileTenNotForegroundUser() throws Exception {
        launcher("com.whatsapp");
        ProfileContext context = context(1012345);
        restart(context, "com.whatsapp");
        assertRootCommands(10, "com.whatsapp", "com.whatsapp.Launcher");
        assertTrue(context.starts.isEmpty());
        assertTrue(Shadows.shadowOf(app).getBroadcastIntents().isEmpty());
    }

    @Test public void secondaryProfileCloneKeepsItsOwnComponentAndUser() throws Exception {
        launcher("com.gbwhatsapp");
        restart(context(1112345), "com.gbwhatsapp");
        assertRootCommands(11, "com.gbwhatsapp", "com.gbwhatsapp.Launcher");
    }

    @Test public void launcherLibraryClassDoesNotChangeTargetPackage() throws Exception {
        launcher("com.whatsapp", "com.whatsapp", "androidx.activity.ComponentActivity");
        restart(context(1012345), "com.whatsapp");
        assertRootCommands(10, "com.whatsapp", "androidx.activity.ComponentActivity");
    }

    @Test public void unsupportedAndInjectedPackageNamesCannotStartRootCommands() throws Exception {
        for (String pkg : new String[]{"com.android.settings", "com.waenhancer", "com.whatsapp;id"}) {
            launcher(pkg);
            restart(context(1012345), pkg);
        }
        assertTrue(RootCommandRunner.commands.isEmpty());
    }

    @Test public void packageMissingInThisProfileIsNotStoppedElsewhere() throws Exception {
        restart(context(1012345), "com.whatsapp");
        assertTrue(RootCommandRunner.commands.isEmpty());
    }

    @Test public void mismatchedLauncherCannotRedirectPrivilegedLaunch() throws Exception {
        launcher("com.whatsapp", "com.android.settings", "com.android.settings.Launcher");
        restart(context(1012345), "com.whatsapp");
        assertTrue(RootCommandRunner.commands.isEmpty());
    }

    @Test public void shellCharactersInComponentFailBeforeForceStop() throws Exception {
        launcher("com.whatsapp", "com.whatsapp", "com.whatsapp.Launcher;id");
        restart(context(1012345), "com.whatsapp");
        assertTrue(RootCommandRunner.commands.isEmpty());
    }

    @Test public void negativeContextUidCannotSelectAllOrCurrentUsers() throws Exception {
        launcher("com.whatsapp");
        restart(context(-1), "com.whatsapp");
        assertTrue(RootCommandRunner.commands.isEmpty());
    }

    @Test public void foreignContextProfileCannotRedirectRootCommands() throws Exception {
        launcher("com.whatsapp");
        context(1012345);
        restart(new ProfileContext(app, 1112345), "com.whatsapp");
        assertTrue(RootCommandRunner.commands.isEmpty());
    }

    @Test public void targetUidFromAnotherProfileIsRejectedBeforeStopping() throws Exception {
        launcher("com.whatsapp");
        ProfileContext context = context(1012345);
        PackageInfo other = installed.get("com.whatsapp");
        other.applicationInfo.uid = 1123456;
        packages.installPackage(other);
        assertEquals(1123456, app.getPackageManager().getApplicationInfo("com.whatsapp", 0).uid);
        restart(context, "com.whatsapp");
        assertTrue(RootCommandRunner.commands.isEmpty());
    }

    @Test public void deniedRootUsesSameContextBroadcastAndSingleLaunch() throws Exception {
        RootCommandRunner.mode = "denied";
        launcher("com.whatsapp");
        ProfileContext context = context(1012345);
        restart(context, "com.whatsapp");
        assertEquals(1, RootCommandRunner.commands.size());
        assertEquals(1, context.starts.size());
        Intent launch = context.starts.get(0);
        assertEquals("com.whatsapp", launch.getComponent().getPackageName());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK,
                launch.getFlags() & (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        List<Intent> broadcasts = Shadows.shadowOf(app).getBroadcastIntents();
        assertEquals(1, broadcasts.size());
        assertEquals("com.whatsapp", broadcasts.get(0).getPackage());
        assertEquals("com.whatsapp", broadcasts.get(0).getStringExtra("PKG"));
    }

    private void assertLaunchFallback(String mode) throws Exception {
        RootCommandRunner.mode = mode;
        launcher("com.whatsapp");
        ProfileContext context = context(1012345);
        restart(context, "com.whatsapp");
        assertEquals(2, RootCommandRunner.commands.size());
        assertEquals(1, context.starts.size());
        assertTrue("Do not send a self-kill after a successful stop", Shadows.shadowOf(app).getBroadcastIntents().isEmpty());
    }

    @Test public void launchTimeoutUsesContextFallbackWithoutSecondKill() throws Exception {
        assertLaunchFallback("start-timeout");
    }

    @Test public void rejectedLaunchUsesContextFallbackWithoutSecondKill() throws Exception {
        assertLaunchFallback("start-denied");
    }

    @Test public void misleadingLaunchAcknowledgmentDoesNotClaimSuccess() throws Exception {
        assertLaunchFallback("start-malformed");
    }

    @Test public void duplicateRequestWhileRootBlockedDoesNotDuplicateWork() throws Exception {
        RootCommandRunner.mode = "blocked";
        launcher("com.whatsapp");
        ProfileContext context = context(1012345);
        AppRestartHelper.restartPackage(context, "com.whatsapp", "WhatsApp");
        assertTrue(RootCommandRunner.entered.await(3, TimeUnit.SECONDS));
        AppRestartHelper.restartPackage(context, "com.whatsapp", "WhatsApp");
        assertEquals(1, RootCommandRunner.commands.size());
        RootCommandRunner.release.countDown();
        awaitWorker();
        drainMain();
        assertEquals(1, RootCommandRunner.commands.size());
        assertEquals(1, context.starts.size());
        assertEquals(1, Shadows.shadowOf(app).getBroadcastIntents().size());
    }

    @Test public void bulkRestartTargetsOnlyInstalledAppsInSameProfile() throws Exception {
        launcher("com.whatsapp");
        launcher("com.whatsapp.w4b");
        AppRestartHelper.restartWhatsAppAndModule(context(1012345));
        awaitWorker();
        assertEquals(4, RootCommandRunner.commands.size());
        for (String command : RootCommandRunner.commands) {
            assertTrue(command, command.contains("--user 10 "));
            assertFalse(command, command.contains("com.waenhancer"));
        }
        assertEquals(2, RootCommandRunner.commands.stream().filter(command -> command.startsWith("am start -W ")).count());
    }

    @Test public void bulkRestartRejectsMismatchedComponentBeforeStoppingAnything() throws Exception {
        launcher("com.whatsapp", "com.android.settings", "com.android.settings.Launcher");
        AppRestartHelper.restartWhatsAppAndModule(context(1012345));
        awaitWorker();
        assertTrue(RootCommandRunner.commands.isEmpty());
    }

    private ProfileContext bulkFallback(String mode) throws Exception {
        RootCommandRunner.mode = mode;
        launcher("com.whatsapp");
        launcher("com.whatsapp.w4b");
        ProfileContext context = context(1012345);
        // A missing manager launcher makes the real final callback release its
        // gate without scheduling an alarm or terminating this test process.
        assertNull(app.getPackageManager().getLaunchIntentForPackage(app.getPackageName()));
        AppRestartHelper.restartWhatsAppAndModule(context);
        awaitWorker();
        drainMain();
        assertFalse(((AtomicBoolean) field("RESTART_PENDING")).get());
        assertEquals(2, context.starts.size());
        for (Intent launch : context.starts) {
            assertEquals(launch.getComponent().getPackageName(), launch.getPackage());
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK,
                    launch.getFlags() & (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        }
        return context;
    }

    @Test public void bulkDeniedRootReopensBothTargetsAfterBroadcast() throws Exception {
        bulkFallback("denied");
        assertEquals(2, RootCommandRunner.commands.size());
        assertEquals(2, Shadows.shadowOf(app).getBroadcastIntents().size());
    }

    @Test public void bulkTimedOutLaunchRecoversWithoutAnotherKill() throws Exception {
        bulkFallback("start-timeout");
        assertEquals(4, RootCommandRunner.commands.size());
        assertTrue(Shadows.shadowOf(app).getBroadcastIntents().isEmpty());
    }

    @Test public void bulkMalformedLaunchAcknowledgmentRecoversWithoutAnotherKill() throws Exception {
        bulkFallback("start-malformed");
        assertEquals(4, RootCommandRunner.commands.size());
        assertTrue(Shadows.shadowOf(app).getBroadcastIntents().isEmpty());
    }

    @Test public void bulkBroadcastFailureStillProcessesBothTargets() throws Exception {
        RootCommandRunner.mode = "denied";
        launcher("com.whatsapp");
        launcher("com.whatsapp.w4b");
        context(1012345);
        ProfileContext context = new ProfileContext(app, 1012345) {
            @Override public void sendBroadcast(Intent intent) { throw new SecurityException("controlled denial"); }
        };
        assertNull(app.getPackageManager().getLaunchIntentForPackage(app.getPackageName()));
        AppRestartHelper.restartWhatsAppAndModule(context);
        awaitWorker();
        drainMain();
        assertEquals(2, RootCommandRunner.commands.size());
        assertEquals(2, context.starts.size());
        assertFalse(((AtomicBoolean) field("RESTART_PENDING")).get());
    }

    private ProfileActivity activity(int uid) {
        ShadowProcess.setUid(uid);
        ActivityController<ProfileActivity> controller = Robolectric.buildActivity(ProfileActivity.class).setup();
        activities.add(controller);
        ProfileActivity activity = controller.get();
        activity.profileUid = uid;
        return activity;
    }

    private File apk(ProfileActivity activity, String version) throws Exception {
        File archive = File.createTempFile("profile-update-", ".apk", activity.getCacheDir());
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            for (String entry : new String[]{"AndroidManifest.xml", "classes.dex"}) {
                zip.putNextEntry(new ZipEntry(entry));
                byte[] bytes = new byte[2048];
                new Random(entry.hashCode()).nextBytes(bytes);
                zip.write(bytes);
                zip.closeEntry();
            }
        }
        PackageInfo info = new PackageInfo();
        info.packageName = activity.getPackageName();
        info.versionName = version;
        // Archive metadata is explicit; no generated or signed Android APK is claimed.
        packages.setPackageArchiveInfo(archive.getAbsolutePath(), info);
        RootCommandRunner.copiedBytes = archive.length();
        return archive;
    }

    private void assertInstallerUser(int uid, int user) throws Exception {
        ProfileActivity activity = activity(uid);
        File apk = apk(activity, "2.0.4");
        UpdateDownloader.INSTANCE.installApkWithRoot(activity, apk, "2.0.4");
        assertTrue("Installer fixture never reached cleanup", RootCommandRunner.cleaned.await(3, TimeUnit.SECONDS));
        List<String> installs = RootCommandRunner.commands.stream().filter(command -> command.startsWith("pm install ")).toList();
        assertEquals(1, installs.size());
        assertEquals("pm install -r -d --user " + user + " '/data/local/tmp/waex.FIXTURE1'", installs.get(0));
        assertEquals("rm -f -- '/data/local/tmp/waex.FIXTURE1'", RootCommandRunner.commands.get(RootCommandRunner.commands.size() - 1));
    }

    @Test public void ownerUpdateKeepsExplicitOwnerTarget() throws Exception { assertInstallerUser(12345, 0); }
    @Test public void workProfileUpdateDoesNotInstallIntoOwnerZero() throws Exception { assertInstallerUser(1012345, 10); }
    @Test public void secondaryProfileUpdateDoesNotInstallIntoOwnerZero() throws Exception { assertInstallerUser(1112345, 11); }

    @Test public void invalidApkNeverStartsRootInstaller() throws Exception {
        ProfileActivity activity = activity(1012345);
        File file = File.createTempFile("invalid-profile-update-", ".apk", activity.getCacheDir());
        UpdateDownloader.INSTANCE.installApkWithRoot(activity, file, "2.0.4");
        assertTrue(RootCommandRunner.commands.isEmpty());
    }

    @Test public void foreignContextProfileCannotRedirectRootInstaller() throws Exception {
        ProfileActivity activity = activity(1012345);
        File file = apk(activity, "2.0.4");
        activity.profileUid = 1112345;
        Set<Thread> before = Thread.getAllStackTraces().keySet();
        UpdateDownloader.INSTANCE.installApkWithRoot(activity, file, "2.0.4");
        awaitUpdaterThreads(before);
        assertTrue(RootCommandRunner.commands.isEmpty());
    }

    @Test public void wrongVersionNeverStartsRootInstaller() throws Exception {
        ProfileActivity activity = activity(1012345);
        UpdateDownloader.INSTANCE.installApkWithRoot(activity, apk(activity, "2.0.3"), "2.0.4");
        assertTrue(RootCommandRunner.commands.isEmpty());
    }

    @Test public void failedCopyStillCleansUpWithoutInstalling() throws Exception {
        ProfileActivity activity = activity(1012345);
        File file = apk(activity, "2.0.4");
        RootCommandRunner.copiedBytes = file.length() - 1;
        UpdateDownloader.INSTANCE.installApkWithRoot(activity, file, "2.0.4");
        assertTrue(RootCommandRunner.cleaned.await(3, TimeUnit.SECONDS));
        assertTrue(RootCommandRunner.commands.stream().noneMatch(command -> command.startsWith("pm install ")));
    }
}
