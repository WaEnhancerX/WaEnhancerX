package com.waenhancer.core.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.InputStream;
import java.io.OutputStream;
import com.waenhancer.config.PreferenceSchema;
import com.waenhancer.config.PreferenceStores;
import com.waenhancer.config.PreferenceRestartTracker;
import com.waenhancer.licensing.customprops.CustomAbPropsStore;
import dagger.hilt.android.qualifiers.ApplicationContext;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Singleton
public final class WaexPreferenceManager implements CustomAbPropsStore {

    private final Context context;
    private volatile String targetPackage;
    private volatile int targetEpoch;

    private static final String MODE_KEY = "module_config_mode";
    private static final String PER_TARGET_MODE = "Per Target App";

    @Inject
    public WaexPreferenceManager(@ApplicationContext Context context) {
        this.context = context;
        disableShowAsDeletedForExistingUsers();
    }

    /** Native deleted-row rendering is parked; migrate old enabled values off immediately. */
    private void disableShowAsDeletedForExistingUsers() {
        try {
            SharedPreferences store = PreferenceStores.publicStore(context);
            Map<String, ?> values = store.getAll();
            SharedPreferences.Editor editor = store.edit();
            boolean changed = false;
            for (String key : values.keySet()) {
                if ("show_as_deleted".equals(key) || key.endsWith(":show_as_deleted")) {
                    Object value = values.get(key);
                    if (Boolean.TRUE.equals(value)) {
                        editor.putBoolean(key, false);
                        changed = true;
                    }
                }
            }
            if (changed) editor.commit();
        } catch (Throwable ignored) {
            // Preference migration must never prevent the settings screen from opening.
        }
    }

    /** Selects the target whose settings are edited by the UI. */
    public void setTargetPackage(String packageName) {
        if (java.util.Objects.equals(targetPackage, packageName)) return;
        targetPackage = packageName;
        targetEpoch++;
        if (packageName != null && PER_TARGET_MODE.equals(rawString(MODE_KEY, "Universal"))) {
            initializeTarget(packageName);
        }
    }

    /** Changes when the active target changes; preference screens use it to refresh controls only. */
    public int getTargetEpoch() { return targetEpoch; }

    private String rawString(String key, String fallback) {
        return PreferenceStores.publicStore(context).getString(key, fallback);
    }

    /** Package names are part of the key so old universal settings remain intact. */
    public String resolvedKey(String key) {
        if (key == null || MODE_KEY.equals(key) || targetPackage == null
                || !PER_TARGET_MODE.equals(rawString(MODE_KEY, "Universal"))) return key;
        return targetPackage + ":" + key;
    }

    private void initializeTarget(String packageName) {
        SharedPreferences universal = PreferenceStores.publicStore(context);
        SharedPreferences.Editor editor = universal.edit();
        boolean changed = false;
        for (PreferenceSchema.Entry entry : PreferenceSchema.all().values()) {
            if (entry.store != PreferenceSchema.Store.PUBLIC || entry.sensitivity == PreferenceSchema.Sensitivity.RUNTIME) continue;
            String scoped = packageName + ":" + entry.key;
            if (!universal.contains(scoped) && universal.contains(entry.key)) {
                Object value = universal.getAll().get(entry.key);
                if (value instanceof Boolean) editor.putBoolean(scoped, (Boolean) value);
                else if (value instanceof String) editor.putString(scoped, (String) value);
                else if (value instanceof Integer) editor.putInt(scoped, (Integer) value);
                else if (value instanceof Long) editor.putLong(scoped, (Long) value);
                else if (value instanceof Float) editor.putFloat(scoped, (Float) value);
                else if (value instanceof Set<?>) editor.putStringSet(scoped, new LinkedHashSet<>((Set<String>) value));
                changed = true;
            }
        }
        if (changed) editor.commit();
    }

    private SharedPreferences getStore(String key) {
        return PreferenceStores.storeFor(context, resolvedKey(key));
    }

