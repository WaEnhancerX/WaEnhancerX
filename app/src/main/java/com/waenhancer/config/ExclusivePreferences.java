package com.waenhancer.config;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Boolean preferences that must never be on at the same time. Switching one on switches its
 * partner off. The rule lives with the data, so every write path (settings rows, dedicated
 * screens, backup import) enforces it the same way.
 */
public final class ExclusivePreferences {
    public static final String MODULE_FLOATING_BAR = "floating_bottom_bar";
    public static final String NATIVE_FLOATING_NAVIGATION = "native_floating_navigation";

    /** Both directions. */
    private static final Map<String, String> PARTNER;
    /** When a restored backup has both on, the first of each pair is kept and the second cleared. */
    private static final Map<String, String> CLEARED_ON_CONFLICT;

    static {
        Map<String, String> partner = new LinkedHashMap<>();
        partner.put(MODULE_FLOATING_BAR, NATIVE_FLOATING_NAVIGATION);
        partner.put(NATIVE_FLOATING_NAVIGATION, MODULE_FLOATING_BAR);
        PARTNER = Collections.unmodifiableMap(partner);
        CLEARED_ON_CONFLICT = Collections.singletonMap(MODULE_FLOATING_BAR, NATIVE_FLOATING_NAVIGATION);
    }

    private ExclusivePreferences() {}

    /** The preference that must be off while {@code key} is on, or null. */
    @Nullable
    public static String partnerOf(@Nullable String key) {
        return key == null ? null : PARTNER.get(key);
    }

    /**
     * Applies the rule to a batch of writes, in order: every key set to true also sets its
     * partner to false. A later entry in the batch overrides an earlier one, so a batch that
     * switches both on ends with only the last one on.
     */
    @NonNull
    public static Map<String, Boolean> expand(@NonNull Map<String, Boolean> batch) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> change : batch.entrySet()) {
            result.put(change.getKey(), change.getValue());
            String partner = Boolean.TRUE.equals(change.getValue()) ? partnerOf(change.getKey()) : null;
            if (partner != null) result.put(partner, false);
        }
        return result;
    }

    /** Keys to clear when both members of a pair are on (for example after a restore). */
    @NonNull
    public static Map<String, String> clearedOnConflict() {
        return CLEARED_ON_CONFLICT;
    }
}
