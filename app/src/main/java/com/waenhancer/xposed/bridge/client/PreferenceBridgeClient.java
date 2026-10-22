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
 * High-performance, fallback-resilient Preference Bridge for injected processes.
 * Combines direct XSharedPreferences disk reads with ContentProvider IPC fallback and real-time ContentObserver updates.
 */
public final class PreferenceBridgeClient implements SharedPreferences {

    public static final String AUTHORITY = "com.waenhancer.hookprovider";
    public static final Uri PREFS_URI = Uri.parse("content://" + AUTHORITY + "/preferences");
    private static final Uri PROVIDER_URI = Uri.parse("content://" + AUTHORITY);
    private static final Object REMOVED = new Object();

    private final Context context;
    private final SharedPreferences diskPrefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "WAEX-Preferences");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger refreshGeneration = new AtomicInteger();
    private final AtomicBoolean refreshScheduled = new AtomicBoolean();
    @Nullable private final Future<?> initialSync;
    private volatile Map<String, Object> memoryCache = Collections.emptyMap();
    // Guarded by this. Provider snapshots cannot erase writes accepted but not yet persisted.
    private Map<String, Object> confirmedCache = Collections.emptyMap();
    private final Map<Long, PendingEdit> pendingEdits = new LinkedHashMap<>();
    private long nextEditId;
    private long mutationGeneration;
    private long latestSyncId;

    private static final class PendingEdit {
        final boolean clear;
        final boolean optimistic;
        final Map<String, Object> values;
        PendingEdit(boolean clear, Map<String, Object> values, boolean optimistic) {
            this.clear = clear;
            this.values = new LinkedHashMap<>(values);
            this.optimistic = optimistic;
        }
    }
    private volatile String lastSyncSource = "PENDING";
    @Nullable private volatile String lastSyncError;

    public PreferenceBridgeClient(@NonNull Context context, @Nullable SharedPreferences diskPrefs) {
        this(context, diskPrefs, false);
    }

    /**
     * Creates a bridge and optionally overlaps the authoritative provider read with target-app
     * startup. The disk snapshot is published first so reads always have a usable fallback.
     */
    public PreferenceBridgeClient(@NonNull Context context, @Nullable SharedPreferences diskPrefs,
                                  boolean syncInBackground) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        this.diskPrefs = diskPrefs;

        publishDiskSnapshot(false);
        registerObserver();
        if (syncInBackground) {
            initialSync = ioExecutor.submit(this::syncPreferences);
        } else {
            initialSync = null;
            syncPreferences();
        }
    }

    private final Set<OnSharedPreferenceChangeListener> changeListeners = new CopyOnWriteArraySet<>();

    /** Waits for the startup provider read before security-sensitive feature decisions are made. */
    public void awaitInitialSync() {
        Future<?> pending = initialSync;
        if (pending == null) return;
        try {
            pending.get();
        } catch (InterruptedException interrupted) {
            // The provider read may still be running. Preserve cancellation of the startup
            // waiter instead of installing features from a snapshot that is not authoritative.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Initial preference sync interrupted", interrupted);
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] PreferenceBridgeClient: Initial preference sync failed: "
                    + t.getClass().getSimpleName());
        }
    }

    public void syncPreferences() {
        final long generation;
        final long syncId;
        synchronized (this) { generation = mutationGeneration; syncId = ++latestSyncId; }
        Map<String, Object> loaded = null;
        String providerError = null;
        try {
            Bundle bundle = context.getContentResolver().call(PROVIDER_URI,
                    "get_all_preferences", null, null);
            if (bundle != null) {
                loaded = decodePreferences(bundle);
            }
        } catch (Throwable t) {
            providerError = diagnosticError(t);
            XposedBridge.log("[WAEX] PreferenceBridgeClient: Provider IPC query failed: " + t.getClass().getSimpleName());
        }

        // Disk read fallback if provider query was unavailable
        if (loaded != null) {
            publishRemoteSnapshot(loaded, generation, syncId, "PROVIDER", null);
            return;
        }
        loaded = readDiskSnapshot(true);
        if (loaded != null) {
            publishRemoteSnapshot(loaded, generation, syncId, "DISK_FALLBACK", providerError);
        } else {
            synchronized (this) {
                if (generation == mutationGeneration && syncId == latestSyncId && pendingEdits.isEmpty()) {
                    lastSyncSource = "FAILED";
                    lastSyncError = providerError != null ? providerError : "No preference source was readable";
                }
            }
        }
    }

    public String getLastSyncSource() {
        return lastSyncSource;
    }

    @Nullable
    public String getLastSyncError() {
        return lastSyncError;
    }

    private static String diagnosticError(Throwable error) {
        // Exception messages may embed private JIDs, user data, or filesystem paths.
        // Runtime health records only the stable exception class, never its raw text.
        String detail = error.getClass().getSimpleName();
        detail = detail.replace('\n', ' ').replace('\r', ' ').trim();
        return detail.length() <= 240 ? detail : detail.substring(0, 240);
    }

    private void publishDiskSnapshot(boolean reload) {
        Map<String, Object> loaded = readDiskSnapshot(reload);
        if (loaded != null) publishSnapshot(loaded);
    }

    @Nullable
    private Map<String, Object> readDiskSnapshot(boolean reload) {
        if (diskPrefs == null) return null;
        try {
            if (reload && diskPrefs instanceof XSharedPreferences) {
                ((XSharedPreferences) diskPrefs).reload();
            }
            Map<String, ?> all = diskPrefs.getAll();
            return all == null ? null : copyValues(all);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private Map<String, Object> decodePreferences(Bundle result) {
        Bundle values = result.getBundle("values");
        Bundle sets = result.getBundle("sets");
        if (values == null && sets == null) return null;
        Map<String, Object> map = new HashMap<>();
        if (values != null) {
            for (String key : values.keySet()) {
                Object value = values.get(key);
                if (value != null) map.put(key, value);
            }
        }
        if (sets != null) {
            for (String key : sets.keySet()) {
                ArrayList<String> value = sets.getStringArrayList(key);
                if (value != null) map.put(key, new HashSet<>(value));
            }
        }
        return map;
    }

    private static Map<String, Object> copyValues(Map<String, ?> source) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (entry.getKey() != null && value != null) {
                copy.put(entry.getKey(), value instanceof Set<?>
                        ? Collections.unmodifiableSet(new HashSet<>((Set<?>) value)) : value);
            }
        }
        return copy;
    }

    private void publishSnapshot(Map<String, ?> candidate) {
        Set<String> modified;
        synchronized (this) {
            confirmedCache = copyValues(candidate);
            modified = rebuildViewLocked();
        }
        dispatchChanges(modified);
    }

    private void publishRemoteSnapshot(Map<String, ?> candidate, long generation, long syncId,
                                       String source, @Nullable String error) {
        Set<String> modified;
        synchronized (this) {
            // Also reject reads started AFTER apply but BEFORE its queued IPC write.
            if (generation != mutationGeneration || syncId != latestSyncId || !pendingEdits.isEmpty()) return;
            confirmedCache = copyValues(candidate);
            lastSyncSource = source;
            lastSyncError = error;
            modified = rebuildViewLocked();
        }
        dispatchChanges(modified);
    }

    private static Map<String, Object> applyEdits(Map<String, ?> base, PendingEdit edit) {
        Map<String, Object> next = edit.clear ? new HashMap<>() : copyValues(base);
        for (Map.Entry<String, Object> entry : edit.values.entrySet()) {
            if (entry.getValue() == REMOVED) next.remove(entry.getKey());
            else next.put(entry.getKey(), entry.getValue());
        }
        return next;
    }

    /** Replays accepted optimistic edits over confirmed state in submission order. */
    private Set<String> rebuildViewLocked() {
        Map<String, Object> view = copyValues(confirmedCache);
        for (PendingEdit edit : pendingEdits.values()) {
            if (edit.optimistic) view = applyEdits(view, edit);
        }
        Map<String, Object> next = Collections.unmodifiableMap(copyValues(view));
        Map<String, Object> previous = memoryCache;
        memoryCache = next;
        Set<String> modified = new HashSet<>(previous.keySet());
        modified.addAll(next.keySet());
        modified.removeIf(key -> Objects.equals(previous.get(key), next.get(key)));
        return modified;
    }

    private void dispatchChanges(Set<String> modified) {
        // Never invoke external listeners while holding the state/submission lock.
        if (!modified.isEmpty() && !changeListeners.isEmpty()) {
            mainHandler.post(() -> {
                for (String key : modified) notifyListeners(key);
            });
        }
    }

    private Future<Boolean> enqueueEdit(boolean clear, Map<String, Object> values,
                                        boolean optimistic) {
        final Future<Boolean> result;
        Set<String> modified;
        synchronized (this) {
            final long id = ++nextEditId;
            final PendingEdit edit = new PendingEdit(clear, values, optimistic);
            pendingEdits.put(id, edit);
            mutationGeneration++;
            try {
                // Registration and enqueue are atomic, so concurrent callers share one order.
                result = ioExecutor.submit(() -> {
                    boolean success = persist(edit.clear, edit.values);
                    Set<String> completedChanges;
                    synchronized (PreferenceBridgeClient.this) {
                        pendingEdits.remove(id);
                        if (success) confirmedCache = applyEdits(confirmedCache, edit);
                        mutationGeneration++;
                        completedChanges = rebuildViewLocked();
                    }
                    dispatchChanges(completedChanges);
                    // Reconcile failures and provider-side normalization after pending writes drain.
                    requestRefresh();
                    return success;
                });
            } catch (RuntimeException rejected) {
                pendingEdits.remove(id);
                mutationGeneration++;
                throw rejected;
            }
            modified = rebuildViewLocked();
        }
        dispatchChanges(modified);
        return result;
    }

    private void notifyListeners(@Nullable String key) {
        for (OnSharedPreferenceChangeListener listener : changeListeners) {
            try { listener.onSharedPreferenceChanged(this, key); }
            catch (Throwable t) { XposedBridge.log("[WAEX] Preference change listener error: " + t.getClass().getSimpleName()); }
        }
    }

    private void registerObserver() {
        try {
            context.getContentResolver().registerContentObserver(
                    PREFS_URI,
                    true,
                    new ContentObserver(mainHandler) {
                        @Override
                        public void onChange(boolean selfChange) {
                            requestRefresh();
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] PreferenceBridgeClient: ContentObserver registration failed: " + t.getClass().getSimpleName());
        }
    }

    /** Coalesces live refresh signals on the existing ordered preference worker. */
    public void requestRefresh() {
        refreshGeneration.incrementAndGet();
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
                if (observed != refreshGeneration.get()) requestRefresh();
            }
        });
    }

    @Override
    public Map<String, ?> getAll() {
        return copyValues(memoryCache);
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
        return val instanceof Set ? new HashSet<>((Set<String>) val) : defValues;
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
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        if (listener != null) {
            changeListeners.add(listener);
        }
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        if (listener != null) {
            changeListeners.remove(listener);
        }
    }

    private class BridgeEditor implements Editor {
        private final Map<String, Object> values = new LinkedHashMap<>();
        private boolean clear;

        @Override
        public Editor putString(String key, @Nullable String value) {
            values.put(key, value == null ? REMOVED : value); return this;
        }
        @Override
        public Editor putStringSet(String key, @Nullable Set<String> values) {
            this.values.put(key, values == null ? REMOVED : new HashSet<>(values));
            return this;
        }
        @Override
        public Editor putInt(String key, int value) { values.put(key, value); return this; }
        @Override
        public Editor putLong(String key, long value) { values.put(key, value); return this; }
        @Override
        public Editor putFloat(String key, float value) { values.put(key, value); return this; }
        @Override
        public Editor putBoolean(String key, boolean value) { values.put(key, value); return this; }
        @Override
        public Editor remove(String key) { values.put(key, REMOVED); return this; }
        @Override
        public Editor clear() { clear = true; return this; }
        @Override
        public boolean commit() {
            Map<String, Object> changes = new LinkedHashMap<>(values);
            final boolean clearSnapshot = clear;
            if (!clearSnapshot && changes.isEmpty()) return true;
            try {
                // Publish successful confirmed state inside the ordered worker, before returning.
                return enqueueEdit(clearSnapshot, changes, false).get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                requestRefresh();
                return false;
            } catch (Throwable t) {
                requestRefresh();
                return false;
            }
        }

        @Override
        public void apply() {
            Map<String, Object> changes = new LinkedHashMap<>(values);
            final boolean clearSnapshot = clear;
            if (!clearSnapshot && changes.isEmpty()) return;
            enqueueEdit(clearSnapshot, changes, true);
        }
    }

    private boolean persist(boolean clear, Map<String, Object> values) {
        try {
            Bundle request = new Bundle();
            request.putBoolean("clear", clear);
            ArrayList<Bundle> operations = new ArrayList<>();
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                String key = entry.getKey();
                Object val = entry.getValue();
                Bundle operation = new Bundle();
                operation.putString("key", key);
                if (val == REMOVED) operation.putString("type", "remove");
                else if (val instanceof String) { operation.putString("type", "string"); operation.putString("value", (String) val); }
                else if (val instanceof Boolean) { operation.putString("type", "boolean"); operation.putBoolean("value", (Boolean) val); }
                else if (val instanceof Integer) { operation.putString("type", "int"); operation.putInt("value", (Integer) val); }
                else if (val instanceof Long) { operation.putString("type", "long"); operation.putLong("value", (Long) val); }
                else if (val instanceof Float) { operation.putString("type", "float"); operation.putFloat("value", (Float) val); }
                else if (val instanceof Set) {
                    operation.putString("type", "string_set");
                    @SuppressWarnings("unchecked") Set<String> set = (Set<String>) val;
                    operation.putStringArrayList("value", new ArrayList<>(set));
                } else return false;
                operations.add(operation);
            }
            request.putParcelableArrayList("operations", operations);
            Bundle response = context.getContentResolver().call(
                    PROVIDER_URI, "apply_preferences", null, request);
            return response != null && response.getBoolean("success", false);
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Preference batch write failed: " + t.getClass().getSimpleName());
            return false;
        }
    }

}