    private String key(String key) { return resolvedKey(key); }

    private boolean isPublicStore(String key) {
        PreferenceSchema.Entry entry = PreferenceSchema.entry(key);
        return entry == null || entry.store == PreferenceSchema.Store.PUBLIC;
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        try {
            SharedPreferences store = getStore(key);
            String resolved = key(key);
            if (store.contains(resolved)) {
                return store.getBoolean(resolved, defaultValue);
            }
            if (!resolved.equals(key) && store.contains(key)) {
                return store.getBoolean(key, defaultValue);
            }
            return defaultValue;
        } catch (ClassCastException e) {
            try {
                SharedPreferences store = getStore(key);
                String resolved = key(key);
                Object allVal = store.getAll().get(resolved);
                if (allVal == null && !resolved.equals(key)) {
                    allVal = store.getAll().get(key);
                }
                if (allVal instanceof Boolean) {
                    return (Boolean) allVal;
                } else if (allVal instanceof String) {
                    String s = (String) allVal;
                    return "true".equalsIgnoreCase(s) || "1".equals(s);
                } else if (allVal instanceof Number) {
                    return ((Number) allVal).intValue() != 0;
                }
                return defaultValue;
            } catch (Throwable ignored) {
                return defaultValue;
            }
        }
    }

    private static final Uri PREFS_URI = Uri.parse("content://com.waenhancer.hookprovider/preferences");

    @Override
    public void putBoolean(String key, boolean value) {
        getStore(key).edit().putBoolean(key(key), value).commit();
        if (isPublicStore(key)) {
            PreferenceRestartTracker.onPreferenceChanged(PreferenceStores.publicStore(context), key(key));
            notifyChange();
        }
    }

