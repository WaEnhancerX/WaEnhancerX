package com.waenhancer.xposed.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Production runner with unprivileged child processes and controllable startup/stream faults. */
public final class TestRootCommandDeadline {
    interface Checked { void run() throws Exception; }
    private static int tests, failures;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void test(String name, Checked body) {
        tests++;
        try { body.run(); System.out.println("PASS " + name); }
        catch (AssertionError error) { failures++; System.out.println("FAIL " + name + ": " + error.getMessage()); }
        catch (Exception error) { throw new IllegalStateException("Fixture failed: " + name, error); }
    }
    private static void ignoreInterrupts(CountDownLatch latch) {
        boolean done = false;
        while (!done) {
            try { done = latch.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}
        }
    }
    private static class StubProcess extends Process {
        final AtomicBoolean destroyed = new AtomicBoolean();
        final InputStream input;
        final long completesAt;
        StubProcess(InputStream input, long durationMs) {
            this.input = input;
            completesAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMs);
        }
        StubProcess(String output, long durationMs) {
            this(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)), durationMs);
        }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public int waitFor() throws InterruptedException {
            while (!destroyed.get() && System.nanoTime() < completesAt) Thread.sleep(1);
            return 0;
        }
        @Override public int exitValue() {
            if (!destroyed.get() && System.nanoTime() < completesAt) throw new IllegalThreadStateException();
            return 0;
        }
        @Override public void destroy() { destroyed.set(true); }
    }
    private static void waitForDestroyed(StubProcess process) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!process.destroyed.get() && System.nanoTime() < end) Thread.sleep(5);
        check(process.destroyed.get(), "late process was not destroyed");
    }
    private static void lateStartup(boolean interruptCaller) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        StubProcess process = new StubProcess("late", 0);
        AtomicBoolean returnedNull = new AtomicBoolean(), keptInterrupt = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            String result = RootCommandRunner.run("late-start", interruptCaller ? 2000 : 120, command -> {
                entered.countDown(); ignoreInterrupts(release); return process;
            });
            returnedNull.set(result == null);
            keptInterrupt.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        try {
            check(entered.await(2, TimeUnit.SECONDS), "starter never entered");
            if (interruptCaller) caller.interrupt();
            caller.join(700);
            check(!caller.isAlive(), "caller remained blocked in startup");
            check(returnedNull.get(), "abandoned startup reported success");
            if (interruptCaller) check(keptInterrupt.get(), "caller interruption was lost");
        } finally { release.countDown(); caller.join(2500); }
        waitForDestroyed(process);
    }
    public static void main(String[] args) throws Exception {
        test("startup shares caller timeout", () -> lateStartup(false));
        test("interrupt abandons and cleans late startup", () -> lateStartup(true));
        test("startup consumes process budget", () -> {
            long began = System.nanoTime();
            String result = RootCommandRunner.run("shared-budget", 190, command -> {
                try { Thread.sleep(130); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new StubProcess("too-late", 150);
            });
            check(result == null, "startup and execution received separate budgets");
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 600, "deadline did not bound caller");
        });
        test("preinterrupted caller cannot spawn", () -> {
            AtomicInteger starts = new AtomicInteger();
            Thread.currentThread().interrupt();
            try {
                check(RootCommandRunner.run("no-start", 1000, command -> {
                    starts.incrementAndGet(); return new StubProcess("", 0);
                }) == null, "interrupted call reported success");
                check(starts.get() == 0, "spawned after cancellation");
                check(Thread.currentThread().isInterrupted(), "interrupt flag lost");
            } finally { Thread.interrupted(); }
        });
        test("stream failure cannot acknowledge command", () -> {
            InputStream broken = new InputStream() { @Override public int read() throws IOException { throw new IOException("fault"); } };
            check(RootCommandRunner.run("broken-read", 1000, command -> new StubProcess(broken, 0)) == null,
                    "stream exception became successful empty output");
        });
        test("unchecked stream failure cannot acknowledge command", () -> {
            InputStream broken = new InputStream() { @Override public int read() { throw new IllegalStateException("fault"); } };
            check(RootCommandRunner.run("unchecked-read", 1000, command -> new StubProcess(broken, 0)) == null,
                    "unchecked stream exception became successful empty output");
        });
        test("stream close failure cannot acknowledge command", () -> {
            InputStream broken = new ByteArrayInputStream("partial".getBytes(StandardCharsets.UTF_8)) {
                @Override public void close() throws IOException { throw new IOException("close fault"); }
            };
            check(RootCommandRunner.run("broken-close", 1000, command -> new StubProcess(broken, 0)) == null,
                    "failed stream close acknowledged its output");
        });
        test("unfinished drain cannot acknowledge command", () -> {
            CountDownLatch release = new CountDownLatch(1);
            InputStream blocked = new InputStream() {
                @Override public int read() { ignoreInterrupts(release); return -1; }
            };
            try {
                check(RootCommandRunner.run("unfinished-read", 120, command -> new StubProcess(blocked, 0)) == null,
                        "unfinished stdout was promoted to success");
            } finally { release.countDown(); }
        });
        test("bounded startup capacity and expired queue", () -> {
            CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
            AtomicInteger starts = new AtomicInteger(), expiredStarts = new AtomicInteger();
            List<StubProcess> processes = java.util.Collections.synchronizedList(new ArrayList<>());
            List<Thread> callers = new ArrayList<>();
            try {
                for (int i=0; i<2; i++) {
                    Thread caller = new Thread(() -> RootCommandRunner.run("occupied", 180, command -> {
                        starts.incrementAndGet(); entered.countDown(); ignoreInterrupts(release);
                        StubProcess process = new StubProcess("",0); processes.add(process); return process;
                    })); callers.add(caller); caller.start();
                }
                check(entered.await(2, TimeUnit.SECONDS), "startup slots not occupied");
                for (int i=0; i<20; i++) {
                    RootCommandRunner.run("expired-queued", 15, command -> {
                        expiredStarts.incrementAndGet(); return new StubProcess("",0);
                    });
                }
                check(expiredStarts.get() == 0, "unbounded startup workers ran queued work");
            } finally {
                release.countDown();
                for (Thread caller: callers) caller.join(2500);
            }
            Thread.sleep(60);
            check(starts.get() == 2 && expiredStarts.get() == 0, "expired queued command started later");
            for (StubProcess process: processes) waitForDestroyed(process);
            check("recovered".equals(RootCommandRunner.run("recovered", 1000, command -> new StubProcess("recovered",0))),
                    "released startup capacity was not reused");
        });
        test("bounded capture refuses truncation", () -> {
            check(RootCommandRunner.runBounded("overflow", 1000, 10, command -> new StubProcess("12345678901",0)) == null,
                    "complete capture returned a truncated document");
        });
        test("ordinary capture drains oversized output", () -> {
            String output = RootCommandRunner.run("large",1000,command -> new StubProcess("x".repeat(250000),0));
            check(output != null && output.length()==8192,"ordinary bounded capture changed");
        });
        test("fatal startup error propagates unchanged", () -> {
            Error sentinel = new Error("synthetic fatal startup");
            try { RootCommandRunner.run("fatal",1000,command -> { throw sentinel; }); throw new AssertionError("fatal error swallowed"); }
            catch (Error observed) { check(observed==sentinel,"fatal identity changed"); }
        });
        test("unprivileged real child still captures output", () -> {
            String output=RootCommandRunner.run("printf ready",2000,command -> new ProcessBuilder("sh","-c",command).start());
            check("ready".equals(output),"real child output lost");
        });
        System.out.println("RESULT tests="+tests+" failures="+failures);
        if (failures!=0) System.exit(1);
    }
}
