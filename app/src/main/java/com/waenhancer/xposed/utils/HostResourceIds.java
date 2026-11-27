package com.waenhancer.xposed.utils;

import android.content.Context;
import android.content.res.Resources;
import android.view.View;

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

    /** Resource type to (name to id). Two levels avoid building a "type/name" key on every lookup. */
    // Identical resource names may have different IDs (or be absent) in the
    // WAEX manager and the injected WhatsApp process. Cache by package first.
    private static final Map<String, Map<String, Map<String, Integer>>> CACHE =
            new ConcurrentHashMap<>();

    private HostResourceIds() {}

    /** Host {@code R.id.<name>}, or 0 when this build has no such id. */
    public static int id(@NonNull Context context, @NonNull String name) {
        return resolve(context, name, "id");
    }

    public static int resolve(@NonNull Context context, @NonNull String name, @NonNull String type) {
        Map<String, Map<String, Integer>> byType = CACHE.computeIfAbsent(
                context.getPackageName(), ignored -> new ConcurrentHashMap<>());
        Map<String, Integer> byName = byType.computeIfAbsent(
                type, ignored -> new ConcurrentHashMap<>());
        Integer cached = byName.get(name);
        if (cached != null) return cached;
        int resolved = context.getResources().getIdentifier(name, type, context.getPackageName());
        byName.put(name, resolved);
        return resolved;
    }

    /** Entry names by package, then id. Keyed by package because module views reuse 0x7f ids. */
    private static final Map<String, Map<Integer, String>> ENTRY_NAMES = new ConcurrentHashMap<>();
    /** Stored for ids that have no entry name (generated view ids, NO_ID); never a real name. */
    private static final String NO_ENTRY = new String();

    /**
     * Drop-in for {@code view.getResources().getResourceEntryName(id)} that resolves each id once
     * per package instead of making a native call and allocating a String on every view or row.
     * Like the platform method, it throws {@link Resources.NotFoundException} when the id has no
     * name, so existing callers keep their behavior.
     */
    @NonNull
    public static String entryName(@NonNull View view, int id) throws Resources.NotFoundException {
        String pkg = view.getContext().getPackageName();
        Map<Integer, String> byId = ENTRY_NAMES.get(pkg);
        if (byId == null) byId = ENTRY_NAMES.computeIfAbsent(pkg, ignored -> new ConcurrentHashMap<>());
        String name = byId.get(id);
        if (name == null) {
            try {
                name = view.getResources().getResourceEntryName(id);
            } catch (Resources.NotFoundException missing) {
                name = NO_ENTRY;
            }
            byId.put(id, name);
        }
        if (name == NO_ENTRY) throw new Resources.NotFoundException("No entry name for id " + id);
        return name;
    }
}
