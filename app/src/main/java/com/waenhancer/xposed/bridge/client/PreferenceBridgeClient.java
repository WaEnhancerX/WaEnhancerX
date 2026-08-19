package com.waenhancer.xposed.bridge.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.robv.android.xposed.XposedBridge;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance, fallback-resilient Preference Bridge for injected processes.
 * Combines direct XSharedPreferences disk reads with ContentProvider IPC fallback and real-time ContentObserver updates.
 */
public class PreferenceBridgeClient implements SharedPreferences {

    public static final String AUTHORITY = "com.waenhancer.hookprovider";
    public static final Uri PREFS_URI = Uri.parse("content://" + AUTHORITY + "/preferences");


    private final Context context;
    private final SharedPreferences diskPrefs;
    private final Map<String, Object> memoryCache = new ConcurrentHashMap<>();

    public PreferenceBridgeClient(@NonNull Context context, @Nullable SharedPreferences diskPrefs) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        this.diskPrefs = diskPrefs;

        // 1. Initial hydration from disk and provider
        syncPreferences();

        // 2. Register observer for real-time live preference updates without restart
        registerObserver();
    }

    public synchronized void syncPreferences() {
        // Provider IPC read (bypasses SELinux file restrictions on modern Android)
        boolean syncedFromProvider = false;
        try {
            Bundle bundle = context.getContentResolver().call(
                    Uri.parse("content://" + AUTHORITY),
                    "get_all_preferences",
                    null,
                    null
            );
            if (bundle != null) {
                @SuppressWarnings("unchecked")
                HashMap<String, Object> map = (HashMap<String, Object>) bundle.getSerializable("prefs");
                if (map != null) {
                    memoryCache.clear();
                    memoryCache.putAll(map);
                    syncedFromProvider = true;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] PreferenceBridgeClient: Provider IPC query failed: " + t.getMessage());
        }

        // Disk read fallback if provider query was unavailable
        if (!syncedFromProvider && diskPrefs != null) {
            try {
                Map<String, ?> all = diskPrefs.getAll();
                if (all != null) {
                    memoryCache.clear();
                    memoryCache.putAll(all);
                }
            } catch (Throwable ignored) {}
        }
    }

    private void registerObserver() {
        try {
            context.getContentResolver().registerContentObserver(
                    PREFS_URI,
                    true,
                    new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            syncPreferences();
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] PreferenceBridgeClient: ContentObserver registration failed: " + t.getMessage());
        }
    }

    @Override
    public Map<String, ?> getAll() {
        return new HashMap<>(memoryCache);
    }

    @Nullable
    @Override
    public String getString(String key, @Nullable String defValue) {
        Object val = memoryCache.get(key);
        return val instanceof String ? (String) val : defValue;
    }

    @Nullable
    @Override
    public Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
        Object val = memoryCache.get(key);
        return val instanceof Set ? (Set<String>) val : defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        Object val = memoryCache.get(key);
        if (val instanceof Integer) return (Integer) val;
        if (val instanceof String) {
            try { return Integer.parseInt((String) val); } catch (Throwable ignored) {}
        }
        return defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        Object val = memoryCache.get(key);
        if (val instanceof Long) return (Long) val;
        if (val instanceof String) {
            try { return Long.parseLong((String) val); } catch (Throwable ignored) {}
        }
        return defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        Object val = memoryCache.get(key);
        if (val instanceof Float) return (Float) val;
        if (val instanceof String) {
            try { return Float.parseFloat((String) val); } catch (Throwable ignored) {}
        }
        return defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        Object val = memoryCache.get(key);
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof String) return Boolean.parseBoolean((String) val);
        return defValue;
    }

    @Override
    public boolean contains(String key) {
        return memoryCache.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new BridgeEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

    private class BridgeEditor implements Editor {
        private final Map<String, Object> values = new HashMap<>();

        @Override
        public Editor putString(String key, @Nullable String value) { values.put(key, value); return this; }
        @Override
        public Editor putStringSet(String key, @Nullable Set<String> values) { return this; }
        @Override
        public Editor putInt(String key, int value) { values.put(key, value); return this; }
        @Override
        public Editor putLong(String key, long value) { values.put(key, value); return this; }
        @Override
        public Editor putFloat(String key, float value) { values.put(key, value); return this; }
        @Override
        public Editor putBoolean(String key, boolean value) { values.put(key, value); return this; }
        @Override
        public Editor remove(String key) { values.remove(key); return this; }
        @Override
        public Editor clear() { values.clear(); return this; }
        @Override
        public boolean commit() { apply(); return true; }

        @Override
        public void apply() {
            memoryCache.putAll(values);
        }
    }
}
