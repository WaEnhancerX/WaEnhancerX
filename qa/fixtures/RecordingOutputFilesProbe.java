package com.waenhancer.calls;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Runs real file-allocation operations without an Android or media-recorder double. */
public final class RecordingOutputFilesProbe {
    private static int assertions;

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        check(root.isDirectory(), "fixture directory missing");

        Date timestamp = new Date(1_730_000_000_000L);
        UUID a = new UUID(0, 1);
        UUID b = new UUID(0, 2);
        String firstName = RecordingOutputFiles.formatName(timestamp, a, ".m4a");
        String secondName = RecordingOutputFiles.formatName(timestamp, b, ".m4a");
        check(!firstName.equals(secondName), "same-second calls reused a filename");
        check(firstName.matches("WhatsApp_Call_[0-9]{8}_[0-9]{6}_[0-9a-f]{32}\\.m4a"),
                "generated name is not a safe timestamp plus nonce");
        check(RecordingOutputFiles.formatName(timestamp, a, ".opus").endsWith(".opus"),
                "Opus extension was lost");
        try {
            RecordingOutputFiles.formatName(timestamp, a, ".exe");
            throw new AssertionError("invalid recording extension accepted");
        } catch (IllegalArgumentException expected) {
            assertions++;
        }

        Set<String> generated = new HashSet<>();
        for (int index = 0; index < 3000; index++) {
            String name = RecordingOutputFiles.newName(".m4a");
            check(generated.add(name), "duplicate name generated");
            check(name.matches("WhatsApp_Call_[0-9]{8}_[0-9]{6}_[0-9a-f]{32}\\.m4a"),
                    "invalid generated filename");
        }

        File saved = RecordingOutputFiles.reserve(root, firstName);
        check(saved != null && saved.isFile(), "initial reservation failed");
        byte[] original = "previous completed recording".getBytes(StandardCharsets.UTF_8);
        Files.write(saved.toPath(), original);
        check(RecordingOutputFiles.reserve(root, firstName) == null, "collision was accepted");
        check(java.util.Arrays.equals(original, Files.readAllBytes(saved.toPath())),
                "collision changed the previous recording");
        check(RecordingOutputFiles.reserve(root, secondName) != null,
                "another same-second recording could not reserve its own file");

        for (String invalid : new String[] {"", ".", ".."}) {
            check(RecordingOutputFiles.reserve(root, invalid) == null,
                    "unsafe or empty filename was accepted: " + invalid);
        }
        check(RecordingOutputFiles.reserve(root, null) == null, "null filename was accepted");
        String disguised = RecordingOutputFiles.safeName("../outside.m4a");
        check(disguised != null && !disguised.contains("/") && !disguised.contains("\\"),
                "path separators escaped validation");
        check(RecordingOutputFiles.reserve(root, disguised).getParentFile().equals(root),
                "reservation escaped its directory");

        File notDirectory = new File(root, "not-a-directory");
        Files.write(notDirectory.toPath(), "do not modify".getBytes(StandardCharsets.UTF_8));
        check(RecordingOutputFiles.reserve(notDirectory, "attempt.m4a") == null,
                "non-directory destination was accepted");
        check("do not modify".equals(new String(Files.readAllBytes(notDirectory.toPath()),
                StandardCharsets.UTF_8)),
                "non-directory contents changed");

        final int workers = 20;
        final ExecutorService executor = Executors.newFixedThreadPool(workers);
        final CountDownLatch ready = new CountDownLatch(workers);
        final CountDownLatch start = new CountDownLatch(1);
        List<Future<File>> reservations = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                reservations.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start gate timeout");
                    return RecordingOutputFiles.reserve(root, "parallel.m4a");
                }));
            }
            check(ready.await(10, TimeUnit.SECONDS), "workers did not reach the start gate");
            start.countDown();
            int winners = 0;
            for (Future<File> reservation : reservations) {
                if (reservation.get(10, TimeUnit.SECONDS) != null) winners++;
            }
            check(winners == 1, "exactly one parallel reservation must succeed; got " + winners);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        System.out.println("PASS " + assertions + " assertions: unique names, exact-once reservation,"
                + " preservation of prior bytes, path validation, failure handling, parallel collision");
        System.out.println("BOUNDARY: local JVM filesystem; no Android ContentProvider,"
                + " LSPosed, MediaRecorder, storage permission, or captured audio");
    }
}
