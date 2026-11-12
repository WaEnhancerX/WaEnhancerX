package com.waenhancer.utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.HashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A deliberately narrow, offline diagnostic journal. This is NOT logcat and never accepts
 * message contents, exception messages, arbitrary logs or WhatsApp databases.
 *
 * Only explicitly enumerated events reach private app storage. A two-part, seven-day
 * rolling limit bounds storage to 7 * 2 * 128 KiB (plus one in-progress line).
 * There is no background worker, timer, polling, network transport or per-hook tracing.
 */
public final class DiagnosticsJournal {
    public static final int RETENTION_DAYS = 7;
    public static final int MAX_PART_BYTES = 128 * 1024;
    private static final long DAY_MILLIS = 86_400_000L;
    private static final Set<String> RUNTIME_STATUSES = new HashSet<>(Arrays.asList(
            "INJECTED", "INITIALIZING", "READY", "READY_WITH_ERRORS",
            "UNSUPPORTED_VERSION", "COMPATIBILITY_UNAVAILABLE", "INITIALIZATION_FAILED"));
    private static final Set<String> BRIDGE_STATUSES = new HashSet<>(Arrays.asList(
            "PENDING", "PROVIDER", "DISK_FALLBACK", "FAILED"));
    // These are Screen subclasses, not dynamic routes containing release tags or package data.
    private static final Set<String> SCREEN_NAMES = new HashSet<>(Arrays.asList(
            "MainDashboard", "DashboardBento", "SystemHealth", "GlobalPrivacySettings",
            "ConversationEnhancements", "MediaStatusHub", "AutomationTasker", "AudioTranscription",
            "LicenseActivation", "Search", "PerContactPrivacyList", "StylesSettings",
            "Changelog", "ReleaseDetails", "About", "Stargazers", "ModuleSettings",
            "HookingModeHelp", "UpdateSettings", "SupportedVersions", "BootloaderSpoofer",
            "DeletedMessages", "CallRecordingSettings", "TaskerGuide", "TaskerHistory",
            "ManageDevices", "FloatingBottomBarCustomizer", "FilterItems", "CustomAbProps",
            "CustomAbPropEditor", "InjectedHooks"));

    private final File directory;

    public DiagnosticsJournal(File directory) {
        if (directory == null) throw new IllegalArgumentException("Missing journal directory");
        this.directory = directory;
    }

    public synchronized void recordManagerStarted(long now) throws IOException {
        append(now, "MANAGER_STARTED");
    }

    public synchronized void recordEnabled(long now) throws IOException {
        append(now, "CAPTURE_ENABLED");
    }

    /**
     * Throwable.getMessage(), toString(), file paths and line numbers are intentionally omitted.
     * Restrict frames to WAEX classes and methods to avoid recording unrelated app data.
     */
    public synchronized void recordManagerCrash(long now, Throwable failure) throws IOException {
        if (failure == null) return;
        String kind = safeJavaName(failure.getClass().getName(), 120);
        if (kind == null) kind = "UNKNOWN";
        StringBuilder event = new StringBuilder("MANAGER_CRASH type=").append(kind);
        int frames = 0;
        for (StackTraceElement frame : failure.getStackTrace()) {
            if (frames == 3) break;
            String className = safeJavaName(frame.getClassName(), 120);
            String method = safeJavaName(frame.getMethodName(), 60);
            if (className == null || method == null
                    || !className.startsWith("com.waenhancer.")) continue;
            String frameLabel = " at=" + className + "." + method;
            if (event.length() + frameLabel.length() > 450) break;
            event.append(frameLabel);
            frames++;
        }
        append(now, event.toString());
    }

