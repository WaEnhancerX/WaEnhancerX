package com.waenhancer.xposed.utils;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached {@code Resources.getIdentifier} for the host app.
 *
 * <p>{@code getIdentifier(name)} is a linear string-pool search and measured at ~20% of the UI
 * thread while scrolling a chat when called per row. Results, including "not found" (0), are
 * constant for the process lifetime, so each name is resolved once.
 */
public final class HostResourceIds {

    private static final Map<String, Integer> CACHE = new ConcurrentHashMap<>();

    private HostResourceIds() {}

    /** Host {@code R.id.<name>}, or 0 when this build has no such id. */
    public static int id(@NonNull Context context, @NonNull String name) {
        return resolve(context, name, "id");
    }

    public static int resolve(@NonNull Context context, @NonNull String name, @NonNull String type) {
        String key = type + '/' + name;
        Integer cached = CACHE.get(key);
        if (cached != null) return cached;
        int resolved = context.getResources().getIdentifier(name, type, context.getPackageName());
        CACHE.put(key, resolved);
        return resolved;
    }
}
