package com.waenhancer.xposed.utils;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import de.robv.android.xposed.XposedBridge;

/**
 * Records failures that a hook deliberately survives, so a feature that stops working leaves a
 * trace instead of failing silently. Each (site, exception type) pair is logged at most
 * {@link #MAX_LOGGED_PER_SITE} times with its exception class, never its message or stack text.
 * Later occurrences are only counted, so a failure in a hot path (a view bind, a message hook)
 * cannot flood the LSPosed log.
 */
public final class HookErrors {
    static final int MAX_LOGGED_PER_SITE = 3;

    private static final Map<Key, AtomicInteger> COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> NOTES = new ConcurrentHashMap<>();
    private static volatile Consumer<String> sink = XposedBridge::log;

    private HookErrors() {}

    /**
     * Call from a catch block that intentionally continues. {@code site} is the hook's name.
     * Never throws: it runs inside catch blocks, so a logging failure must not become a crash.
     */
    public static void swallowed(@NonNull String site, @NonNull Throwable failure) {
        try {
            int seen = COUNTS.computeIfAbsent(new Key(site, failure.getClass()), k -> new AtomicInteger())
                    .incrementAndGet();
            if (seen > MAX_LOGGED_PER_SITE) return;
            String more = seen == MAX_LOGGED_PER_SITE ? " (further repeats are counted, not logged)" : "";
            sink.accept("[WAEX][swallowed] " + site + ": " + failure.getClass().getName() + more);
        } catch (Throwable loggingFailed) {
            // Nothing safe left to do; the original failure is already being intentionally ignored.
        }
    }

    /**
     * Rate-limited diagnostic line for state that is wrong but throws nothing (for example a view
     * that ended up hidden). The message is only built while the site is under its log limit, so
     * callers can pass an expensive description without paying for it on hot paths.
     */
    public static void note(@NonNull String site, @NonNull String key, @NonNull Supplier<String> message) {
        try {
            int seen = NOTES.computeIfAbsent(site + '|' + key, ignored -> new AtomicInteger())
                    .incrementAndGet();
            if (seen > MAX_LOGGED_PER_SITE) return;
            String more = seen == MAX_LOGGED_PER_SITE ? " (further repeats are counted, not logged)" : "";
            sink.accept("[WAEX][note] " + site + " " + key + ": " + message.get() + more);
        } catch (Throwable loggingFailed) {
            // A diagnostic must never affect the hook it describes.
        }
    }

    /** How many times {@code site} swallowed an exception of this type. */
    public static int count(@NonNull String site, @NonNull Class<? extends Throwable> type) {
        AtomicInteger value = COUNTS.get(new Key(site, type));
        return value == null ? 0 : value.get();
    }

    /** Total swallowed failures across every site since the process started. */
    public static int total() {
        int sum = 0;
        for (AtomicInteger value : COUNTS.values()) sum += value.get();
        return sum;
    }

    @VisibleForTesting
    static void resetForTest(@NonNull Consumer<String> testSink) {
        COUNTS.clear();
        NOTES.clear();
        sink = testSink;
    }

    private static final class Key {
        private final String site;
        private final Class<?> type;

        Key(String site, Class<?> type) {
            this.site = site;
            this.type = type;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key that = (Key) other;
            return site.equals(that.site) && type == that.type;
        }

        @Override public int hashCode() {
            return 31 * site.hashCode() + type.hashCode();
        }
    }
}
