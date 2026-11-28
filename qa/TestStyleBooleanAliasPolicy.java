package com.waenhancer.ui.screens.settings;

import java.util.Random;

public final class TestStyleBooleanAliasPolicy {
    private static int checks;
    private static void check(boolean ok, String text) {
        checks++;
        if (!ok) throw new AssertionError(text);
    }
    public static void main(String[] args) {
        String[][] aliases = {
                {"bubble_colors", "bubble_color"},
                {"custom_time_format", "segundos"},
                {"custom_toolbar", "novaconfig"},
                {"instagram_status_layout", "igstatus"},
                {"contact_blocked_verify", "verify_blocked_contact"},
        };
        for (String[] item : aliases) {
            check(item[1].equals(StyleBooleanAliasPolicy.canonicalKey(item[0])), "alias target " + item[0]);
            check(item[1].equals(StyleBooleanAliasPolicy.canonicalKey(item[1])), "canonical idempotence");
        }
        for (String key : new String[]{"custom_theme", "custom_view_dpi", "chat_filters",
                "hide_ui_tabs", "quick_home_menu", "seen_tick_customization",
                "seentick", "disable_sensor_proximity", "separate_groups_tabs",
                "send_audio_as_voice_status", "voice_status_enhancement", ""}) {
            check(key.equals(StyleBooleanAliasPolicy.canonicalKey(key)), "must not corrupt non-Boolean setting " + key);
        }
        check(StyleBooleanAliasPolicy.canonicalKey(null) == null, "null accepted without mutation");
        Random random = new Random(0xB001A5);
        for (int i = 0; i < 100000; i++) {
            String key = "other_" + random.nextInt(1000000);
            check(key.equals(StyleBooleanAliasPolicy.canonicalKey(key)), "unrelated key changed");
        }
        System.out.println("PASS: " + checks + " Style BOOLEAN alias and non-Boolean isolation assertions");
    }
}
