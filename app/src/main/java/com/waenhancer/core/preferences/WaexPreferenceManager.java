package com.waenhancer.core.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.InputStream;
import java.io.OutputStream;
import com.waenhancer.config.PreferenceSchema;
import com.waenhancer.config.PreferenceStores;
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

    @Inject
    public WaexPreferenceManager(@ApplicationContext Context context) {
        this.context = context;
    }

    private SharedPreferences getStore(String key) {
        return PreferenceStores.storeFor(context, key);
    }

    private boolean isPublicStore(String key) {
        PreferenceSchema.Entry entry = PreferenceSchema.entry(key);
        return entry == null || entry.store == PreferenceSchema.Store.PUBLIC;
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        try {
            return getStore(key).getBoolean(key, defaultValue);
        } catch (ClassCastException e) {
            try {
                Object allVal = getStore(key).getAll().get(key);
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
        getStore(key).edit().putBoolean(key, value).commit();
        if (isPublicStore(key)) {
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
                editor.putBoolean(change.getKey(), change.getValue());
            }
            if (!editor.commit()) return false;
            notifyChange();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override
    public String getString(String key, String defaultValue) {
        try {
            return getStore(key).getString(key, defaultValue);
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
        getStore(key).edit().putString(key, value).commit();
        if (isPublicStore(key)) {
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
            if (!PreferenceStores.publicStore(context).edit().putString(key, value).commit()) {
                return false;
            }
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
            if (!PreferenceStores.publicStore(context).edit().putStringSet(key, snapshot).commit()) {
                return false;
            }
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
            Set<String> value = getStore(key).getStringSet(key, defaultValue);
            return value == null ? defaultValue : new LinkedHashSet<>(value);
        } catch (ClassCastException ignored) {
            return defaultValue;
        }
    }

    public void putStringSet(String key, Set<String> value) {
        getStore(key).edit().putStringSet(key, new LinkedHashSet<>(value)).commit();
        if (isPublicStore(key)) {
            notifyChange();
        }
    }

    public int getInt(String key, int defaultValue) {
        return getStore(key).getInt(key, defaultValue);
    }

    public void putInt(String key, int value) {
        getStore(key).edit().putInt(key, value).commit();
        if (isPublicStore(key)) {
            notifyChange();
        }
    }

    public float getFloat(String key, float defaultValue) {
        return getStore(key).getFloat(key, defaultValue);
    }

    public void putFloat(String key, float value) {
        getStore(key).edit().putFloat(key, value).commit();
        if (isPublicStore(key)) {
            notifyChange();
        }
    }

    public void remove(String key) {
        getStore(key).edit().remove(key).commit();
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
            context.getContentResolver().notifyChange(PREFS_URI, null);
        } catch (Throwable ignored) {}
    }

}
