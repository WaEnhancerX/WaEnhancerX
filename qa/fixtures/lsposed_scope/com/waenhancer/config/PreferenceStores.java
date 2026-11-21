package com.waenhancer.config;

import android.content.Context;
import java.util.Set;

/** Legacy-cache double retained so the old reader can compile for negative controls. */
public final class PreferenceStores {
    public static int calls;
    private static Set<String> packages = Set.of();
    private static long timestamp;
    private PreferenceStores() {}
    public static void reset() { calls = 0; packages = Set.of(); timestamp = 0; }
    public static void seedLegacyScope(Set<String> value) { packages = Set.copyOf(value); timestamp = 1; }
    public static FakeStore publicStore(Context ignored) { calls++; return new FakeStore(); }
    public static final class FakeStore {
        public Set<String> getStringSet(String key, Set<String> fallback) {
            if (!"cached_lsposed_scope".equals(key)) throw new AssertionError("Unexpected preference key");
            return packages.isEmpty() ? fallback : packages;
        }
        public long getLong(String key, long fallback) {
            if (!"cached_lsposed_scope_time".equals(key)) throw new AssertionError("Unexpected preference key");
            return timestamp == 0 ? fallback : timestamp;
        }
        public FakeStore edit() { return this; }
        public FakeStore putStringSet(String key, Set<String> value) {
            if (!"cached_lsposed_scope".equals(key)) throw new AssertionError("Unexpected preference key");
            packages = Set.copyOf(value); return this;
        }
        public FakeStore putLong(String key, long value) {
            if (!"cached_lsposed_scope_time".equals(key)) throw new AssertionError("Unexpected preference key");
            timestamp = value; return this;
        }
        public void apply() {}
    }
}
