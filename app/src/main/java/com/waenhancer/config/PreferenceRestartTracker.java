package com.waenhancer.config;

import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Collections;

/** Records user-facing feature names that require the hooked app to restart. */
public final class PreferenceRestartTracker {
    public static final String NEED_RESTART = "need_restart";
    public static final String PENDING_CHANGES = "pending_restart_changes";
    public static final String SHOW_RESTART_SHEET = "show_restart_sheet";

    private PreferenceRestartTracker() {}


    public static synchronized void onPreferenceChanged(
            @NonNull SharedPreferences preferences, @Nullable String key) {
        if (SHOW_RESTART_SHEET.equals(key)) {
            if (!preferences.getBoolean(SHOW_RESTART_SHEET, true)) {
                preferences.edit()
                        .putBoolean(NEED_RESTART, false)
                        .remove(PENDING_CHANGES)
                        .apply();
            }
            return;
        }
        if (!preferences.getBoolean(SHOW_RESTART_SHEET, true)) return;
        String trackingKey = unscopedKey(key);
        if (!requiresRestart(trackingKey)) return;

        Set<String> pending = new LinkedHashSet<>(preferences.getStringSet(
                PENDING_CHANGES, Collections.emptySet()));
        // Store the stable key and resolve its current UI title when the sheet is built.
        pending.add(trackingKey);
        preferences.edit()
                .putStringSet(PENDING_CHANGES, pending)
                .putBoolean(NEED_RESTART, true)
                .apply();
    }

    /** Per-target preferences are stored as package:key but restart metadata is universal. */
    @Nullable
    private static String unscopedKey(@Nullable String key) {
        if (key == null) return null;
        int separator = key.indexOf(':');
        if (separator > 0 && separator < key.length() - 1) {
            return key.substring(separator + 1);
        }
        return key;
    }

    public static boolean requiresRestart(@Nullable String key) {
        if (key == null || NEED_RESTART.equals(key) || PENDING_CHANGES.equals(key)
                || SHOW_RESTART_SHEET.equals(key)) return false;

        // FeatureCatalog is the UI's source of truth. Consult it directly so newly cataloged
        // features automatically participate in restart prompting even before they need an
        // explicit schema entry. PreferenceSchema still covers subordinate feature settings
        // such as colors, dimensions, counters, and modes that are not standalone catalog rows.
        if (FeatureCatalog.findByKey(key) != null) return true;

        PreferenceSchema.Entry entry = PreferenceSchema.entry(key);
        return entry != null
                && entry.store == PreferenceSchema.Store.PUBLIC
                && entry.sensitivity == PreferenceSchema.Sensitivity.PUBLIC_SETTING;
    }

    @NonNull
    public static String displayName(@NonNull String key) {
        String[] words = key.replace('-', '_').split("_+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            if ("ai".equalsIgnoreCase(word) || "hd".equalsIgnoreCase(word)
                    || "dpi".equalsIgnoreCase(word) || "dnd".equalsIgnoreCase(word)) {
                result.append(word.toUpperCase(Locale.ROOT));
            } else {
                result.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) result.append(word.substring(1));
            }
        }
        return result.length() == 0 ? key : result.toString();
    }
}
