package com.waenhancer.config;

import android.content.Context;
import java.util.Set;

/** Legacy-cache double retained so the old reader can compile for negative controls. */
public final class PreferenceStores {
    private PreferenceStores() {}
    public static FakeStore publicStore(Context ignored) { return new FakeStore(); }
    public static final class FakeStore {
        public Set<String> getStringSet(String key, Set<String> fallback) { return fallback; }
        public FakeStore edit() { return this; }
        public FakeStore putStringSet(String key, Set<String> value) { return this; }
        public void apply() {}
    }
}
