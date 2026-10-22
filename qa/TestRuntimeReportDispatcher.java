package com.waenhancer.xposed.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.waenhancer.xposed.core.RuntimeReportDispatcher.Kind;

/** Executes the complete production queue; Executor/Binder work is an explicit substitute. */
public final class TestRuntimeReportDispatcher {
    static int checks;
    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    static final class ManualExecutor implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        int submissions;
        boolean reject;
        public void execute(Runnable task) {
            submissions++;
            if (reject) throw new RejectedExecutionException();
            tasks.add(task);
        }
        void run() { tasks.remove().run(); }
    }
    public static void main(String[] args) throws Exception {
        ManualExecutor executor = new ManualExecutor();
        List<String> delivered = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        RuntimeReportDispatcher queue = new RuntimeReportDispatcher(executor, errors::add);
        queue.submitLatest(Kind.PACKAGE, () -> delivered.add("package"));
        queue.submitLatest(Kind.HEALTH, () -> delivered.add("initializing"));
        queue.submitLatest(Kind.HOOKS, () -> delivered.add("hooks"));
        for (int i = 0; i < 100_000; i++) {
            int observation = i;
            queue.submitLatest(Kind.HEALTH, () -> delivered.add("ready:" + observation));
        }
        queue.submitLatest(Kind.UPDATE_CHECK, () -> delivered.add("update"));
        check(delivered.isEmpty(), "submission performed I/O inline");
        check(executor.submissions == 1 && executor.tasks.size() == 1,
                "heartbeats accumulated executor tasks");
        executor.run();
        check(delivered.equals(List.of("package", "hooks", "ready:99999", "update")),
                "latest state must retain causal order after hook inventory: " + delivered);
        check(errors.isEmpty(), "normal delivery emitted a failure");

        queue.submitLatest(Kind.PACKAGE, () -> { throw new IllegalStateException("synthetic"); });
        queue.submitLatest(Kind.HEALTH, () -> delivered.add("recovered"));
        executor.run();
        check(errors.size() == 1 && errors.get(0) instanceof IllegalStateException,
                "delivery failure not isolated");
        check(delivered.get(delivered.size() - 1).equals("recovered"), "failure lost next report");

        executor.reject = true;
        queue.submitLatest(Kind.HEALTH, () -> delivered.add("rejected"));
        check(errors.size() == 2, "rejected worker was not surfaced");
        executor.reject = false;
        queue.submitLatest(Kind.HOOKS, () -> delivered.add("after-reject"));
        executor.run();
        check(!delivered.contains("rejected") && delivered.contains("after-reject"),
                "rejection poisoned future delivery");

        ManualExecutor sinkExecutor = new ManualExecutor();
        RuntimeReportDispatcher badSink = new RuntimeReportDispatcher(sinkExecutor,
                failure -> { throw new IllegalStateException(); });
        badSink.submitLatest(Kind.PACKAGE, () -> { throw new LinkageError(); });
        badSink.submitLatest(Kind.HEALTH, () -> delivered.add("after-sink-failure"));
        sinkExecutor.run();
        check(delivered.contains("after-sink-failure"), "diagnostic sink stopped the queue");

        RuntimeReportDispatcher missingSink = new RuntimeReportDispatcher(sinkExecutor,
                failure -> { throw new NoClassDefFoundError("synthetic logger linkage"); });
        missingSink.submitLatest(Kind.PACKAGE, () -> { throw new IllegalStateException(); });
        missingSink.submitLatest(Kind.HEALTH, () -> delivered.add("after-linkage-sink-failure"));
        sinkExecutor.run();
        check(delivered.contains("after-linkage-sink-failure"),
                "unavailable logger stranded an already pending report");

        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger actualRuns = new AtomicInteger();
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicInteger failures = new AtomicInteger();
        RuntimeReportDispatcher blocked = new RuntimeReportDispatcher(f -> failures.incrementAndGet());
        try {
            blocked.submitLatest(Kind.PACKAGE, () -> {
                worker.set(Thread.currentThread()); entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            });
            check(entered.await(3, TimeUnit.SECONDS), "worker never reached blocked IPC");
            for (int i = 0; i < 100_000; i++)
                blocked.submitLatest(Kind.HEALTH, actualRuns::incrementAndGet);
            blocked.submitLatest(Kind.HOOKS, finished::countDown);
            check(actualRuns.get() == 0, "blocked Binder spawned replacement workers");
            check(worker.get() != Thread.currentThread() && worker.get().isDaemon(),
                    "optional I/O ran on caller or retained process lifetime");
            release.countDown();
            check(finished.await(3, TimeUnit.SECONDS), "queue failed to resume");
            check(actualRuns.get() == 1 && failures.get() == 0, "blocked backlog was not coalesced");
        } finally { release.countDown(); }
        System.out.println("PASS: " + checks + " runtime queue checks, 200000 replaced heartbeat tasks");
    }
}
