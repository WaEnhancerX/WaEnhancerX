package com.waenhancer.ui.screens.settings;

/**
 * UI label keys that describe existing canonical BOOLEAN public preferences.
 * Do not map non-Boolean settings (CSS, DPI, string-set tabs, etc.) here.
 */
public final class StyleBooleanAliasPolicy {
    private StyleBooleanAliasPolicy() {}

    public static String canonicalKey(String uiKey) {
        if (uiKey == null) return null;
        switch (uiKey) {
            case "bubble_colors": return "bubble_color";
            case "custom_time_format": return "segundos";
            case "custom_toolbar": return "novaconfig";
            case "instagram_status_layout": return "igstatus";
            case "contact_blocked_verify": return "verify_blocked_contact";
            default: return uiKey;
        }
    }
}