    /** Call only after HookProvider authenticates the caller and commits the health update. */
    public synchronized void recordHookHealth(long now, String target, String runtime,
                                               String bridge, boolean errorPresent) throws IOException {
        if (!DeclaredWhatsAppTargets.contains(target)
                || !RUNTIME_STATUSES.contains(runtime)
                || !BRIDGE_STATUSES.contains(bridge)) return;
        append(now, "HOOK_HEALTH package=" + target + " status=" + runtime
                + " bridge=" + bridge + " has_error=" + (errorPresent ? "1" : "0"));
    }

    public synchronized void recordScreen(long now, String screenType) throws IOException {
        if (!SCREEN_NAMES.contains(screenType)) return;
        append(now, "WAEX_SCREEN type=" + screenType);
    }

    /** Records which known setting was changed, never any old/new value or target identity. */
    public synchronized void recordPreferenceChange(long now, String key, boolean targetScoped,
                                                     String operation)
            throws IOException {
        com.waenhancer.config.PreferenceSchema.Entry entry =
                com.waenhancer.config.PreferenceSchema.entry(key);
        if (entry == null || !entry.isExportable()
                || (!"PUT".equals(operation) && !"REMOVE".equals(operation))) return;
        append(now, "WAEX_SETTING key=" + entry.key
                + " scope=" + (targetScoped ? "target" : "universal")
                + " action=" + operation);
    }

    public synchronized void recordSettingsTransfer(long now, String kind, int count)
            throws IOException {
        if (count < 0 || count > 10000 || (!"IMPORT".equals(kind) && !"EXPORT".equals(kind)))
            return;
        append(now, "WAEX_SETTINGS_" + kind + " count=" + count);
    }

    /** Compact aggregate, with only validated WAEX feature class names for failed hooks. */
    public synchronized void recordHookSummary(long now, String target, int active, int failed,
                                                int disabled, int waiting, int other,
                                                List<String> failedClasses) throws IOException {
        if (!DeclaredWhatsAppTargets.contains(target)) return;
        long total = (long) active + failed + disabled + waiting + other;
        if (active < 0 || failed < 0 || disabled < 0 || waiting < 0 || other < 0
                || total > 512) return;
        append(now, "HOOK_SUMMARY package=" + target + " total=" + total
                + " active=" + active + " failed=" + failed
                + " disabled=" + disabled + " waiting=" + waiting + " other=" + other);
        if (failed == 0 || failedClasses == null) return;
        Set<String> unique = new LinkedHashSet<>();
        for (String name : failedClasses) {
            if (unique.size() >= Math.min(8, failed)) break;
            if (name != null && name.length() <= 64
                    && name.matches("[A-Za-z_$][A-Za-z0-9_$]*")) unique.add(name);
        }
        for (String name : unique) append(now, "HOOK_FAILED package=" + target + " class=" + name);
    }

    /**
     * Generates a local ZIP for Android's user-confirmed share sheet. Never initiates a send.
     * Only files with our own narrowly defined names are included.
     */
    public synchronized File export(File cacheDirectory, long now, String version, int sdk)
            throws IOException {
        return export(cacheDirectory, now, version, sdk, null);
    }

