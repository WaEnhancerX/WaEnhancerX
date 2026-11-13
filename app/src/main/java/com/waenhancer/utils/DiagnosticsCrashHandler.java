package com.waenhancer.utils;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * A testable, fail-open wrapper for the manager's existing fatal exception handler.
 * The original handler always receives the original Thread and Throwable exactly once.
 * Never install this in the WhatsApp host process.
 */
public final class DiagnosticsCrashHandler implements Thread.UncaughtExceptionHandler {
    @FunctionalInterface
    public interface CrashSink {
        void record(Throwable failure) throws Exception;
    }

    private final BooleanSupplier enabled;
    private final CrashSink sink;
    private final Thread.UncaughtExceptionHandler next;

    public DiagnosticsCrashHandler(BooleanSupplier enabled, CrashSink sink,
                                   Thread.UncaughtExceptionHandler next) {
        this.enabled = Objects.requireNonNull(enabled);
        this.sink = Objects.requireNonNull(sink);
        this.next = Objects.requireNonNull(next);
    }

    @Override
    public void uncaughtException(Thread thread, Throwable failure) {
        try {
            if (enabled.getAsBoolean()) sink.record(failure);
        } catch (Throwable ignored) {
            // Reporting is best-effort; never hide the fatal exception.
        } finally {
            next.uncaughtException(thread, failure);
        }
    }
}
