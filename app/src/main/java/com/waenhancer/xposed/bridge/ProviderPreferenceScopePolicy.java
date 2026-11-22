package com.waenhancer.xposed.bridge;

import com.waenhancer.config.PreferenceSchema;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Protects the shared preference IPC snapshot against cross-target namespace disclosure.
 * Global compatibility decisions belong to the manager, not an injected target process.
 */
final class ProviderPreferenceScopePolicy {
    private ProviderPreferenceScopePolicy() {}

    /** Target processes use unprefixed schema keys; package:key is internal storage. */
    static boolean mayReadKey(String key, boolean moduleCaller) {
        return key != null && !key.isEmpty() && (moduleCaller || key.indexOf(':') < 0);
    }

    static boolean mayWriteKey(String key, boolean moduleCaller) {
        return mayReadKey(key, moduleCaller)
                && (moduleCaller || !PreferenceSchema.isGlobalOnly(key));
    }

    /**
     * The caller-specific overrides were already applied to bare keys by HookProvider.
     * Do not send raw keys for any supported package to another hooked application.
     * Preserve non-target namespaces and leave the persisted source untouched.
     */
    static HashMap<String, Object> filterTargetNamespaces(
            Map<String, Object> all, Set<String> supportedPackages) {
        HashMap<String, Object> filtered = new HashMap<>();
        for (Map.Entry<String, Object> entry : all.entrySet()) {
            String key = entry.getKey();
            if (key != null && supportedPackages != null) {
                int delimiter = key.indexOf(':');
                if (delimiter > 0 && supportedPackages.contains(key.substring(0, delimiter))) {
                    continue;
                }
            }
            filtered.put(key, entry.getValue());
        }
        return filtered;
    }
}
