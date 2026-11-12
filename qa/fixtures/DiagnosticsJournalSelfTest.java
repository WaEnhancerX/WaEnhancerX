import com.waenhancer.utils.DiagnosticsJournal;
import com.waenhancer.utils.DiagnosticsCrashHandler;
import com.waenhancer.utils.DiagnosticsSnapshot;
import com.waenhancer.utils.DiagnosticsHealthGate;
import com.waenhancer.config.PreferenceSchema;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Runs against production DiagnosticsJournal with only the JDK; no Android mocks. */
public final class DiagnosticsJournalSelfTest {
    private static final long DAY = 86_400_000L;
    private static int cases;

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("waex-diagnostics-qa-").toFile();
        try {
            testOptInPreferenceContract();
            testHeartbeatDeduplication();
            testUserBreadcrumbsAndHookFailures(root);
            testOnDemandSnapshotPrivacy(root);
            testCrashHandlerDelegation();
            testExportEmpty(root);
            testOnlySafeEvents(root);
            testCrashContentPrivacy(root);
            testExportAndRetention(root);
            testRotationBounds(root);
            testConcurrentWriters(root);
            testClockRollback(root);
            testClearScope(root);
            System.out.println("PASS DiagnosticsJournalSelfTest cases=" + cases);
        } finally {
            deleteTree(root);
        }
    }

    private static void testHeartbeatDeduplication() throws Exception {
        DiagnosticsHealthGate gate = new DiagnosticsHealthGate();
        AtomicInteger writes = new AtomicInteger();
        DiagnosticsHealthGate.Writer writer = writes::incrementAndGet;
        check(gate.recordIfChanged("com.whatsapp", 1234, "READY", "PROVIDER", false, writer),
                "first authenticated process health recorded");
        for (int i = 0; i < 100; i++) {
            check(!gate.recordIfChanged("com.whatsapp", 1234, "READY", "PROVIDER",
                    false, writer), "identical ten-second heartbeat not written");
        }
        check(writes.get() == 1, "one journal write for 101 identical heartbeats");
        check(gate.recordIfChanged("com.whatsapp", 1234, "READY_WITH_ERRORS",
                "PROVIDER", true, writer), "error transition captured");
        check(gate.recordIfChanged("com.whatsapp", 5678, "READY_WITH_ERRORS",
                "PROVIDER", true, writer), "new host PID captured");
        check(!gate.recordIfChanged("com.evil.app", 5678, "READY",
                "PROVIDER", false, writer), "undeclared host rejected");
        check(writes.get() == 3, "only meaningful transitions written");
        gate.clear();
        check(gate.recordIfChanged("com.whatsapp", 5678, "READY_WITH_ERRORS",
                "PROVIDER", true, writer), "new consent session resets heartbeat gate");

        DiagnosticsHealthGate failing = new DiagnosticsHealthGate();
        try {
            failing.recordIfChanged("com.whatsapp", 999, "READY", "PROVIDER", false,
                    () -> { throw new IOException("injected storage failure"); });
            throw new AssertionError("Expected write failure");
        } catch (IOException expected) {
            check(true, "negative control: writer failure propagated");
        }
        check(failing.recordIfChanged("com.whatsapp", 999, "READY",
                "PROVIDER", false, writer), "failed write is retried on the next heartbeat");
    }

    private static void testUserBreadcrumbsAndHookFailures(File root) throws Exception {
        File dir = new File(root, "structured");
        DiagnosticsJournal journal = new DiagnosticsJournal(dir);
        long now = 20_000 * DAY;
        journal.recordScreen(now, "MainDashboard");
        journal.recordScreen(now + 1, "ReleaseDetails");
        journal.recordScreen(now + 2, "release/SECRET_TAG");
        journal.recordScreen(now + 3, "InjectedHooks/com.whatsapp/private-user");
        journal.recordPreferenceChange(now + 4, "anti_revoke", false, "PUT");
        journal.recordPreferenceChange(now + 5, "anti_revoke", true, "REMOVE");
        journal.recordPreferenceChange(now + 6, "assemblyai_key", false, "PUT");
        journal.recordPreferenceChange(now + 7, "contact:+56911111111", false, "PUT");
        journal.recordPreferenceChange(now + 8, "anti_revoke", true, "INVALID");
        journal.recordSettingsTransfer(now + 9, "IMPORT", 42);
        journal.recordSettingsTransfer(now + 10, "EXPORT", 15);
        journal.recordSettingsTransfer(now + 11, "IMPORT", -1);
        journal.recordHookSummary(now + 12, "com.whatsapp", 22, 2, 7, 1, 0,
                List.of("DownloadProfileFeature", "PrivacyFeature",
                        "SECRET\nUSER_TEXT", "com.waenhancer.sensitive.Phone"));
        journal.recordHookSummary(now + 13, "com.evil.app", 1, 0, 0, 0, 0,
                List.of("Forged"));
        journal.recordHookSummary(now + 14, "com.whatsapp", 500, 20, 0, 0, 0,
                List.of("Oversized"));
        String text = content(dir);
        check(text.contains("WAEX_SCREEN type=MainDashboard"), "static screen recorded");
        check(text.contains("WAEX_SCREEN type=ReleaseDetails"), "dynamic screen class retained");
        check(text.contains("WAEX_SETTING key=anti_revoke scope=universal action=PUT"),
                "known setting key without value");
        check(text.contains("WAEX_SETTING key=anti_revoke scope=target action=REMOVE"),
                "target scope and removal distinguished");
        check(text.contains("WAEX_SETTINGS_IMPORT count=42"), "import summary");
        check(text.contains("WAEX_SETTINGS_EXPORT count=15"), "export summary");
        check(text.contains("HOOK_SUMMARY package=com.whatsapp total=32"),
                "hook installation totals");
        check(text.contains("HOOK_FAILED package=com.whatsapp class=PrivacyFeature"),
                "safe failed hook identifiers");
        for (String secret : List.of("SECRET_TAG", "private-user", "assemblyai_key",
                "contact:", "+56911111111", "USER_TEXT", "com.evil.app",
                "Oversized", "status=INVALID")) {
            check(!text.contains(secret), "sensitive or invalid breadcrumb omitted: " + secret);
        }
    }

    private static void testOnDemandSnapshotPrivacy(File root) throws Exception {
        DiagnosticsSnapshot snapshot = new DiagnosticsSnapshot();
        snapshot.addDevice("Google", "Pixel 8", "2026-11-01", "arm64-v8a");
        snapshot.addRuntime("LSPosed", 100);
        snapshot.addResources(8_000_000_000L, 4_000_000_000L, 128_000_000L,
                20_000_000_000L, 47);
        snapshot.addTargetVersion("com.whatsapp", "2.26.42.10", 20422010);
        snapshot.addTargetVersion("com.evil.private", "PRIVATE_VERSION", 123);
        snapshot.addPreference("anti_revoke", true, false);
        snapshot.addPreference("assemblyai_key", true, false);
        snapshot.addPreference("unknown_personal_key", true, true);
        snapshot.addExit(20_000 * DAY, 20_000 * DAY - 1000, 6, 0, 35000, 49000);
        snapshot.addExit(20_000 * DAY, 20_000 * DAY - 8 * DAY, 6, 0, 35000, 49000);
        String rendered = snapshot.render();
        check(rendered.contains("device.model=Pixel 8"), "device model captured at export");
        check(rendered.contains("target.com.whatsapp.version=2.26.42.10"),
                "declared target version captured");
        check(rendered.contains("setting.universal.anti_revoke=true"),
                "known public feature flag captured");
        check(rendered.contains("exit.at_ms="), "own-process exit metadata captured");
        for (String secret : List.of("PRIVATE_VERSION", "com.evil.private",
                "assemblyai_key", "unknown_personal_key", "serial=")) {
            check(!rendered.contains(secret), "snapshot omits sensitive content: " + secret);
        }
        DiagnosticsJournal journal = new DiagnosticsJournal(new File(root, "snapshot"));
        journal.recordEnabled(20_000 * DAY);
        File exports = new File(root, "snapshot-exports");
        check(exports.mkdir(), "create snapshot export directory");
        File zipFile = journal.export(exports, 20_000 * DAY, "2.0.4", 35, snapshot);
        try (ZipFile zip = new ZipFile(zipFile)) {
            ZipEntry entry = zip.getEntry("environment.txt");
            check(entry != null, "environment in consented export ZIP");
            String text = new String(zip.getInputStream(entry).readAllBytes(),
                    StandardCharsets.UTF_8);
            check(text.equals(rendered), "ZIP environment bytes equal validated snapshot");
        }
    }

    private static void testCrashHandlerDelegation() {
        Thread crashedThread = Thread.currentThread();
        RuntimeException original = new RuntimeException("sensitive exception message");
        AtomicInteger reported = new AtomicInteger();
        AtomicInteger delegated = new AtomicInteger();
        Thread.UncaughtExceptionHandler next = (thread, failure) -> {
            check(thread == crashedThread, "original crash thread delegated");
            check(failure == original, "original throwable delegated");
            delegated.incrementAndGet();
        };
        DiagnosticsCrashHandler disabled = new DiagnosticsCrashHandler(
                () -> false, failure -> reported.incrementAndGet(), next);
        disabled.uncaughtException(crashedThread, original);
        check(reported.get() == 0 && delegated.get() == 1,
                "disabled capture never records and still delegates");
        DiagnosticsCrashHandler enabled = new DiagnosticsCrashHandler(
                () -> true, failure -> {
                    check(failure == original, "crash sink receives exact original throwable");
                    reported.incrementAndGet();
                }, next);
        enabled.uncaughtException(crashedThread, original);
        check(reported.get() == 1 && delegated.get() == 2,
                "enabled capture records then delegates once");
        DiagnosticsCrashHandler failingSink = new DiagnosticsCrashHandler(
                () -> true, failure -> { throw new IOException("cannot write"); }, next);
        failingSink.uncaughtException(crashedThread, original);
        check(delegated.get() == 3, "I/O failure never suppresses fatal delegation");
        DiagnosticsCrashHandler failingConsent = new DiagnosticsCrashHandler(
                () -> { throw new IllegalStateException("prefs failed"); },
                failure -> reported.incrementAndGet(), next);
        failingConsent.uncaughtException(crashedThread, original);
        check(reported.get() == 1 && delegated.get() == 4,
                "consent failure skips capture but delegates");
    }

    private static void testOptInPreferenceContract() {
        String key = PreferenceSchema.KEY_DIAGNOSTIC_CAPTURE_ENABLED;
        PreferenceSchema.Entry entry = PreferenceSchema.entry(key);
        check(entry != null, "diagnostic opt-in is a schema key");
        check(entry.type == PreferenceSchema.Type.BOOLEAN, "opt-in is boolean");
        check(entry.store == PreferenceSchema.Store.PUBLIC, "provider can read consent");
        check(entry.sensitivity == PreferenceSchema.Sensitivity.RUNTIME,
                "opt-in remains local runtime state");
        check(!PreferenceSchema.isExportable(key), "settings backup cannot re-enable capture");
        check(!PreferenceSchema.exportableKeys().contains(key), "backup key set excludes consent");
        check(PreferenceSchema.publicKeys().contains(key), "provider shares one consent key");
    }

    private static void testExportEmpty(File root) throws Exception {
        File dir = new File(root, "empty");
        File exports = new File(root, "empty-exports");
        check(exports.mkdir(), "create export dir");
        DiagnosticsJournal journal = new DiagnosticsJournal(dir);
        expectIoFailure(() -> journal.export(exports, 100 * DAY, "2.0.4", 35));
        check(!dir.exists(), "export of empty journal must not create data files");
    }

    private static void testOnlySafeEvents(File root) throws Exception {
        File dir = new File(root, "safe");
        DiagnosticsJournal journal = new DiagnosticsJournal(dir);
        long now = 20_000 * DAY;
        journal.recordEnabled(now);
        journal.recordManagerStarted(now + 1);
        journal.recordHookHealth(now + 2, "com.whatsapp", "READY", "PROVIDER", false);
        journal.recordHookHealth(now + 3, "com.whatsapp.w4b", "READY_WITH_ERRORS", "FAILED", true);
        String before = content(dir);
        check(before.contains("CAPTURE_ENABLED"), "opt-in marker");
        check(before.contains("MANAGER_STARTED"), "startup marker");
        check(before.contains("package=com.whatsapp status=READY bridge=PROVIDER has_error=0"),
                "authenticated health details");
        journal.recordHookHealth(now + 4, "com.evil.app", "READY", "PROVIDER", false);
        journal.recordHookHealth(now + 5, "com.whatsapp", "READY\nSECRET_TEXT", "PROVIDER", false);
        journal.recordHookHealth(now + 6, "com.whatsapp", "READY", "UNTRUSTED", false);
        check(before.equals(content(dir)), "untrusted event data rejected exactly");
        check(!content(dir).contains("SECRET_TEXT"), "rejected injection absent");
    }

    private static void testCrashContentPrivacy(File root) throws Exception {
        File dir = new File(root, "crash");
        DiagnosticsJournal journal = new DiagnosticsJournal(dir);
        RuntimeException exception = new RuntimeException(
                "PRIVATE_CHAT_TEXT phone +56911111111 password=SECRET123");
        exception.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.waenhancer.feature.Hooks", "initialize",
                        "/private/data/WhatsApp.db", 419),
                new StackTraceElement("com.example.external", "message_888", "CHAT.txt", 12),
                new StackTraceElement("com.waenhancer.ui.Settings", "open", "Settings.java", 19)
        });
        journal.recordManagerCrash(20_000 * DAY, exception);
        String recorded = content(dir);
        check(recorded.contains("MANAGER_CRASH type=java.lang.RuntimeException"),
                "exception type captured");
        check(recorded.contains("com.waenhancer.feature.Hooks.initialize"),
                "WAEX frame captured");
        String longIdentifier = "L".repeat(100);
        RuntimeException longStack = new RuntimeException("PRIVATE_CONTENT");
        longStack.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.waenhancer." + longIdentifier, longIdentifier, "private", 9),
                new StackTraceElement("com.waenhancer." + longIdentifier, longIdentifier, "private", 10),
                new StackTraceElement("com.waenhancer." + longIdentifier, longIdentifier, "private", 11)
        });
        journal.recordManagerCrash(20_000 * DAY + 1, longStack);
        check(content(dir).lines().count() == 2, "long crash stacks remain recordable");
        check(!content(dir).contains("PRIVATE_CONTENT"), "long crash message omitted");
        for (String forbidden : new String[]{"PRIVATE_CHAT_TEXT", "+56911111111",
                "password=", "SECRET123", "WhatsApp.db", "419",
                "com.example.external", "message_888"}) {
            check(!recorded.contains(forbidden), "private crash detail omitted: " + forbidden);
        }
    }

    private static void testExportAndRetention(File root) throws Exception {
        File dir = new File(root, "retention");
        File exports = new File(root, "retention-exports");
        check(exports.mkdir(), "create export dir");
        DiagnosticsJournal journal = new DiagnosticsJournal(dir);
        long today = 20_000 * DAY;
        journal.recordManagerStarted(today - 8 * DAY);
        journal.recordManagerStarted(today - 6 * DAY);
        journal.recordHookHealth(today, "com.whatsapp", "READY", "PROVIDER", false);
        File zipFile = journal.export(exports, today, "2.0.4-debug", 35);
        check(zipFile.isFile(), "ZIP created");
        try (ZipFile zip = new ZipFile(zipFile)) {
            check(zip.getEntry("README.txt") != null, "README in ZIP");
            check(zip.getEntry("events-19992-0.log") == null, "eight-day old events omitted");
            check(zip.getEntry("events-19994-0.log") != null, "seven-day retention boundary");
            check(zip.getEntry("events-20000-0.log") != null, "today's events exported");
            String readme = new String(zip.getInputStream(zip.getEntry("README.txt"))
                    .readAllBytes(), StandardCharsets.UTF_8);
            check(readme.contains("Module version: 2.0.4-debug"), "safe version recorded");
            check(readme.contains("Not collected: chat contents"), "scope explained");
        }
        check(!new File(dir, "events-19992-0.log").exists(), "expired file purged");
        check(new File(dir, "events-19994-0.log").exists(), "retained file preserved");
    }

    private static void testRotationBounds(File root) throws Exception {
        File dir = new File(root, "rotation");
        DiagnosticsJournal journal = new DiagnosticsJournal(dir);
        long today = 20_000 * DAY;
        for (int i = 0; i < 10_000; i++) {
            journal.recordHookHealth(today + i, "com.whatsapp",
                    "INITIALIZATION_FAILED", "DISK_FALLBACK", true);
        }
        File[] files = dir.listFiles();
        check(files != null && files.length == 2, "at most two daily parts");
        long total = 0;
        for (File file : files) {
            check(file.length() <= DiagnosticsJournal.MAX_PART_BYTES,
                    "every part is bounded");
            total += file.length();
        }
        check(total <= 2L * DiagnosticsJournal.MAX_PART_BYTES, "hard storage cap");
        check(content(dir).contains("HOOK_HEALTH"), "last events retained after rotation");
    }

    private static void testConcurrentWriters(File root) throws Exception {
        File dir = new File(root, "concurrent");
        DiagnosticsJournal journal = new DiagnosticsJournal(dir);
        long today = 20_000 * DAY;
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int worker = 0; worker < 4; worker++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 100; i++) journal.recordManagerStarted(today + i);
                } catch (Throwable failure) {
                    error.set(failure);
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
        check(error.get() == null, "concurrent appends succeed");
        check(content(dir).lines().count() == 400, "400 intact lines from four writers");
    }

    private static void testClockRollback(File root) throws Exception {
        File dir = new File(root, "clock");
        DiagnosticsJournal journal = new DiagnosticsJournal(dir);
        journal.recordManagerStarted(20_000 * DAY);
        journal.recordManagerStarted(19_990 * DAY);
        check(!new File(dir, "events-20000-0.log").exists(),
                "future-dated file removed after clock rollback");
        check(new File(dir, "events-19990-0.log").exists(),
                "current event survives");
    }

    private static void testClearScope(File root) throws Exception {
        File dir = new File(root, "clear");
        DiagnosticsJournal journal = new DiagnosticsJournal(dir);
        journal.recordManagerStarted(20_000 * DAY);
        File unrelated = new File(dir, "user-notes.txt");
        Files.writeString(unrelated.toPath(), "do not delete");
        journal.clear();
        check(unrelated.exists(), "unrelated file preserved");
        check(new File(dir, "events-20000-0.log").exists() == false,
                "owned event files deleted");
    }

    private static String content(File dir) throws IOException {
        StringBuilder result = new StringBuilder();
        File[] files = dir.listFiles((d, name) -> name.startsWith("events-"));
        if (files == null) return "";
        for (File file : files) result.append(Files.readString(file.toPath()));
        return result.toString();
    }

    private static void expectIoFailure(ThrowingAction operation) throws Exception {
        try {
            operation.run();
            throw new AssertionError("Expected IOException");
        } catch (IOException expected) {
            cases++;
        }
    }

    private static void check(boolean condition, String detail) {
        cases++;
        if (!condition) throw new AssertionError(detail);
    }

    private static void deleteTree(File root) throws IOException {
        if (!root.exists()) return;
        File[] children = root.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        Files.delete(root.toPath());
    }

    @FunctionalInterface
    private interface ThrowingAction { void run() throws Exception; }
}
