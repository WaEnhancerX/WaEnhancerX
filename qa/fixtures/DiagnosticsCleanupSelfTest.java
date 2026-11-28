package com.waenhancer.utils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Predicate;

/** Actual cleanup/journal code, owned files, and explicit File-boundary failures. */
public final class DiagnosticsCleanupSelfTest {
    private static final String BLOCKED = "events-1-0.log";
    private static final Predicate<String> OWNED = name -> name.startsWith("events-");
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("waex-diagnostic-cleanup-");
        try {
            testNormalScope(root);
            testJournalNames(root);
            testAbsentAndWrongType(root);
            testInitialListingFailure(root);
            testFinalListingFailure(root);
            testUnknownParent(root);
            testDeleteFailuresAndRetry(root);
            testMetadataFailuresAndRetry(root);
            testPostconditionAndDirectories(root);
            testInvalidArguments(root);
            System.out.println("PASS DiagnosticsCleanupSelfTest checks=" + checks);
        } finally {
            // Only this test's newly created directory; never follow directory links.
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static void testNormalScope(Path root) throws Exception {
        Path dir = directory(root, "normal");
        Path first = file(dir, BLOCKED);
        Path second = file(dir, "events-1-1.log");
        Path unrelated = file(dir, "keep.txt");
        Path nested = file(directory(dir, "keep-directory"), BLOCKED);
        check(DiagnosticsFileCleanup.clearKnownFiles(dir.toFile(), OWNED),
                "normal clear confirmed");
        check(!Files.exists(first) && !Files.exists(second), "owned files removed");
        check(Files.exists(unrelated) && Files.exists(nested), "unrelated files preserved");
        check(DiagnosticsFileCleanup.clearKnownFiles(dir.toFile(), OWNED),
                "repeated clear is successful");
    }

    private static void testJournalNames(Path root) throws Exception {
        Path dir = directory(root, "journal-names");
        Path first = file(dir, BLOCKED);
        Path second = file(dir, "events-99999999-1.log");
        String[] outside = {"events-1-2.log", "events-123456789-0.log",
                "events-x-0.log", "events-1-0.log.bak", "keep.txt"};
        for (String name : outside) file(dir, name);
        check(new DiagnosticsJournal(dir.toFile()).clearWithResult(), "journal clear confirmed");
        check(!Files.exists(first) && !Files.exists(second), "journal owns valid names only");
        for (String name : outside) {
            check(Files.exists(dir.resolve(name)), "journal preserves non-journal name " + name);
        }
    }

    private static void testAbsentAndWrongType(Path root) throws Exception {
        Path absent = root.resolve("absent");
        check(DiagnosticsFileCleanup.clearKnownFiles(absent.toFile(), OWNED),
                "accessible parent proves absence");
        check(!Files.exists(absent), "clear does not create absent directories");
        Path empty = directory(root, "empty");
        check(DiagnosticsFileCleanup.clearKnownFiles(empty.toFile(), OWNED), "empty confirmed");
        Path wrong = file(root, "not-a-directory");
        check(!DiagnosticsFileCleanup.clearKnownFiles(wrong.toFile(), OWNED),
                "regular file at directory path is not success");
        check(Files.isRegularFile(wrong), "wrong-type path preserved");
    }

    private static void testInitialListingFailure(Path root) throws Exception {
        for (Listing failure : new Listing[]{Listing.NULL, Listing.THROW}) {
            Path dir = directory(root, "initial-" + failure);
            Path retained = file(dir, BLOCKED);
            FaultDirectory boundary = new FaultDirectory(dir);
            boundary.initial = failure;
            check(!new DiagnosticsJournal(boundary).clearWithResult(),
                    "unreadable initial listing is not success");
            check(Files.exists(retained), "initial-list failure preserves retry data");
            boundary.initial = Listing.NORMAL;
            check(new DiagnosticsJournal(boundary).clearWithResult(), "initial-list retry succeeds");
            check(!Files.exists(retained), "initial-list retry deletes file");
        }
    }

    private static void testFinalListingFailure(Path root) throws Exception {
        for (Listing failure : new Listing[]{Listing.NULL, Listing.THROW}) {
            Path dir = directory(root, "final-" + failure);
            Path removed = file(dir, BLOCKED);
            FaultDirectory boundary = new FaultDirectory(dir);
            boundary.after = failure;
            check(!DiagnosticsFileCleanup.clearKnownFiles(boundary, OWNED),
                    "unreadable final listing is not success");
            check(!Files.exists(removed), "unverified completion may have removed files");
            boundary.after = Listing.NORMAL;
            check(DiagnosticsFileCleanup.clearKnownFiles(boundary, OWNED),
                    "final-list retry confirms completion");
        }
    }

    private static void testUnknownParent(Path root) {
        for (Listing failure : new Listing[]{Listing.NULL, Listing.THROW}) {
            FaultDirectory absent = new FaultDirectory(root.resolve("unknown-parent-" + failure));
            absent.parent = failure;
            check(!DiagnosticsFileCleanup.clearKnownFiles(absent, OWNED),
                    "unreadable parent cannot prove absence");
            absent.parent = Listing.NORMAL;
            check(DiagnosticsFileCleanup.clearKnownFiles(absent, OWNED),
                    "parent-readable retry proves absence");
        }
    }

    private static void testDeleteFailuresAndRetry(Path root) throws Exception {
        for (ChildFailure failure : new ChildFailure[]{ChildFailure.DELETE_FALSE,
                ChildFailure.DELETE_THROW}) {
            Path dir = directory(root, "delete-" + failure);
            Path retained = file(dir, BLOCKED);
            Path removed = file(dir, "events-1-1.log");
            FaultDirectory boundary = new FaultDirectory(dir);
            boundary.child = failure;
            check(!new DiagnosticsJournal(boundary).clearWithResult(),
                    "retained diagnostic prevents success");
            check(Files.exists(retained), "failed delete remains available for retry");
            check(!Files.exists(removed), "failed first delete does not stop other deletions");
            boundary.child = ChildFailure.NONE;
            check(new DiagnosticsJournal(boundary).clearWithResult(), "delete retry succeeds");
            check(!Files.exists(retained), "retry removes retained diagnostic");
        }
    }

    private static void testMetadataFailuresAndRetry(Path root) throws Exception {
        for (ChildFailure failure : new ChildFailure[]{ChildFailure.IS_FILE_FALSE,
                ChildFailure.IS_FILE_THROW}) {
            Path dir = directory(root, "metadata-" + failure);
            Path retained = file(dir, BLOCKED);
            FaultDirectory boundary = new FaultDirectory(dir);
            boundary.child = failure;
            check(!DiagnosticsFileCleanup.clearKnownFiles(boundary, OWNED),
                    "unknown file metadata is not success");
            check(Files.exists(retained), "metadata failure does not discard file");
            boundary.child = ChildFailure.NONE;
            check(DiagnosticsFileCleanup.clearKnownFiles(boundary, OWNED),
                    "metadata retry succeeds");
            check(!Files.exists(retained), "metadata retry removes file");
        }
    }

    private static void testPostconditionAndDirectories(Path root) throws Exception {
        Path dir = directory(root, "gone-before-postcheck");
        Path removed = file(dir, BLOCKED);
        FaultDirectory boundary = new FaultDirectory(dir);
        boundary.child = ChildFailure.DELETE_GONE_FALSE;
        check(DiagnosticsFileCleanup.clearKnownFiles(boundary, OWNED),
                "observed absence can succeed after a false delete result");
        check(!Files.exists(removed), "false result fixture really removed file");

        Path parent = directory(root, "matching-directory");
        Path matching = directory(parent, BLOCKED);
        Path nested = file(matching, "keep.txt");
        check(!DiagnosticsFileCleanup.clearKnownFiles(parent.toFile(), OWNED),
                "matching directory prevents success");
        check(Files.exists(nested), "matching directory is never recursively deleted");

        Path disappearing = directory(root, "directory-gone-before-postcheck");
        file(disappearing, BLOCKED);
        FaultDirectory gone = new FaultDirectory(disappearing);
        gone.removeBeforeFinalListing = true;
        check(DiagnosticsFileCleanup.clearKnownFiles(gone, OWNED),
                "parent confirms directory disappeared after deletion");
        check(!Files.exists(disappearing), "disappearing directory fixture removed its directory");
    }

    private static void testInvalidArguments(Path root) {
        check(!DiagnosticsFileCleanup.clearKnownFiles(null, OWNED), "null root rejected");
        check(!DiagnosticsFileCleanup.clearKnownFiles(new File("relative-owned-test"), OWNED),
                "relative root rejected");
        check(!DiagnosticsFileCleanup.clearKnownFiles(root.toFile(), null), "null matcher rejected");
    }

    private static Path directory(Path parent, String name) throws IOException {
        return Files.createDirectory(parent.resolve(name));
    }

    private static Path file(Path parent, String name) throws IOException {
        return Files.writeString(parent.resolve(name), "synthetic diagnostic fixture\n",
                StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private enum Listing { NORMAL, NULL, THROW }
    private enum ChildFailure {
        NONE, DELETE_FALSE, DELETE_THROW, IS_FILE_FALSE, IS_FILE_THROW, DELETE_GONE_FALSE
    }

    /** File boundaries only; the production deletion and name policy are not mocked. */
    private static final class FaultDirectory extends File {
        Listing initial = Listing.NORMAL;
        Listing after = Listing.NORMAL;
        Listing parent = Listing.NORMAL;
        ChildFailure child = ChildFailure.NONE;
        boolean removeBeforeFinalListing;

        FaultDirectory(Path path) { super(path.toAbsolutePath().toString()); }

        @Override public File[] listFiles() {
            if (initial == Listing.THROW) throw new SecurityException("fixture initial listing");
            if (initial == Listing.NULL) return null;
            File[] files = super.listFiles();
            if (files == null) return null;
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (int i = 0; i < files.length; i++) {
                if (BLOCKED.equals(files[i].getName())) files[i] = new FaultChild(files[i], child);
            }
            return files;
        }

        @Override public String[] list() {
            if (after == Listing.THROW) throw new SecurityException("fixture final listing");
            if (after == Listing.NULL) return null;
            if (removeBeforeFinalListing) {
                if (!super.delete()) throw new AssertionError("empty fixture directory not deleted");
                return null;
            }
            return super.list();
        }

        @Override public File getParentFile() {
            File actual = super.getParentFile();
            if (actual == null || parent == Listing.NORMAL) return actual;
            return new File(actual.getPath()) {
                @Override public String[] list() {
                    if (parent == Listing.THROW) throw new SecurityException("fixture parent listing");
                    return null;
                }
            };
        }
    }

    private static final class FaultChild extends File {
        private final ChildFailure failure;
        FaultChild(File actual, ChildFailure failure) {
            super(actual.getPath());
            this.failure = failure;
        }

        @Override public boolean isFile() {
            if (failure == ChildFailure.IS_FILE_THROW) throw new SecurityException("fixture metadata");
            return failure != ChildFailure.IS_FILE_FALSE && super.isFile();
        }

        @Override public boolean delete() {
            if (failure == ChildFailure.DELETE_THROW) throw new SecurityException("fixture delete");
            if (failure == ChildFailure.DELETE_FALSE) return false;
            if (failure == ChildFailure.DELETE_GONE_FALSE) {
                if (!super.delete()) throw new AssertionError("fixture file did not disappear");
                return false;
            }
            return super.delete();
        }
    }
}
