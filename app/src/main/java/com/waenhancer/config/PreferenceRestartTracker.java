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
        if (key == null) return;
        String targetPkg = targetPackage(key);
        String trackingKey = unscopedKey(key);

        String needRestartKey = scopedKey(targetPkg, NEED_RESTART);
        String pendingChangesKey = scopedKey(targetPkg, PENDING_CHANGES);
        String showRestartSheetKey = scopedKey(targetPkg, SHOW_RESTART_SHEET);

        if (SHOW_RESTART_SHEET.equals(trackingKey)) {
            boolean enabled = preferences.getBoolean(key, preferences.getBoolean(SHOW_RESTART_SHEET, true));
            if (!enabled) {
                preferences.edit()
                        .putBoolean(needRestartKey, false)
                        .remove(pendingChangesKey)
                        .apply();
            }
            return;
        }

        boolean showSheet = preferences.getBoolean(showRestartSheetKey, preferences.getBoolean(SHOW_RESTART_SHEET, true));
        if (!showSheet) return;
        if (!requiresRestart(trackingKey)) return;

        Set<String> pending = new LinkedHashSet<>(preferences.getStringSet(
                pendingChangesKey, Collections.emptySet()));
        // Store the stable key and resolve its current UI title when the sheet is built.
        pending.add(trackingKey);
        preferences.edit()
                .putStringSet(pendingChangesKey, pending)
                .putBoolean(needRestartKey, true)
                .apply();
    }

    /** Extracts package name from scoped key e.g. "com.whatsapp:anti_revoke" -> "com.whatsapp". */
    @Nullable
    public static String targetPackage(@Nullable String key) {
        if (key == null) return null;
        int separator = key.indexOf(':');
        if (separator > 0 && separator < key.length() - 1) {
            return key.substring(0, separator);
        }
        return null;
    }

    /** Builds target-scoped or universal preference key. */
    @NonNull
    public static String scopedKey(@Nullable String targetPackage, @NonNull String baseKey) {
        return (targetPackage == null || targetPackage.isEmpty()) ? baseKey : targetPackage + ":" + baseKey;
    }

    /** Unscopes per-target preferences stored as package:key. */
    @Nullable
    public static String unscopedKey(@Nullable String key) {
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
