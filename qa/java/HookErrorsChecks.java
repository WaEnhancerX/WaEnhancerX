package com.waenhancer.xposed.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the actual logger against synthetic private payloads and a controlled sink. */
public final class HookErrorsChecks {
    private static int checks;
    private static int failures;

    private static void check(String name, boolean passed) {
        checks++;
        System.out.println((passed ? "PASS|" : "ASSERTION|") + name);
        if (!passed) failures++;
    }

    private static final class Hostile extends RuntimeException {
        static final AtomicInteger calls = new AtomicInteger();

        Hostile() {
            super("SYNTHETIC_MESSAGE_MARKER");
        }

        @Override
        public String toString() {
            calls.incrementAndGet();
            throw new IllegalStateException("SYNTHETIC_TOSTRING_MARKER");
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        HookErrors.resetForTest(lines::add);
        RuntimeException error = new IllegalArgumentException("SYNTHETIC_PRIVATE_MARKER");
        error.setStackTrace(new StackTraceElement[]{new StackTraceElement(
                "SYNTHETIC_FRAME_MARKER", "m", "SYNTHETIC_FILE_MARKER", 7)});
        for (int n = 0; n < 10; n++) HookErrors.swallowed("alpha", error);
        check("counts unchanged", HookErrors.count("alpha", IllegalArgumentException.class) == 10);
        check("rate limit", lines.size() == 3);
        check("type retained", lines.stream().allMatch(s -> s.contains("java.lang.IllegalArgumentException")));
        check("message redacted", lines.stream().noneMatch(s -> s.contains("SYNTHETIC_PRIVATE_MARKER")));
        check("stack text redacted", lines.stream().noneMatch(s ->
                s.contains("SYNTHETIC_FRAME_MARKER") || s.contains("SYNTHETIC_FILE_MARKER")));
        check("limit notice", lines.get(2).contains("further repeats"));

        HookErrors.swallowed("beta", new Hostile());
        check("never invokes throwable.toString", Hostile.calls.get() == 0);
        check("hostile throwable still classified", lines.size() == 4 && lines.get(3).contains("Hostile"));
        check("separate site counts", HookErrors.count("beta", Hostile.class) == 1);

        HookErrors.resetForTest(lines::add);
        lines.clear();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        for (int n = 0; n < 120; n++) {
            pool.submit(() -> HookErrors.swallowed("parallel",
                    new IllegalStateException("SYNTHETIC_PARALLEL_MARKER")));
        }
        pool.shutdown();
        check("workers completed", pool.awaitTermination(10, TimeUnit.SECONDS));
        check("concurrent counts", HookErrors.total() == 120);
        check("concurrent log cap", lines.size() == 3);
        check("parallel payload redacted", lines.stream().noneMatch(s -> s.contains("SYNTHETIC_PARALLEL_MARKER")));

        HookErrors.resetForTest(s -> { throw new IllegalStateException(); });
        HookErrors.swallowed("sink", new RuntimeException());
        check("throwing sink does not crash or lose count", HookErrors.total() == 1);

        HookErrors.resetForTest(lines::add);
        lines.clear();
        AtomicInteger built = new AtomicInteger();
        for (int i = 0; i < 9; i++) HookErrors.note("note", "kind", () -> "safe " + built.incrementAndGet());
        check("note laziness preserved", built.get() == 3 && lines.size() == 3);
        check("note excluded from failure counts", HookErrors.total() == 0);
        System.out.println("RESULT|hook_errors|" + checks + "|" + failures);
        if (failures != 0) throw new AssertionError("behavioral assertions failed");
    }
}
