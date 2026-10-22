package com.waenhancer.config;

/** Runtime observations share the preference file, but are not hook configuration changes. */
public final class PreferenceSyncPolicy {
    private PreferenceSyncPolicy() {}

    public static boolean shouldNotifyHooks(String key) {
        // Android may report null for a whole-store clear. Configuration must then be reloaded.
        if (key == null) return true;
        return !("hooked_whatsapp_packages".equals(key)
                || "active_xposed_api_version".equals(key)
                || "active_xposed_framework".equals(key)
                || key.startsWith("injected_hooks_")
                || key.startsWith("last_active_")
                || key.startsWith("runtime_boot_")
                || key.startsWith("runtime_pid_")
                || key.startsWith("runtime_status_")
                || key.startsWith("runtime_error_")
                || key.startsWith("bridge_status_"));
    }
}