    public synchronized File export(File cacheDirectory, long now, String version, int sdk,
                                    DiagnosticsSnapshot environment) throws IOException {
        if (cacheDirectory == null || !cacheDirectory.isDirectory()) {
            throw new IOException("Private export cache is unavailable");
        }
        purge(now);
        File[] files = retainedFiles(now);
        if (files.length == 0) throw new IOException("No diagnostic events have been recorded");
        File output = File.createTempFile("waex-diagnostics-", ".zip", cacheDirectory);
        boolean success = false;
        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(output)))) {
            zip.putNextEntry(new ZipEntry("README.txt"));
            String safeVersion = safeVersion(version);
            String summary = "WaEnhancerX opt-in diagnostics v2\n"
                    + "Module version: " + safeVersion + "\n"
                    + "Android SDK: " + Math.max(0, sdk) + "\n"
                    + "Generated epoch ms: " + Math.max(0L, now) + "\n"
                    + "Scope: WAEX manager crashes, screen types, changed setting keys, "
                    + "authenticated hook startup health and failure counts.\n"
                    + "environment.txt, when present, is an on-demand, filtered device and "
                    + "non-secret Boolean configuration snapshot.\n"
                    + "Not collected: chat contents, contacts, message IDs, raw logcat, media, "
                    + "WhatsApp crash stacks or private app storage.\n"
                    + "Retention: seven days, at most two 128-KiB parts per day.\n"
                    + "Review before choosing a Telegram recipient and confirming send.\n";
            zip.write(summary.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (environment != null) {
                zip.putNextEntry(new ZipEntry("environment.txt"));
                zip.write(environment.render().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            byte[] buffer = new byte[8192];
            for (File file : files) {
                if (file.length() > MAX_PART_BYTES + 512L) continue;
                zip.putNextEntry(new ZipEntry(file.getName()));
                try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
                    int n;
                    while ((n = input.read(buffer)) != -1) zip.write(buffer, 0, n);
                }
                zip.closeEntry();
            }
            success = true;
        } finally {
            if (!success) output.delete();
        }
        return output;
    }

    /** Removes only our known event filenames, never sibling application files. */
    public synchronized void clear() {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (parseDay(file.getName()) != null && file.isFile()) file.delete();
        }
    }

    private void append(long now, String event) throws IOException {
        if (now < 0) throw new IOException("Invalid clock");
        // Event text is only constructed from a fixed vocabulary and validated identifiers.
        if (event.length() > 600 || event.indexOf('\n') != -1 || event.indexOf('\r') != -1)
            throw new IOException("Unsafe diagnostic event");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create private diagnostic directory");
        purge(now);
        long day = now / DAY_MILLIS;
        File current = fileFor(day, 0);
        byte[] line = (now + " " + event + "\n").getBytes(StandardCharsets.UTF_8);
        if (line.length > 512) throw new IOException("Event exceeds journal line limit");
        if (current.length() + line.length > MAX_PART_BYTES) {
            File previous = fileFor(day, 1);
            if (previous.exists() && !previous.delete())
                throw new IOException("Cannot rotate diagnostic journal");
            if (current.exists() && !current.renameTo(previous))
                throw new IOException("Cannot rotate diagnostic journal");
        }
        try (FileOutputStream out = new FileOutputStream(current, true)) {
            out.write(line);
        }
    }

    private void purge(long now) {
        File[] files = directory.listFiles();
        if (files == null) return;
        long day = Math.max(0, now) / DAY_MILLIS;
        for (File file : files) {
            Long fileDay = parseDay(file.getName());
            if (fileDay != null && (fileDay < day - RETENTION_DAYS + 1 || fileDay > day)
                    && file.isFile()) file.delete();
        }
    }

    private File[] retainedFiles(long now) {
        File[] matches = directory.listFiles(file -> {
            Long day = parseDay(file.getName());
            long today = Math.max(0, now) / DAY_MILLIS;
            return file.isFile() && day != null && day >= today - RETENTION_DAYS + 1
                    && day <= today;
        });
        if (matches == null) return new File[0];
        Arrays.sort(matches, Comparator.comparing(File::getName));
        return matches;
    }

    private File fileFor(long day, int part) {
        return new File(directory, "events-" + day + "-" + part + ".log");
    }

    private static Long parseDay(String filename) {
        if (!filename.matches("events-[0-9]{1,8}-[01]\\.log")) return null;
        try {
            return Long.parseLong(filename.substring(7, filename.lastIndexOf('-')));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String safeJavaName(String name, int maxLength) {
        if (name == null || name.length() < 1 || name.length() > maxLength
                || !name.matches("[A-Za-z_$][A-Za-z0-9_.$]*")) return null;
        return name;
    }

    private static String safeVersion(String version) {
        return version != null && version.length() <= 40
                && version.matches("[0-9A-Za-z._+-]+") ? version : "unknown";
    }
}
