package com.waenhancer.core.preferences;

import android.content.SharedPreferences;
import com.waenhancer.api.contracts.WaexPreferenceManager;
import java.util.Set;

public class WaexPreferenceManagerImpl implements WaexPreferenceManager {
    private final SharedPreferences sharedPreferences;
    
    private static final Set<String> VALID_NAMESPACES = Set.of(
        "privacy", "media", "messaging", "automation", "ai", "system", "premium"
    );

    public WaexPreferenceManagerImpl(SharedPreferences sharedPreferences) {
        this.sharedPreferences = sharedPreferences;
    }

    private void validateKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException("Preference key cannot be null");
        }
        int firstDotIdx = key.indexOf('.');
        if (firstDotIdx == -1) {
            throw new IllegalArgumentException("Preference key must be namespace-based (e.g., category.keyName). Violating key: " + key);
        }
        String namespace = key.substring(0, firstDotIdx);
        if (!VALID_NAMESPACES.contains(namespace)) {
            throw new IllegalArgumentException("Invalid preference namespace '" + namespace + "'. Must be one of: " + VALID_NAMESPACES + ". Violating key: " + key);
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        validateKey(key);
        return sharedPreferences.getBoolean(key, defaultValue);
    }

    @Override
    public void putBoolean(String key, boolean value) {
        validateKey(key);
        sharedPreferences.edit().putBoolean(key, value).apply();
    }

    @Override
    public int getInt(String key, int defaultValue) {
        validateKey(key);
        return sharedPreferences.getInt(key, defaultValue);
    }

    @Override
    public void putInt(String key, int value) {
        validateKey(key);
        sharedPreferences.edit().putInt(key, value).apply();
    }

    @Override
    public String getString(String key, String defaultValue) {
        validateKey(key);
        return sharedPreferences.getString(key, defaultValue);
    }

    @Override
    public void putString(String key, String value) {
        validateKey(key);
        sharedPreferences.edit().putString(key, value).apply();
    }

    @Override
    public float getFloat(String key, float defaultValue) {
        validateKey(key);
        return sharedPreferences.getFloat(key, defaultValue);
    }

    @Override
    public void putFloat(String key, float value) {
        validateKey(key);
        sharedPreferences.edit().putFloat(key, value).apply();
    }
}
