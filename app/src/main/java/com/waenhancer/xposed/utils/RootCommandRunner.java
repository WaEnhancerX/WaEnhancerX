package com.waenhancer.xposed.utils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs root shell commands with bounded wait and captured output (but always drains stdout). */
public final class RootCommandRunner {
    /** Shared bounded root execution for the manager and injected utility callers. */
    public static String run(String command, long timeoutMillis) {
        return run(command, timeoutMillis,
                value -> new ProcessBuilder("su", "-c", value).redirectErrorStream(true).start());
    }

    private static final int MAX_CAPTURE_CHARS = 8192;
    private static final int READ_CHUNK = 1024;
    private static final ThreadPoolExecutor STARTERS = new ThreadPoolExecutor(
            2, 2, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> {
                Thread thread = new Thread(task, "WAEX-root-start");
                thread.setDaemon(true);
                return thread;
            });
    static { STARTERS.allowCoreThreadTimeOut(true); }

    /** Config readers require the complete output, never a silently truncated document. */
    public static String runBounded(String command, long timeoutMillis, int captureLimit) {
        return runBounded(command, timeoutMillis, captureLimit,
                value -> new ProcessBuilder("su", "-c", value).redirectErrorStream(true).start());
    }

    static String runBounded(String command, long timeoutMillis, int captureLimit, Starter starter) {
        if (captureLimit < 1 || captureLimit > 1024 * 1024) return null;
        return run(command, timeoutMillis, captureLimit, true, starter);
    }

    @FunctionalInterface
    interface Starter { Process start(String command) throws IOException; }

    private RootCommandRunner() {}

    /** Own a process until the waiting caller claims it, including late starts. */
    private static final class StartAttempt implements Runnable {
        final String command;
        final Starter starter;
        final CountDownLatch finished = new CountDownLatch(1);
        private Process process;
        private Throwable failure;
        private boolean abandoned;

        StartAttempt(String command, Starter starter) {
            this.command = command;
            this.starter = starter;
        }

        @Override public void run() {
            try {
                synchronized (this) { if (abandoned) return; }
                Process started = starter.start(command);
                synchronized (this) {
                    if (!abandoned) {
                        process = started;
                        return;
                    }
                }
                if (started != null) terminate(started);
            } catch (IOException | RuntimeException | Error error) {
                synchronized (this) { failure = error; }
            } finally {
                finished.countDown();
            }
        }

        synchronized Process claim() {
            if (failure instanceof Error) throw (Error) failure;
            Process result = process;
            process = null;
            return result;
        }

        void abandon() {
            Process orphan;
            synchronized (this) {
                abandoned = true;
                orphan = process;
                process = null;
            }
            if (orphan != null) terminate(orphan);
        }
    }

    /** A null result means startup, exit, timeout or stream processing failed. Empty output can be success. */
    static String run(String command, long timeoutMillis, Starter starter) {
        return run(command, timeoutMillis, MAX_CAPTURE_CHARS, false, starter);
    }

    private static String run(String command, long timeoutMillis, int captureLimit,
                              boolean requireComplete, Starter starter) {
        if (command == null || command.trim().isEmpty() || timeoutMillis <= 0 || starter == null) return null;
        if (Thread.currentThread().isInterrupted()) return null;
        final long startedAt = System.nanoTime();
        final long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        StartAttempt attempt = new StartAttempt(command, starter);
        Future<?> startup = null;
        Process process = null;
        Thread drain = null;
        StringBuilder output = new StringBuilder(Math.min(captureLimit, MAX_CAPTURE_CHARS));
        AtomicBoolean truncated = new AtomicBoolean();
        AtomicBoolean readComplete = new AtomicBoolean();
        try {
            startup = STARTERS.submit(attempt);
            long remainingNanos = timeoutNanos - (System.nanoTime() - startedAt);
            if (remainingNanos <= 0 || !attempt.finished.await(remainingNanos, TimeUnit.NANOSECONDS)) return null;
            process = attempt.claim();
            if (process == null || System.nanoTime() - startedAt >= timeoutNanos) return null;
            final Process currentProcess = process;
            drain = new Thread(() -> {
                try {
                    try (Reader reader = new InputStreamReader(
                            currentProcess.getInputStream(), StandardCharsets.UTF_8)) {
                        char[] chunk = new char[READ_CHUNK];
                        int count;
                        while ((count = reader.read(chunk)) != -1) {
                            synchronized (output) {
                                int room = captureLimit - output.length();
                                if (count > room) truncated.set(true);
                                if (room > 0) output.append(chunk, 0, Math.min(room, count));
                            }
                            // Keep draining even after the capture is full so the process
                            // never blocks on a full OS pipe.
                        }
                    }
                    // Only EOF and a successful close can acknowledge a complete read.
                    readComplete.set(true);
                } catch (IOException | RuntimeException ignored) {
                    // Process termination can close the stream while it is being read.
                }
            }, "WAEX-root-stdout");
            drain.setDaemon(true);
            drain.start();
            // Poll exitValue rather than using Process.waitFor(timeout, unit),
            // which is not reliably available on the application's minSdk 24.
            boolean completed = false;
            while (System.nanoTime() - startedAt < timeoutNanos) {
                try {
                    process.exitValue();
                    completed = true;
                    break;
                } catch (IllegalThreadStateException stillRunning) {
                    remainingNanos = timeoutNanos - (System.nanoTime() - startedAt);
                    if (remainingNanos <= 0) break;
                    long sleepMillis = Math.min(25L,
                            Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                    Thread.sleep(sleepMillis);
                }
            }
            if (!completed) {
                terminate(process);
                return null;
            }
            remainingNanos = timeoutNanos - (System.nanoTime() - startedAt);
            if (drain.isAlive() && remainingNanos > 0) {
                drain.join(Math.min(250L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))));
            }
            if (process.exitValue() != 0) return null;
            if (drain.isAlive() || !readComplete.get() || requireComplete && truncated.get()) return null;
            synchronized (output) { return output.toString().trim(); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException failure) {
            return null;
        } finally {
            attempt.abandon();
            if (startup != null) {
                startup.cancel(true);
                if (startup instanceof Runnable) STARTERS.remove((Runnable) startup);
            }
            if (process != null) terminate(process);
            if (drain != null && drain.isAlive()) drain.interrupt();
        }
    }

    private static void terminate(Process process) {
        try { process.destroy(); } catch (RuntimeException ignored) {}
        // Reflection preserves Android API 24 support where destroyForcibly()
        // may not be linked, while using it on runtimes that provide it.
        try {
            Process.class.getMethod("destroyForcibly").invoke(process);
        } catch (ReflectiveOperationException | SecurityException ignored) {}
    }
}
