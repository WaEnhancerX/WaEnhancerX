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
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe preference bridge for the hooked host process.
 *
 * <p>One initial IPC request hydrates the settings before feature installation.
 * Subsequent updates are read on a dedicated executor so provider I/O never
 * stalls WhatsApp's rendering thread. All snapshots are atomically swapped.
 * Editor operations are sent in a single atomic IPC transaction.</p>
 */
public final class PreferenceBridgeClient implements SharedPreferences {

    public static final String AUTHORITY = "com.waenhancer.hookprovider";
    public static final Uri PREFS_URI = Uri.parse("content://" + AUTHORITY + "/preferences");
    private static final Uri PROVIDER_URI = Uri.parse("content://" + AUTHORITY);
    private static final Object REMOVED = new Object();

    private final Context context;
    private final SharedPreferences diskPrefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<OnSharedPreferenceChangeListener> listeners = new CopyOnWriteArraySet<>();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "WAEX-Preferences");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger refreshGeneration = new AtomicInteger();
    private final AtomicBoolean refreshScheduled = new AtomicBoolean();
    private volatile Map<String, Object> memoryCache = Collections.emptyMap();

    public PreferenceBridgeClient(@NonNull Context context, @Nullable SharedPreferences diskPrefs) {
        Context app = context.getApplicationContext();
        this.context = app != null ? app : context;
        this.diskPrefs = diskPrefs;
        registerObserver();
        syncPreferences(); // The version guard must see the correct settings at startup.
    }

    public void syncPreferences() {
        Map<String, ?> newValues = null;
        try {
            Bundle bundle = context.getContentResolver().call(
                    PROVIDER_URI, "get_all_preferences", null, null);
            if (bundle != null) {
                Object data = bundle.getSerializable("prefs");
                if (data instanceof Map<?, ?>) {
                    Map<String, Object> validValues = new HashMap<>();
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) data).entrySet()) {
                        if (entry.getKey() instanceof String && entry.getValue() != null) {
                            validValues.put((String) entry.getKey(), entry.getValue());
                        }
                    }
                    newValues = validValues;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Preference provider unavailable: " + t.getMessage());
        }
        if (newValues == null && diskPrefs != null) {
            try {
                if (diskPrefs instanceof XSharedPreferences) {
                    ((XSharedPreferences) diskPrefs).reload();
                }
                newValues = diskPrefs.getAll();
            } catch (Throwable ignored) { }
        }
        if (newValues != null) publishSnapshot(newValues);
        // Keep the last known-good snapshot on transient IPC failures.
    }

    private void registerObserver() {
        try {
            context.getContentResolver().registerContentObserver(
                    PREFS_URI, true,
                    new ContentObserver(mainHandler) {
                        @Override
                        public void onChange(boolean selfChange) {
                            requestRefresh();
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Preference observer registration failed: " + t.getMessage());
        }
    }

    /** Coalesce bursts of changes without discarding a notification that arrives
     * during the provider read. */
    private void requestRefresh() {
        refreshGeneration.incrementAndGet();
        scheduleRefreshWorker();
    }

    private void scheduleRefreshWorker() {
        if (!refreshScheduled.compareAndSet(false, true)) return;
        ioExecutor.execute(() -> {
            int observed = refreshGeneration.get();
            try {
                do {
                    observed = refreshGeneration.get();
                    syncPreferences();
                } while (observed != refreshGeneration.get());
            } finally {
                refreshScheduled.set(false);
                if (refreshGeneration.get() != observed) scheduleRefreshWorker();
            }
        });
    }

    private synchronized void publishSnapshot(Map<String, ?> candidate) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : candidate.entrySet()) {
            Object value = entry.getValue();
            if (entry.getKey() == null || value == null) continue;
            if (value instanceof Set<?>) {
                copy.put(entry.getKey(), Collections.unmodifiableSet(new HashSet<>((Set<?>) value)));
            } else {
                copy.put(entry.getKey(), value);
            }
        }
        Map<String, Object> before = memoryCache;
        memoryCache = Collections.unmodifiableMap(copy); // Single visible, complete snapshot.
        if (listeners.isEmpty()) return;
        Set<String> modified = new HashSet<>(before.keySet());
        modified.addAll(copy.keySet());
        modified.removeIf(key -> Objects.equals(before.get(key), copy.get(key)));
        if (!modified.isEmpty()) {
            mainHandler.post(() -> {
                for (String key : modified) {
                    for (OnSharedPreferenceChangeListener listener : listeners) {
                        try { listener.onSharedPreferenceChanged(this, key); }
                        catch (Throwable t) { XposedBridge.log("[WAEX] Preference listener error: " + t); }
                    }
                }
            });
        }
    }

    @Override
    public Map<String, ?> getAll() { return mutableCopy(memoryCache); }

    private static Map<String, Object> mutableCopy(Map<String, ?> values) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> e : values.entrySet()) {
            Object value = e.getValue();
            copy.put(e.getKey(), value instanceof Set<?> ? new HashSet<>((Set<?>) value) : value);
        }
        return copy;
    }

    @Nullable
    @Override
    public String getString(String key, @Nullable String defValue) {
        Object value = memoryCache.get(key);
        return value instanceof String ? (String) value : defValue;
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
        Object value = memoryCache.get(key);
        return value instanceof Set<?> ? new HashSet<>((Set<String>) value) : defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        Object value = memoryCache.get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) try { return Integer.parseInt((String) value); }
        catch (NumberFormatException ignored) { }
        return defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        Object value = memoryCache.get(key);
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) try { return Long.parseLong((String) value); }
        catch (NumberFormatException ignored) { }
        return defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        Object value = memoryCache.get(key);
        if (value instanceof Number) return ((Number) value).floatValue();
        if (value instanceof String) try { return Float.parseFloat((String) value); }
        catch (NumberFormatException ignored) { }
        return defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        Object value = memoryCache.get(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        if (value instanceof String) {
            if ("1".equals(value)) return true;
            if ("0".equals(value)) return false;
            return Boolean.parseBoolean((String) value);
        }
        return defValue;
    }

    @Override
    public boolean contains(String key) { return memoryCache.containsKey(key); }

    @Override
    public Editor edit() { return new BridgeEditor(); }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        if (listener != null) listeners.add(listener);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        listeners.remove(listener);
    }

    private boolean persist(boolean clear, Map<String, Object> updates) {
        try {
            Bundle request = new Bundle();
            request.putBoolean("clear", clear);
            ArrayList<Bundle> operations = new ArrayList<>();
            for (Map.Entry<String, Object> entry : updates.entrySet()) {
                Bundle operation = new Bundle();
                operation.putString("key", entry.getKey());
                Object value = entry.getValue();
                if (value == REMOVED) {
                    operation.putString("type", "remove");
                } else if (value instanceof String) {
                    operation.putString("type", "string");
                    operation.putString("value", (String) value);
                } else if (value instanceof Set<?>) {
                    operation.putString("type", "string_set");
                    @SuppressWarnings("unchecked") Set<String> values = (Set<String>) value;
                    operation.putStringArrayList("value", new ArrayList<>(values));
                } else if (value instanceof Boolean) {
                    operation.putString("type", "boolean");
                    operation.putBoolean("value", (Boolean) value);
                } else if (value instanceof Integer) {
                    operation.putString("type", "int");
                    operation.putInt("value", (Integer) value);
                } else if (value instanceof Long) {
                    operation.putString("type", "long");
                    operation.putLong("value", (Long) value);
                } else if (value instanceof Float) {
                    operation.putString("type", "float");
                    operation.putFloat("value", (Float) value);
                } else {
                    return false;
                }
                operations.add(operation);
            }
            request.putParcelableArrayList("operations", operations);
            Bundle reply = context.getContentResolver().call(PROVIDER_URI,
                    "apply_preferences", null, request);
            return reply != null && reply.getBoolean("success", false);
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Unable to save preferences via IPC: " + t.getMessage());
            return false;
        }
    }

    private synchronized void publishLocalEdits(boolean clear, Map<String, Object> updates) {
        Map<String, Object> next = clear ? new HashMap<>() : mutableCopy(memoryCache);
        for (Map.Entry<String, Object> change : updates.entrySet()) {
            if (change.getValue() == REMOVED) next.remove(change.getKey());
            else next.put(change.getKey(), change.getValue());
        }
        publishSnapshot(next);
    }

    private final class BridgeEditor implements Editor {
        private final Map<String, Object> updates = new LinkedHashMap<>();
        private boolean clear;

        @Override
        public Editor putString(String key, @Nullable String value) {
            updates.put(key, value == null ? REMOVED : value);
            return this;
        }
        @Override
        public Editor putStringSet(String key, @Nullable Set<String> values) {
            updates.put(key, values == null ? REMOVED : new HashSet<>(values));
            return this;
        }
        @Override
        public Editor putInt(String key, int value) { updates.put(key, value); return this; }
        @Override
        public Editor putLong(String key, long value) { updates.put(key, value); return this; }
        @Override
        public Editor putFloat(String key, float value) { updates.put(key, value); return this; }
        @Override
        public Editor putBoolean(String key, boolean value) { updates.put(key, value); return this; }
        @Override
        public Editor remove(String key) { updates.put(key, REMOVED); return this; }
        @Override
        public Editor clear() { clear = true; return this; }

        private Map<String, Object> frozenUpdates() {
            return mutableCopy(updates);
        }

        @Override
        public boolean commit() {
            Map<String, Object> changes = frozenUpdates();
            if (!clear && changes.isEmpty()) return true;
            try {
                // Serializing commits with pending apply() jobs preserves edit order.
                Future<Boolean> result = ioExecutor.submit(() -> persist(clear, changes));
                boolean succeeded = result.get();
                if (succeeded) publishLocalEdits(clear, changes);
                else requestRefresh();
                return succeeded;
            } catch (Throwable t) {
                requestRefresh();
                return false;
            }
        }

        @Override
        public void apply() {
            Map<String, Object> changes = frozenUpdates();
            if (!clear && changes.isEmpty()) return;
            publishLocalEdits(clear, changes); // SharedPreferences.apply: visible immediately.
            ioExecutor.execute(() -> {
                if (!persist(clear, changes)) requestRefresh();
            });
        }
    }
}
