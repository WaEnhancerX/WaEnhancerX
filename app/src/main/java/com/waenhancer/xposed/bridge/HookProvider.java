package com.waenhancer.xposed.bridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;

/**
 * HookProvider provides preference IPC bridge between WaEnhancerX UI and target processes (WhatsApp / WA Business).
 */
public class HookProvider extends ContentProvider {

    public static final String AUTHORITY = "com.waenhancer.hookprovider";

    @Override
    public boolean onCreate() {

        return getContext() != null;
    }

    private SharedPreferences getPrefs() {
        Context context = getContext();
        if (context == null) return null;
        return context.getSharedPreferences("waex_prefs", Context.MODE_PRIVATE);
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        long token = Binder.clearCallingIdentity();
        try {
            SharedPreferences prefs = getPrefs();
            if (prefs == null) return null;

            Context context = getContext();
            if (context == null) return null;

            if ("get_all_preferences".equals(method)) {
                var all = prefs.getAll();
                Bundle result = new Bundle();
                result.putSerializable("prefs", new HashMap<>(all));
                return result;
            }

            if ("get_preference".equals(method) && extras != null) {
                String key = extras.getString("key");
                Bundle result = new Bundle();
                if (key != null) {
                    Object value = prefs.getAll().get(key);
                    if (value instanceof Boolean) result.putBoolean("value", (Boolean) value);
                    else if (value instanceof String) result.putString("value", (String) value);
                    else if (value instanceof Integer) result.putInt("value", (Integer) value);
                    else if (value instanceof Long) result.putLong("value", (Long) value);
                    else if (value instanceof Float) result.putFloat("value", (Float) value);
                }
                return result;
            }

            if ("put_preference".equals(method) && extras != null) {
                String key = extras.getString("key");
                String type = extras.getString("type");
                if (key == null || type == null) return null;

                var editor = prefs.edit();
                switch (type) {
                    case "string":
                        editor.putString(key, extras.getString("value"));
                        break;
                    case "string_set":
                        var values = extras.getStringArrayList("value");
                        editor.putStringSet(key, values == null ? null : new HashSet<>(values));
                        break;
                    case "boolean":
                        editor.putBoolean(key, extras.getBoolean("value"));
                        break;
                    case "int":
                        editor.putInt(key, extras.getInt("value"));
                        break;
                    case "long":
                        editor.putLong(key, extras.getLong("value"));
                        break;
                    case "float":
                        editor.putFloat(key, extras.getFloat("value"));
                        break;
                    default:
                        return null;
                }
                editor.commit();
                fixPermissions();
                context.getContentResolver().notifyChange(Uri.parse("content://" + AUTHORITY + "/preferences"), null);
                return Bundle.EMPTY;
            }

            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return "";
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    private void fixPermissions() {
        try {
            Context context = getContext();
            if (context == null) return;
            File dataDir = new File(context.getApplicationInfo().dataDir);
            File prefsDir = new File(dataDir, "shared_prefs");
            File prefsFile = new File(prefsDir, "waex_prefs.xml");

            dataDir.setExecutable(true, false);
            dataDir.setReadable(true, false);

            prefsDir.setExecutable(true, false);
            prefsDir.setReadable(true, false);

            prefsFile.setReadable(true, false);
        } catch (Throwable ignored) {}
    }
}
