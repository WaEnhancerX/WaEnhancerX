package com.waenhancer.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import com.waenhancer.config.PreferenceSchema;
import com.waenhancer.config.PreferenceStores;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opt-in Android adapter for the bounded journal. Runs exclusively in the WAEX manager/
 * provider process; it never installs a crash handler or a file writer in WhatsApp.
 */
public final class DiagnosticsRecorder {
    private static final String DIRECTORY = "waex-diagnostics";
    private static final String EXPORT_DIRECTORY = "waex-diagnostic-exports";
    private static final long EXPORTED_FILE_LIFETIME_MS = 48L * 60 * 60 * 1000;
    /** Serialize UI, provider and fatal-handler writes to the same private journal. */
    private static final ConcurrentHashMap<String, DiagnosticsJournal> JOURNALS =
            new ConcurrentHashMap<>();
    private static final DiagnosticsHealthGate HEALTH_GATE = new DiagnosticsHealthGate();
    private static final String SEEN_TARGET_EXITS_PREFIX = "diagnostic_seen_target_exits_";

    private DiagnosticsRecorder() {}

    public static boolean isEnabled(Context context) {
        try {
            return PreferenceStores.publicStore(context).getBoolean(
                    PreferenceSchema.KEY_DIAGNOSTIC_CAPTURE_ENABLED, false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Consent is per Android user/profile and intentionally not exported in settings backups. */
    public static boolean setEnabled(Context context, boolean enabled) {
        boolean committed;
        try {
            SharedPreferences prefs = PreferenceStores.publicStore(context);
            SharedPreferences.Editor editor = prefs.edit().putBoolean(
                    PreferenceSchema.KEY_DIAGNOSTIC_CAPTURE_ENABLED, enabled);
            if (!enabled) {
                // Turning off basic capture also revokes advanced host-process consent.
                editor.putBoolean(PreferenceSchema.KEY_DIAGNOSTIC_TARGET_EXIT_ENABLED, false)
                        .putLong(PreferenceSchema.KEY_DIAGNOSTIC_TARGET_EXIT_SINCE, 0L);
            }
            committed = editor.commit();
        } catch (RuntimeException ignored) {
            return false;
        }
        if (!committed) return false;
        if (enabled) {
            HEALTH_GATE.clear();
            try {
                installManagerCrashCapture(context);
            } catch (RuntimeException ignored) {}
            try {
                journal(context).recordEnabled(System.currentTimeMillis());
            } catch (IOException | SecurityException ignored) {
                // Diagnostic storage must never disrupt the manager or preference state.
            }
        }
        return true;
    }

    public static boolean isAdvancedEnabled(Context context) {
        try {
            SharedPreferences store = PreferenceStores.publicStore(context);
            return store.getBoolean(PreferenceSchema.KEY_DIAGNOSTIC_CAPTURE_ENABLED, false)
                    && store.getBoolean(PreferenceSchema.KEY_DIAGNOSTIC_TARGET_EXIT_ENABLED, false)
                    && store.getLong(PreferenceSchema.KEY_DIAGNOSTIC_TARGET_EXIT_SINCE, 0L) > 0L;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Advanced consent begins now: previous WhatsApp exits are never backfilled. */
    public static boolean setAdvancedEnabled(Context context, boolean enabled) {
        try {
            if (enabled && Build.VERSION.SDK_INT < 30) return false;
            SharedPreferences store = PreferenceStores.publicStore(context);
            if (enabled && !store.getBoolean(
                    PreferenceSchema.KEY_DIAGNOSTIC_CAPTURE_ENABLED, false)) return false;
            long since = enabled ? System.currentTimeMillis() : 0L;
            return store.edit()
                    .putBoolean(PreferenceSchema.KEY_DIAGNOSTIC_TARGET_EXIT_ENABLED, enabled)
                    .putLong(PreferenceSchema.KEY_DIAGNOSTIC_TARGET_EXIT_SINCE, since)
                    .commit();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Register once at manager startup; do not displace the existing crash handler. */
    public static synchronized void installManagerCrashCapture(Context context) {
        if (!isEnabled(context)) return;
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous == null || previous instanceof DiagnosticsCrashHandler) return;
        Context appContext = context.getApplicationContext();
        Thread.setDefaultUncaughtExceptionHandler(new DiagnosticsCrashHandler(
                () -> isEnabled(appContext),
                failure -> journal(appContext).recordManagerCrash(
                        System.currentTimeMillis(), failure),
                previous));
    }

    public static void recordManagerStarted(Context context) {
        if (!isEnabled(context)) return;
        try {
            journal(context).recordManagerStarted(System.currentTimeMillis());
        } catch (IOException | SecurityException ignored) {}
    }

    /**
     * Called only after the existing HookProvider UID/package check and successful commit.
     * This adds no IPC calls, WhatsApp callbacks or background work.
     */
    public static void recordHookHealth(Context context, String target, String runtime,
                                        String bridge, boolean errorPresent, int targetPid) {
        try {
            if (!isEnabled(context)) return;
            HEALTH_GATE.recordIfChanged(target, targetPid, runtime, bridge, errorPresent,
                    () -> journal(context).recordHookHealth(System.currentTimeMillis(), target,
                            runtime, bridge, errorPresent));
        } catch (IOException | RuntimeException ignored) {}
    }

    public static void recordScreen(Context context, String screenType) {
        try {
            if (!isEnabled(context)) return;
            journal(context).recordScreen(System.currentTimeMillis(), screenType);
        } catch (IOException | RuntimeException ignored) {}
    }

    /** Only a schema-declared setting key and universal/target scope are retained. */
    public static void recordPreferenceChange(Context context, String key, boolean targetScoped,
                                              String operation) {
        try {
            if (!isEnabled(context)) return;
            journal(context).recordPreferenceChange(System.currentTimeMillis(), key,
                    targetScoped, operation);
        } catch (IOException | RuntimeException ignored) {}
    }

    public static void recordSettingsTransfer(Context context, String kind, int count) {
        try {
            if (!isEnabled(context)) return;
            journal(context).recordSettingsTransfer(System.currentTimeMillis(), kind, count);
        } catch (IOException | RuntimeException ignored) {}
    }

    /**
     * Called only after the existing HookProvider UID/package authentication.
     * The caller submits only fixed typed OS fields; no user strings or trace streams.
     * A private seen-set prevents the same OS ring-buffer entries from being logged
     * again after each WhatsApp process restart, including within a work profile.
     */
    public static synchronized int recordTargetExitHistory(Context context, String target,
            long[] times, int[] pids, int[] reasons, int[] statuses,
            long[] pss, long[] rss) {
        if (!DeclaredWhatsAppTargets.contains(target) || !isAdvancedEnabled(context)
                || times == null || pids == null || reasons == null || statuses == null
                || pss == null || rss == null
                || times.length > TargetExitContract.MAX_EVENTS
                || pids.length != times.length || reasons.length != times.length
                || statuses.length != times.length || pss.length != times.length
                || rss.length != times.length) return -1;
        try {
            long now = System.currentTimeMillis();
            long since = PreferenceStores.publicStore(context).getLong(
                    PreferenceSchema.KEY_DIAGNOSTIC_TARGET_EXIT_SINCE, 0L);
            if (times.length == 0) {
                journal(context).recordTargetExitScan(now, target, 0, 0);
                return 0;
            }
            SharedPreferences privateStore = PreferenceStores.privateStore(context);
            String seenKey = SEEN_TARGET_EXITS_PREFIX + target;
            Set<String> seen = new LinkedHashSet<>(privateStore.getStringSet(
                    seenKey, java.util.Collections.emptySet()));
            // Expired IDs are not retained indefinitely; only recent exit IDs are relevant.
            seen.removeIf(id -> {
                try {
                    int colon = id.indexOf(':');
                    return colon <= 0 || now - Long.parseLong(id.substring(0, colon))
                            > TargetExitContract.RETENTION_MS;
                } catch (RuntimeException ignored) { return true; }
            });
            int recorded = 0;
            for (int i = times.length - 1; i >= 0; i--) {
                if (!TargetExitContract.isValid(target, now, since, times[i],
                        pids[i], reasons[i], pss[i], rss[i])) continue;
                String id = TargetExitContract.identity(times[i], pids[i], reasons[i], statuses[i]);
                if (seen.contains(id)) continue;
                if (journal(context).recordTargetExit(now, since, target, times[i],
                        pids[i], reasons[i], statuses[i], pss[i], rss[i])) {
                    seen.add(id);
                    recorded++;
                }
            }
            while (seen.size() > TargetExitContract.MAX_SEEN_KEYS) {
                Iterator<String> iterator = seen.iterator();
                iterator.next();
                iterator.remove();
            }
            if (!privateStore.edit().putStringSet(seenKey, seen).commit()) return -1;
            journal(context).recordTargetExitScan(now, target, times.length, recorded);
            return recorded;
        } catch (IOException | RuntimeException ignored) {
            return -1;
        }
    }

    /**
     * Summarize the JSON already sent in the existing authenticated IPC call.
     * Never serialize raw hook JSON, display names or exception messages.
     */
    public static void recordHookSummary(Context context, String target, String hooksJson) {
        try {
            if (!isEnabled(context) || !DeclaredWhatsAppTargets.contains(target)
                    || hooksJson == null || hooksJson.length() > 192 * 1024) return;
            JSONArray entries = new JSONArray(hooksJson);
            if (entries.length() > 512) return;
            int active = 0, failed = 0, disabled = 0, waiting = 0, other = 0;
            List<String> classes = new ArrayList<>();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.optJSONObject(i);
                if (item == null) { other++; continue; }
                String status = item.optString("status", "");
                switch (status) {
                    case "ACTIVE": active++; break;
                    case "FAILED":
                        failed++;
                        if (classes.size() < 8) classes.add(item.optString("className", ""));
                        break;
                    case "DISABLED": disabled++; break;
                    case "WAITING": waiting++; break;
                    default: other++; break;
                }
            }
            journal(context).recordHookSummary(System.currentTimeMillis(), target,
                    active, failed, disabled, waiting, other, classes);
        } catch (Exception ignored) {
            // Malformed, oversized or inaccessible reports must not affect hook IPC.
        }
    }

    /** Create a private local ZIP; the UI must explicitly launch a user-confirmed share. */
    public static File export(Context context) throws IOException {
        File exports = new File(context.getCacheDir(), EXPORT_DIRECTORY);
        if (!exports.isDirectory() && !exports.mkdirs()) {
            throw new IOException("Cannot create private diagnostic export cache");
        }
        pruneOldExports(exports, System.currentTimeMillis());
        String version = "unknown";
        try {
            version = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {}
        DiagnosticsSnapshot environment = null;
        try {
            environment = DiagnosticsEnvironment.capture(context);
        } catch (RuntimeException ignored) {}
        return journal(context).export(exports, System.currentTimeMillis(), version,
                Build.VERSION.SDK_INT, environment);
    }

    /** Delete the local journal and its previously generated share copies only. */
    public static void clear(Context context) {
        journal(context).clear();
        HEALTH_GATE.clear();
        File exports = new File(context.getCacheDir(), EXPORT_DIRECTORY);
        File[] copies = exports.listFiles();
        if (copies == null) return;
        for (File copy : copies) {
            if (isExport(copy)) copy.delete();
        }
    }

    private static DiagnosticsJournal journal(Context context) {
        // noBackupFilesDir is private to this app and excludes diagnostics from cloud backups.
        File directory = new File(context.getNoBackupFilesDir(), DIRECTORY);
        return JOURNALS.computeIfAbsent(directory.getAbsolutePath(),
                ignored -> new DiagnosticsJournal(directory));
    }

    private static void pruneOldExports(File directory, long now) {
        File[] copies = directory.listFiles();
        if (copies == null) return;
        for (File copy : copies) {
            if (isExport(copy) && now - copy.lastModified() > EXPORTED_FILE_LIFETIME_MS) {
                copy.delete();
            }
        }
    }

    private static boolean isExport(File file) {
        return file.isFile() && file.getName().matches("waex-diagnostics-[A-Za-z0-9-]+\\.zip");
    }

}
