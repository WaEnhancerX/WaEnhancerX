package com.waenhancer.core.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import com.waenhancer.api.contracts.WaexPreferenceManager;
import dagger.hilt.android.qualifiers.ApplicationContext;
import javax.inject.Inject;
import javax.inject.Singleton;


@Singleton
public final class WaexPreferenceManagerImpl implements WaexPreferenceManager {

    private final Context context;
    private final SharedPreferences prefs;

    @Inject
    public WaexPreferenceManagerImpl(@ApplicationContext Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences("waex_prefs", Context.MODE_PRIVATE);
    }


    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        return prefs.getBoolean(key, defaultValue);
    }

    private static final Uri PREFS_URI = Uri.parse("content://com.waenhancer.hookprovider/preferences");


    @Override
    public void putBoolean(String key, boolean value) {
        prefs.edit().putBoolean(key, value).commit();
        fixFilePermissions();
        notifyChange();
    }

    @Override
    public String getString(String key, String defaultValue) {
        return prefs.getString(key, defaultValue);
    }

    @Override
    public void putString(String key, String value) {
        prefs.edit().putString(key, value).commit();
        fixFilePermissions();
        notifyChange();
    }

    @Override
    public int getInt(String key, int defaultValue) {
        return prefs.getInt(key, defaultValue);
    }

    @Override
    public void putInt(String key, int value) {
        prefs.edit().putInt(key, value).commit();
        fixFilePermissions();
        notifyChange();
    }

    @Override
    public float getFloat(String key, float defaultValue) {
        return prefs.getFloat(key, defaultValue);
    }

    @Override
    public void putFloat(String key, float value) {
        prefs.edit().putFloat(key, value).commit();
        fixFilePermissions();
        notifyChange();
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
            java.io.File prefsFile = new java.io.File(prefsDir, "waex_prefs.xml");

            dataDir.setExecutable(true, false);
            dataDir.setReadable(true, false);

            prefsDir.setExecutable(true, false);
            prefsDir.setReadable(true, false);

            prefsFile.setReadable(true, false);
        } catch (Throwable ignored) {}
    }

}

