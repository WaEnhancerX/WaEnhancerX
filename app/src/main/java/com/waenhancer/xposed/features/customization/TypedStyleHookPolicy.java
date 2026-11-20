package com.waenhancer.xposed.features.customization;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Pure value transformations shared by typed style editors and their runtime hooks. */
public final class TypedStyleHookPolicy {
    private TypedStyleHookPolicy() {}

    public static int parseDpi(String value) {
        try {
            int dpi = Integer.parseInt(value == null ? "0" : value.trim());
            return dpi >= 120 && dpi <= 1000 ? dpi : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** Keep the user's selected tab IDs regardless of unrelated status-layout options. */
    public static Set<Integer> parseHiddenTabIds(Set<String> selected) {
        Set<Integer> hidden = new HashSet<>();
        if (selected == null) return hidden;
        for (String value : selected) {
            try { hidden.add(Integer.parseInt(value)); }
            catch (NumberFormatException ignored) { /* Ignore malformed legacy entries. */ }
        }
        return hidden;
    }

    public static void filterTabIds(ArrayList<Object> tabs, Set<Integer> hidden) {
        tabs.removeIf(value -> value instanceof Integer && hidden.contains(value));
    }
}
