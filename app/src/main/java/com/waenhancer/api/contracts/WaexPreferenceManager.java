package com.waenhancer.api.contracts;

public interface WaexPreferenceManager {
    boolean getBoolean(String key, boolean defaultValue);
    void putBoolean(String key, boolean value);
    int getInt(String key, int defaultValue);
    void putInt(String key, int value);
    String getString(String key, String defaultValue);
    void putString(String key, String value);
    float getFloat(String key, float defaultValue);
    void putFloat(String key, float value);
}
