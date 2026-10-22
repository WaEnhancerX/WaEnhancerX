package com.waenhancer.xposed.bridge;

public final class TestProviderDerivedKeyPolicy {
    private static int tests;
    private static void check(boolean result, String reason) {
        tests++;
        if (!result) throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        for (String key : new String[]{
            "waex_pro_active", "pro_entitlement_audio_to_voice_status",
            "pro_entitlement_unlock_wa_plus", "pro_entitlement_custom_flag",
            "hooked_whatsapp_packages", "injected_hooks_com.whatsapp",
            "injected_hooks_time_com.whatsapp", "last_active_com.whatsapp",
            "runtime_boot_com.whatsapp", "runtime_status_com.whatsapp",
            "runtime_pid_com.whatsapp", "runtime_pid_com.whatsapp.w4b",
            "runtime_pid_com.example.clone", "runtime_pid_",
            "runtime_error_com.whatsapp", "bridge_status_com.whatsapp",
            "active_xposed_api_version"
        }) check(!ProviderDerivedKeyPolicy.allowsGenericWrite(key), "protected key accepted: " + key);
        for (String key : new String[]{
            "anti_revoke", "toastdeleted", "imagequality", "video_maxfps",
            "custom_css", "separategroups", "downloadstatus", "voice_status_enhancement",
            "legacy_compatible_setting", "runtime_health_ui_enabled"
        }) check(ProviderDerivedKeyPolicy.allowsGenericWrite(key), "normal key rejected: " + key);
        check(!ProviderDerivedKeyPolicy.allowsGenericWrite(null), "null key");
        check(!ProviderDerivedKeyPolicy.allowsGenericWrite(""), "empty key");
        check(ProviderDerivedKeyPolicy.mayClearPreferences(12000, 12000), "self UID may clear");
        check(!ProviderDerivedKeyPolicy.mayClearPreferences(12001, 12000), "WhatsApp UID cannot clear");
        check(!ProviderDerivedKeyPolicy.mayClearPreferences(12000, 12001), "different module UID");
        check(!ProviderDerivedKeyPolicy.mayClearPreferences(0, 0), "root UID must not be accepted as module");
        check(!ProviderDerivedKeyPolicy.mayClearPreferences(-1, -1), "invalid UIDs must not match");
        System.out.println("PASS: " + tests + " generic preference/health/clear policy assertions");
    }
}
