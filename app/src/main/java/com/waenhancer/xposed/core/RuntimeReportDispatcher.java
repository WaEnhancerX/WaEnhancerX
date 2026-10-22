package com.waenhancer.xposed.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Serial, bounded delivery of optional injection diagnostics. A slow Binder call owns at most
 * one worker; each kind retains only its newest pending task. Hook installation and preference
 * writes must never use this lossy queue.
 */
public final class RuntimeReportDispatcher {
    public enum Kind { PACKAGE, HEALTH, HOOKS, UPDATE_CHECK }

    private final Executor executor;
    private final Consumer<Throwable> failureReporter;
    private final Map<Kind, Runnable> pending = new LinkedHashMap<>();
    private boolean draining;

    public RuntimeReportDispatcher(Consumer<Throwable> failureReporter) {
        this(Executors.newSingleThreadExecutor(task -> {
            Thread worker = new Thread(task, "WAEX-RuntimeReports");
            worker.setDaemon(true);
            return worker;
        }), failureReporter);
    }

    RuntimeReportDispatcher(Executor executor, Consumer<Throwable> failureReporter) {
        this.executor = Objects.requireNonNull(executor);
        this.failureReporter = Objects.requireNonNull(failureReporter);
    }

    public void submitLatest(Kind kind, Runnable task) {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(task);
        synchronized (pending) {
            // Replacing an observation also updates its position. A final READY report must
            // not overtake the hook inventory queued since an older INITIALIZING report.
            pending.remove(kind);
            pending.put(kind, task);
            if (draining) return;
            draining = true;
            try {
                // Only one drain is submitted, regardless of the number of heartbeat updates.
                executor.execute(this::drain);
            } catch (RuntimeException rejected) {
                draining = false;
                pending.clear();
                reportFailure(rejected);
            }
        }
    }

    private void drain() {
        boolean completed = false;
        try {
            while (true) {
                Runnable task;
                synchronized (pending) {
                    if (pending.isEmpty()) {
                        draining = false;
                        completed = true;
                        return;
                    }
                    Kind first = pending.keySet().iterator().next();
                    task = pending.remove(first);
                }
                try {
                    task.run();
                } catch (Throwable failure) {
                    if (failure instanceof VirtualMachineError) throw (VirtualMachineError) failure;
                    if (failure instanceof ThreadDeath) throw (ThreadDeath) failure;
                    reportFailure(failure);
                }
            }
        } finally {
            // Also release scheduling ownership if the VM aborts a worker.
            if (!completed) {
                synchronized (pending) { draining = false; }
            }
        }
    }

    private void reportFailure(Throwable failure) {
        try {
            failureReporter.accept(failure);
        } catch (Throwable unavailable) {
            if (unavailable instanceof VirtualMachineError) throw (VirtualMachineError) unavailable;
            if (unavailable instanceof ThreadDeath) throw (ThreadDeath) unavailable;
            // An optional diagnostic sink must not disable later reports or host hooks.
        }
    }
}
