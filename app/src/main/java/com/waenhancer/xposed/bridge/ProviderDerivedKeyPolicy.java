package com.waenhancer.xposed.bridge;

/** Protects provider-derived status and package telemetry from generic preference writes. */
final class ProviderDerivedKeyPolicy {
    private ProviderDerivedKeyPolicy() {}

    static boolean allowsGenericWrite(String key) {
        if (key == null || key.isEmpty()) return false;
        // Computed from the module's license state; a target app cannot set these.
        if ("waex_pro_active".equals(key) || key.startsWith("pro_entitlement_")) return false;
        // Only dedicated caller-validated routes may write package diagnostics.
        if ("hooked_whatsapp_packages".equals(key)
                || key.startsWith("injected_hooks_")
                || key.startsWith("last_active_")
                || key.startsWith("runtime_boot_")
                || key.startsWith("runtime_pid_")
                || key.startsWith("runtime_status_")
                || key.startsWith("runtime_error_")
                || key.startsWith("bridge_status_")) return false;
        if ("active_xposed_api_version".equals(key)) return false;
        return true;
    }

    /** A scoped host process must never erase the module's entire settings file. */
    static boolean mayClearPreferences(int callerUid, int moduleUid) {
        return callerUid > 0 && moduleUid > 0 && callerUid == moduleUid;
    }
}
