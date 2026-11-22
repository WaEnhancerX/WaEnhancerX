package com.waenhancer.xposed.bridge;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public final class TestProviderPreferenceScopePolicy {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        HashMap<String, Object> all = new HashMap<>();
        all.put("module_config_mode", "Per Target App");
        all.put("hideads", Boolean.FALSE);
        all.put("com.whatsapp:hideads", Boolean.TRUE);
        all.put("com.whatsapp.w4b:hideads", Boolean.FALSE);
        all.put("com.whatsapp.w4b:private_choice", "business-only");
        all.put("com.whatsapp:bypass_version_check", Boolean.TRUE);
        all.put("unrelated:custom_value", "keep");
        Set<String> targets = new HashSet<>(Arrays.asList("com.whatsapp", "com.whatsapp.w4b"));

        HashMap<String, Object> filtered =
                ProviderPreferenceScopePolicy.filterTargetNamespaces(all, targets);
        check(!filtered.containsKey("com.whatsapp:hideads"), "own prefixed key exposed");
        check(!filtered.containsKey("com.whatsapp.w4b:hideads"), "other target key exposed");
        check(!filtered.containsKey("com.whatsapp.w4b:private_choice"), "other target data exposed");
        check(!filtered.containsKey("com.whatsapp:bypass_version_check"), "stale global override exposed");
        check(Boolean.FALSE.equals(filtered.get("hideads")), "effective unprefixed setting lost");
        check("keep".equals(filtered.get("unrelated:custom_value")), "unrelated key removed");
        check(all.containsKey("com.whatsapp.w4b:private_choice"), "original preferences mutated");

        check(ProviderPreferenceScopePolicy.mayReadKey("hideads", false), "normal target read refused");
        check(ProviderPreferenceScopePolicy.mayReadKey("bypass_version_check", false), "global read refused");
        check(!ProviderPreferenceScopePolicy.mayReadKey("com.whatsapp.w4b:hideads", false),
                "direct cross-target read accepted");
        check(!ProviderPreferenceScopePolicy.mayReadKey("", false), "empty read accepted");
        check(ProviderPreferenceScopePolicy.mayReadKey("com.whatsapp.w4b:hideads", true),
                "manager raw read refused");

        check(ProviderPreferenceScopePolicy.mayWriteKey("hideads", false), "normal target write refused");
        for (String key : Arrays.asList("module_config_mode", "customize_supported_versions",
                "custom_supported_versions", "custom_supported_versions_str",
                "bypass_version_check", "com.whatsapp.w4b:hideads")) {
            check(!ProviderPreferenceScopePolicy.mayWriteKey(key, false),
                    "target may write global or namespaced key: " + key);
            check(ProviderPreferenceScopePolicy.mayWriteKey(key, true),
                    "manager write refused: " + key);
        }
        check(!ProviderPreferenceScopePolicy.mayWriteKey(null, false), "null key accepted");
        System.out.println("PASS: provider target namespaces and global-only write policy");
    }
}
