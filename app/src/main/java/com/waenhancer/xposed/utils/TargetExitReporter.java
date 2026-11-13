package com.waenhancer.xposed.utils;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import com.waenhancer.utils.DeclaredWhatsAppTargets;
import com.waenhancer.utils.TargetExitContract;
import com.waenhancer.xposed.bridge.client.PreferenceBridgeClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot Android-owned exit history from the *calling WhatsApp UID* at process startup.
 * This is not a crash/ANR hook, logcat monitor or a request for another app's history.
 * No work at all is scheduled unless both local diagnostic consents were synchronized.
 */
public final class TargetExitReporter {
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private TargetExitReporter() {}

    public static void scheduleOnce(Context context, String target, boolean enabled, long consentAt) {
        if (!enabled || consentAt <= 0 || Build.VERSION.SDK_INT < 30
                || context == null || !DeclaredWhatsAppTargets.contains(target)
                || !target.equals(context.getPackageName())) return;
        if (!STARTED.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        Thread worker = new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                Api30.report(app, target, consentAt);
            } catch (Throwable ignored) {
                // Telemetry is optional; a failed OS query or provider call must not disrupt WA.
            }
        }, "WAEX-Exit-Report");
        worker.setDaemon(true);
        worker.start();
    }

    /** Kept out of the pre-30 code path to avoid loading API30-only class references. */
    private static final class Api30 {
        private static void report(Context context, String target, long consentAt) {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return;
            List<ApplicationExitInfo> history = manager.getHistoricalProcessExitReasons(
                    context.getPackageName(), 0, TargetExitContract.MAX_EVENTS);
            if (history == null) return;
            long now = System.currentTimeMillis();
            long[] timestamps = new long[TargetExitContract.MAX_EVENTS];
            int[] pids = new int[TargetExitContract.MAX_EVENTS];
            int[] reasons = new int[TargetExitContract.MAX_EVENTS];
            int[] statuses = new int[TargetExitContract.MAX_EVENTS];
            long[] pss = new long[TargetExitContract.MAX_EVENTS];
            long[] rss = new long[TargetExitContract.MAX_EVENTS];
            int count = 0;
            for (ApplicationExitInfo exit : history) {
                if (count == TargetExitContract.MAX_EVENTS) break;
                long at = exit.getTimestamp();
                int pid = exit.getPid(), reason = exit.getReason();
                long pssKiB = exit.getPss(), rssKiB = exit.getRss();
                if (!TargetExitContract.isValid(target, now, consentAt, at,
                        pid, reason, pssKiB, rssKiB)) continue;
                timestamps[count] = at;
                pids[count] = pid;
                reasons[count] = reason;
                statuses[count] = exit.getStatus();
                pss[count] = pssKiB;
                rss[count] = rssKiB;
                count++;
            }
            Bundle data = new Bundle();
            data.putString("package", target);
            data.putLongArray("exit_times", java.util.Arrays.copyOf(timestamps, count));
            data.putIntArray("exit_pids", java.util.Arrays.copyOf(pids, count));
            data.putIntArray("exit_reasons", java.util.Arrays.copyOf(reasons, count));
            data.putIntArray("exit_statuses", java.util.Arrays.copyOf(statuses, count));
            data.putLongArray("exit_pss_kib", java.util.Arrays.copyOf(pss, count));
            data.putLongArray("exit_rss_kib", java.util.Arrays.copyOf(rss, count));
            // Existing unstable provider route: no crash-thread IPC, no retry on ambiguity.
            context.getContentResolver().call(
                    Uri.parse("content://" + PreferenceBridgeClient.AUTHORITY),
                    "report_target_exit_history", target, data);
        }
    }
}
