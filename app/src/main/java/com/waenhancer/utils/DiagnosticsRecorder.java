package com.waenhancer.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import com.waenhancer.config.PreferenceSchema;
import com.waenhancer.config.PreferenceStores;

import java.io.File;
import java.io.IOException;

/**
 * Opt-in Android adapter for the bounded journal. Runs exclusively in the WAEX manager/
 * provider process; it never installs a crash handler or a file writer in WhatsApp.
 */
public final class DiagnosticsRecorder {
    private static final String DIRECTORY = "waex-diagnostics";
    private static final String EXPORT_DIRECTORY = "waex-diagnostic-exports";
    private static final long EXPORTED_FILE_LIFETIME_MS = 48L * 60 * 60 * 1000;

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
            committed = prefs.edit().putBoolean(
                    PreferenceSchema.KEY_DIAGNOSTIC_CAPTURE_ENABLED, enabled).commit();
        } catch (RuntimeException ignored) {
            return false;
        }
        if (!committed) return false;
        if (enabled) {
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
                                        String bridge, boolean errorPresent) {
        try {
            if (!isEnabled(context)) return;
            journal(context).recordHookHealth(System.currentTimeMillis(), target,
                    runtime, bridge, errorPresent);
        } catch (IOException | RuntimeException ignored) {}
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
        return journal(context).export(exports, System.currentTimeMillis(), version,
                Build.VERSION.SDK_INT);
    }

    /** Delete the local journal and its previously generated share copies only. */
    public static void clear(Context context) {
        journal(context).clear();
        File exports = new File(context.getCacheDir(), EXPORT_DIRECTORY);
        File[] copies = exports.listFiles();
        if (copies == null) return;
        for (File copy : copies) {
            if (isExport(copy)) copy.delete();
        }
    }

    private static DiagnosticsJournal journal(Context context) {
        // noBackupFilesDir is private to this app and excludes diagnostics from cloud backups.
        return new DiagnosticsJournal(new File(context.getNoBackupFilesDir(), DIRECTORY));
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
