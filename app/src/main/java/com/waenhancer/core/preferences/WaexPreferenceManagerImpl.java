package com.waenhancer.core.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import com.waenhancer.api.contracts.WaexPreferenceManager;
import dagger.hilt.android.qualifiers.ApplicationContext;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class WaexPreferenceManagerImpl implements WaexPreferenceManager {

    private final SharedPreferences prefs;

    @Inject
    public WaexPreferenceManagerImpl(@ApplicationContext Context context) {
        this.prefs = context.getSharedPreferences("waex_prefs", Context.MODE_PRIVATE);
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        return prefs.getBoolean(key, defaultValue);
    }

    @Override
    public void putBoolean(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    @Override
    public String getString(String key, String defaultValue) {
        return prefs.getString(key, defaultValue);
    }

    @Override
    public void putString(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    @Override
    public int getInt(String key, int defaultValue) {
        return prefs.getInt(key, defaultValue);
    }

    @Override
    public void putInt(String key, int value) {
        prefs.edit().putInt(key, value).apply();
    }

    @Override
    public float getFloat(String key, float defaultValue) {
        return prefs.getFloat(key, defaultValue);
    }

    @Override
    public void putFloat(String key, float value) {
        prefs.edit().putFloat(key, value).apply();
    }
}

