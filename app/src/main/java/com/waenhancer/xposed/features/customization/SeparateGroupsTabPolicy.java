package com.waenhancer.xposed.features.customization;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure tab-list transformation; kept independent of Android/Xposed for regression tests.
 *
 * WhatsApp 2.26.39.79 uses a WDSBottomBar with a maximum of five items. Groups
 * is a synthetic WAEX ID (500), while Communities is the native ID (600).
 */
final class SeparateGroupsTabPolicy {
    private static final int GROUPS = 500;
    private static final int COMMUNITIES = 600;
    private static final int MAX_BOTTOM_BAR_ITEMS = 5;

    private SeparateGroupsTabPolicy() {}

    static ArrayList<Integer> rewrite(List<Integer> nativeTabs) {
        ArrayList<Integer> result = new ArrayList<>(nativeTabs);
        // The hook may run more than once. Remove stale synthetic entries first.
        result.removeIf(id -> id != null && id == GROUPS);
        // Keep all other native destinations; Communities is the explicit trade-off.
        result.removeIf(id -> id != null && id == COMMUNITIES);
        if (result.size() < MAX_BOTTOM_BAR_ITEMS) {
            result.add(Math.min(1, result.size()), GROUPS);
        }
        // If WhatsApp already has five non-Communities tabs, fail closed: do
        // not insert a sixth or arbitrarily remove another native destination.
        return result;
    }
}
