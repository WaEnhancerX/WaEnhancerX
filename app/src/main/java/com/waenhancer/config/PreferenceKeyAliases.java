package com.waenhancer.config;

import androidx.annotation.NonNull;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Canonical preference-key aliases shared by catalog navigation and UI labels. */
public final class PreferenceKeyAliases {
    private static final Map<String, String> ALIASES;

    static {
        Map<String, String> aliases = new HashMap<>();
        aliases.put("freeze_last_seen", "freezelastseen");
        aliases.put("antirevoke", "anti_revoke");
        aliases.put("downloadstatus", "status_downloader");
        aliases.put("downloadviewonce", "download_view_once");
        aliases.put("custom_css", "custom_theme");
        aliases.put("css_theme", "custom_theme");
        aliases.put("change_dpi", "custom_view_dpi");
        aliases.put("chatfilter", "chat_filters");
        aliases.put("hidetabs", "hide_ui_tabs");
        aliases.put("show_home_menu", "quick_home_menu");
        aliases.put("waex_menu_style", "quick_home_menu");
        aliases.put("open_waex", "open_wae");
        aliases.put("miscellaneous_enhancements", "disable_sensor_proximity");
        aliases.put("novaconfig", "custom_toolbar");
        aliases.put("segundos", "custom_time_format");
        aliases.put("igstatus", "instagram_status_layout");
        aliases.put("verify_blocked_contact", "contact_blocked_verify");
        ALIASES = Collections.unmodifiableMap(aliases);
    }

    private PreferenceKeyAliases() {}

    @NonNull
    public static String canonical(@NonNull String key) {
        String canonical = ALIASES.get(key);
        return canonical != null ? canonical : key;
    }
}