    /**
     * Persist related public Boolean settings as a single transaction and notify
     * injected processes once. Reject invalid/private keys before any mutation.
     * A false result means the caller must not optimistically update the UI.
     */
    public boolean putBooleans(Map<String, Boolean> values) {
        if (values == null) return false;
        try {
            // Snapshot once: validation and commit must see the same set of keys.
            Map<String, Boolean> batch = new LinkedHashMap<>(values);
            if (batch.isEmpty()) return false;
            for (Map.Entry<String, Boolean> change : batch.entrySet()) {
                String key = change.getKey();
                if (key == null || key.isEmpty() || change.getValue() == null) return false;
                PreferenceSchema.Entry definition = PreferenceSchema.entry(key);
                if (definition != null && (definition.type != PreferenceSchema.Type.BOOLEAN
                        || definition.store != PreferenceSchema.Store.PUBLIC)) return false;
            }
            SharedPreferences.Editor editor = PreferenceStores.publicStore(context).edit();
            for (Map.Entry<String, Boolean> change : batch.entrySet()) {
                editor.putBoolean(resolvedKey(change.getKey()), change.getValue());
            }
            if (!editor.commit()) return false;
            for (Map.Entry<String, Boolean> change : batch.entrySet()) {
                PreferenceRestartTracker.onPreferenceChanged(
                        PreferenceStores.publicStore(context), resolvedKey(change.getKey()));
            }
            notifyChange();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override
    public String getString(String key, String defaultValue) {
        try {
        return getStore(key).getString(key(key), defaultValue);
        } catch (ClassCastException e) {
            try {
                Object allVal = getStore(key).getAll().get(key);
                return allVal != null ? String.valueOf(allVal) : defaultValue;
            } catch (Throwable ignored) {
                return defaultValue;
            }
        }
    }

    @Override
    public void putString(String key, String value) {
        getStore(key).edit().putString(key(key), value).commit();
        if (isPublicStore(key)) {
            PreferenceRestartTracker.onPreferenceChanged(PreferenceStores.publicStore(context), key(key));
            notifyChange();
        }
    }

    /**
     * Commit a schema-declared public STRING and report persistence failure.
     * Existing void setters keep their contract. A false result is not a disk or
     * in-memory rollback guarantee: SharedPreferences may update memory first.
     */
    public boolean putPublicString(String key, String value) {
        if (value == null || !isPublicType(key, PreferenceSchema.Type.STRING)) return false;
        try {
            if (!PreferenceStores.publicStore(context).edit().putString(resolvedKey(key), value).commit()) {
                return false;
            }
            PreferenceRestartTracker.onPreferenceChanged(
                    PreferenceStores.publicStore(context), resolvedKey(key));
            notifyChange();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Commit a defensive snapshot of a public STRING_SET, notifying only on success. */
    public boolean putPublicStringSet(String key, Set<String> value) {
        if (value == null || !isPublicType(key, PreferenceSchema.Type.STRING_SET)) return false;
        try {
            Set<String> snapshot = new LinkedHashSet<>();
            for (Object element : value) {
                if (!(element instanceof String)) return false;
                snapshot.add((String) element);
            }
            if (!PreferenceStores.publicStore(context).edit().putStringSet(resolvedKey(key), snapshot).commit()) {
                return false;
            }
            PreferenceRestartTracker.onPreferenceChanged(
                    PreferenceStores.publicStore(context), resolvedKey(key));
            notifyChange();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isPublicType(String key, PreferenceSchema.Type type) {
        if (key == null || key.isEmpty()) return false;
        PreferenceSchema.Entry entry = PreferenceSchema.entry(key);
        return entry != null && entry.type == type && entry.store == PreferenceSchema.Store.PUBLIC;
    }

    public Set<String> getStringSet(String key, Set<String> defaultValue) {
        try {
            Set<String> value = getStore(key).getStringSet(key(key), defaultValue);
            return value == null ? defaultValue : new LinkedHashSet<>(value);
        } catch (ClassCastException ignored) {
            return defaultValue;
        }
    }

    public void putStringSet(String key, Set<String> value) {
        getStore(key).edit().putStringSet(key(key), new LinkedHashSet<>(value)).commit();
        if (isPublicStore(key)) {
            PreferenceRestartTracker.onPreferenceChanged(PreferenceStores.publicStore(context), key(key));
            notifyChange();
        }
    }

    public int getInt(String key, int defaultValue) {
        return getStore(key).getInt(key(key), defaultValue);
    }

    public void putInt(String key, int value) {
        getStore(key).edit().putInt(key(key), value).commit();
        if (isPublicStore(key)) {
            PreferenceRestartTracker.onPreferenceChanged(PreferenceStores.publicStore(context), key(key));
            notifyChange();
        }
    }

    public float getFloat(String key, float defaultValue) {
        return getStore(key).getFloat(key(key), defaultValue);
    }

    public void putFloat(String key, float value) {
        getStore(key).edit().putFloat(key(key), value).commit();
        if (isPublicStore(key)) {
            PreferenceRestartTracker.onPreferenceChanged(PreferenceStores.publicStore(context), key(key));
            notifyChange();
        }
    }

    public void remove(String key) {
        getStore(key).edit().remove(key(key)).commit();
        if (isPublicStore(key)) {
            notifyChange();
        }
    }

    public Map<String, ?> getAll() {
        return PreferenceStores.publicStore(context).getAll();
    }

    public int exportSettings(Uri destination) throws Exception {
        OutputStream output = context.getContentResolver().openOutputStream(destination, "wt");
        if (output == null) throw new IOException("Could not open backup destination");
        return SettingsBackupCodec.INSTANCE.encode(context, output);
    }

    public int importSettings(Uri source) throws Exception {
        InputStream input = context.getContentResolver().openInputStream(source);
        if (input == null) throw new IOException("Could not open backup file");
        int count = SettingsBackupCodec.INSTANCE.decode(context, input);
        notifyChange();
        return count;
    }

    private void notifyChange() {
        try {
            PreferenceStores.makePublicStoreReadable(context);
            context.getContentResolver().notifyChange(PREFS_URI, null);
        } catch (Throwable ignored) {}
    }

}
