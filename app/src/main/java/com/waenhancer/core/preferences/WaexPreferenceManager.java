package com.waenhancer.core.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import com.waenhancer.config.PreferenceSchema;
import com.waenhancer.config.PreferenceStores;
import dagger.hilt.android.qualifiers.ApplicationContext;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class WaexPreferenceManager {

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

    public void putBoolean(String key, boolean value) {
        getStore(key).edit().putBoolean(key, value).commit();
        if (isPublicStore(key)) {
            fixFilePermissions();
            notifyChange();
        }
    }

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

    public void putString(String key, String value) {
        getStore(key).edit().putString(key, value).commit();
        if (isPublicStore(key)) {
            fixFilePermissions();
            notifyChange();
        }
    }

    public int getInt(String key, int defaultValue) {
        return getStore(key).getInt(key, defaultValue);
    }

    public void putInt(String key, int value) {
        getStore(key).edit().putInt(key, value).commit();
        if (isPublicStore(key)) {
            fixFilePermissions();
            notifyChange();
        }
    }

    public float getFloat(String key, float defaultValue) {
        return getStore(key).getFloat(key, defaultValue);
    }

    public void putFloat(String key, float value) {
        getStore(key).edit().putFloat(key, value).commit();
        if (isPublicStore(key)) {
            fixFilePermissions();
            notifyChange();
        }
    }

    public void remove(String key) {
        getStore(key).edit().remove(key).commit();
        if (isPublicStore(key)) {
            fixFilePermissions();
            notifyChange();
        }
    }

    public java.util.Map<String, ?> getAll() {
        return PreferenceStores.publicStore(context).getAll();
    }

    private void notifyChange() {
        try {
            context.getContentResolver().notifyChange(PREFS_URI, null);
        } catch (Throwable ignored) {}
    }

    private void fixFilePermissions() {
        try {
            java.io.File dataDir = new java.io.File(context.getApplicationInfo().dataDir);
            java.io.File prefsDir = new java.io.File(dataDir, "shared_prefs");
            java.io.File prefsFile = new java.io.File(prefsDir, PreferenceStores.PUBLIC_NAME + ".xml");

            dataDir.setExecutable(true, false);
            dataDir.setReadable(true, false);

            prefsDir.setExecutable(true, false);
            prefsDir.setReadable(true, false);

            prefsFile.setReadable(true, false);
        } catch (Throwable ignored) {}
    }
}
