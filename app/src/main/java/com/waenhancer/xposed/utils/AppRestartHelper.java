package com.waenhancer.xposed.utils;

import com.waenhancer.app.R;
import com.waenhancer.utils.UiText;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.widget.Toast;
import com.waenhancer.utils.WhatsAppPackageDetector;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Handles dashboard-requested restarts. Never force-stop an unvalidated package. */
public final class AppRestartHelper {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "WAEX-restart-worker");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean RESTART_PENDING = new AtomicBoolean(false);
    private static final long ROOT_TIMEOUT_MS = 4_000L;
    private static final long BROADCAST_SETTLE_MS = 400L;
    private static final String RESTART_ACTION = "com.waenhancer.WHATSAPP.RESTART";

    private AppRestartHelper() {}

    public static boolean hasRootAccess() {
        return RestartTargetPolicy.reportsRootId(runRootCommand("id"));
    }

    /** For internal callers. Do not call this synchronous operation from the UI thread. */
    public static String runRootCommand(String command) {
        return RootCommandRunner.run(command, ROOT_TIMEOUT_MS,
                value -> new ProcessBuilder("su", "-c", value)
                        .redirectErrorStream(true).start());
    }

    /**
     * Force-stop and relaunch an installed supported package. Only the installed
     * packages listed in the module's Xposed scope (plus official WA/WAB) are
     * eligible. The signed WAEX broadcast is used when root is unavailable.
     */
    public static void restartPackage(Context context, String packageName, String appName) {
        restartPackage(context, packageName, appName, true);
    }

    /** Restarts a package, optionally omitting the initial progress toast for delegated callers. */
    public static void restartPackage(Context context, String packageName, String appName,
                                      boolean showProgressToast) {
        if (context == null) return;
        Set<String> discoveredScope;
        try {
            discoveredScope = WhatsAppPackageDetector.getSupportedPackages(context);
        } catch (Throwable ignored) {
            // Fail closed for clones when module resources cannot be resolved.
            discoveredScope = Collections.emptySet();
        }
        final Set<String> supported = discoveredScope == null
                ? Collections.emptySet() : discoveredScope;
        if (!RestartTargetPolicy.isSupportedTarget(packageName, supported)) {
            toast(context, UiText.get(R.string.waex_ui_unsupported_restart_target, "Unsupported restart target"));
            return;
        }

        final Intent launchIntent;
        try {
            launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent == null || launchIntent.getComponent() == null
                    || !packageName.equals(launchIntent.getComponent().getPackageName())) {
                toast(context, UiText.get(R.string.waex_ui_unable_to_launch_selected_whatsapp_app, "Unable to launch selected WhatsApp app"));
                return;
            }
        } catch (RuntimeException error) {
            toast(context, UiText.get(R.string.waex_ui_unable_to_find_selected_whatsapp_app, "Unable to find selected WhatsApp app"));
            return;
        }
        if (!RESTART_PENDING.compareAndSet(false, true)) return;

        if (showProgressToast) {
            toast(context, UiText.get(R.string.waex_ui_restarting, "Restarting ")
                    + (appName == null ? packageName : appName) + "...");
        }
        try {
            EXECUTOR.execute(() -> {
                try {
                    // Root is tried first; never send the self-kill broadcast after
                    // a successful force-stop/relaunch, which could kill the new process.
                    boolean stoppedByRoot = runRootCommand(
                            RestartTargetPolicy.forceStopCommand(packageName, supported)) != null;
                    boolean startedByRoot = false;
                    if (stoppedByRoot) {
                        String component = launchIntent.getComponent() != null
                                ? launchIntent.getComponent().flattenToShortString() : null;
                        String output = runRootCommand(
                                RestartTargetPolicy.launchCommand(packageName, component, supported));
                        startedByRoot = RestartTargetPolicy.reportsRootLaunch(output);
                    }
                    if (startedByRoot) {
                        RESTART_PENDING.set(false);
                        return;
                    }

                    if (!stoppedByRoot) {
                        // Registered receivers require the manager's signature permission.
                        // This broadcast is package-scoped and carries an exact target.
                        try {
                            Intent restart = new Intent(RESTART_ACTION);
                            restart.setPackage(packageName);
                            restart.putExtra("PKG", packageName);
                            context.sendBroadcast(restart);
                        } catch (RuntimeException ignored) {
                            // The receiver may not be registered. Opening the app below
                            // is a best-effort fallback, not proof of a process restart.
                        }
                    }
                    long delay = stoppedByRoot ? 0L : BROADCAST_SETTLE_MS;
                    if (!MAIN.postDelayed(() -> {
                        try {
                            Intent launch = new Intent(launchIntent);
                            launch.setPackage(packageName);
                            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            context.startActivity(launch);
                            if (!stoppedByRoot) {
                                toast(context, UiText.get(R.string.waex_ui_whatsapp_opened_restart_not_verified, "WhatsApp opened; restart not verified"));
                            }
                        } catch (RuntimeException ignored) {
                            toast(context, UiText.get(R.string.waex_ui_could_not_reopen_whatsapp, "Could not reopen WhatsApp"));
                        } finally {
                            RESTART_PENDING.set(false);
                        }
                    }, delay)) RESTART_PENDING.set(false);
                } catch (RuntimeException error) {
                    toast(context, UiText.get(R.string.waex_ui_unable_to_restart_whatsapp, "Unable to restart WhatsApp"));
                    RESTART_PENDING.set(false);
                }
            });
        } catch (RejectedExecutionException failure) {
            RESTART_PENDING.set(false);
            toast(context, UiText.get(R.string.waex_ui_restart_operation_unavailable, "Restart operation unavailable"));
        }
    }

    /** Restarts every installed WhatsApp target, then relaunches the WAEX manager process. */
    public static void restartWhatsAppAndModule(Context context) {
        if (context == null || !RESTART_PENDING.compareAndSet(false, true)) return;
        final Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        toast(appContext, UiText.get(R.string.waex_ui_restarting_whatsapp_and_waex, "Restarting WhatsApp and WAEX..."));

        try {
            EXECUTOR.execute(() -> {
                try {
                    Set<String> discovered = WhatsAppPackageDetector.getSupportedPackages(appContext);
                    Set<String> targets = discovered == null
                            ? Collections.emptySet() : new LinkedHashSet<>(discovered);

                    for (String packageName : targets) {
                        Intent targetLaunch = appContext.getPackageManager().getLaunchIntentForPackage(packageName);
                        if (!RestartTargetPolicy.isSupportedTarget(packageName, targets)
                                || targetLaunch == null) {
                            continue;
                        }

                        boolean stoppedByRoot = runRootCommand(
                                RestartTargetPolicy.forceStopCommand(packageName, targets)) != null;
                        if (stoppedByRoot) {
                            String component = targetLaunch.getComponent() != null
                                    ? targetLaunch.getComponent().flattenToShortString() : null;
                            runRootCommand(RestartTargetPolicy.launchCommand(packageName, component, targets));
                        } else {
                            Intent restart = new Intent(RESTART_ACTION);
                            restart.setPackage(packageName);
                            restart.putExtra("PKG", packageName);
                            appContext.sendBroadcast(restart);
                        }
                    }

                    MAIN.post(() -> relaunchModuleProcess(appContext));
                } catch (Throwable error) {
                    RESTART_PENDING.set(false);
                    toast(appContext, UiText.get(R.string.waex_ui_unable_to_restart_apps, "Unable to restart apps"));
                }
            });
        } catch (RejectedExecutionException failure) {
            RESTART_PENDING.set(false);
            toast(appContext, UiText.get(R.string.waex_ui_restart_operation_unavailable, "Restart operation unavailable"));
        }
    }

    private static void relaunchModuleProcess(Context context) {
        try {
            Intent launchIntent = context.getPackageManager()
                    .getLaunchIntentForPackage(context.getPackageName());
            if (launchIntent == null || launchIntent.getComponent() == null) {
                RESTART_PENDING.set(false);
                toast(context, UiText.get(R.string.waex_ui_unable_to_relaunch_waex, "Unable to relaunch WAEX"));
                return;
            }
            Intent restartIntent = Intent.makeRestartActivityTask(launchIntent.getComponent());
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context,
                    9021,
                    restartIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) {
                RESTART_PENDING.set(false);
                toast(context, UiText.get(R.string.waex_ui_unable_to_relaunch_waex, "Unable to relaunch WAEX"));
                return;
            }
            alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + 500L,
                    pendingIntent
            );
            Process.killProcess(Process.myPid());
            System.exit(0);
        } catch (Throwable error) {
            RESTART_PENDING.set(false);
            toast(context, UiText.get(R.string.waex_ui_unable_to_relaunch_waex, "Unable to relaunch WAEX"));
        }
    }

    private static void toast(Context context, String message) {
        MAIN.post(() -> {
            Context safeContext = context.getApplicationContext();
            Toast.makeText(safeContext != null ? safeContext : context,
                    message, Toast.LENGTH_SHORT).show();
        });
    }
}
