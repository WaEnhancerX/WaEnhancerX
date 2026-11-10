package com.waenhancer.xposed.utils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Pure-JDK regression tests against production root command policy. */
public final class TestRestartTargetPolicy {
    private static int checks;
    private static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    private static void reject(String candidate, Set<String> scope) {
        check(!RestartTargetPolicy.isSupportedTarget(candidate, scope), "unsafe target permitted: " + candidate);
        boolean failed = false;
        try { RestartTargetPolicy.forceStopCommand(candidate, scope); }
        catch (IllegalArgumentException expected) { failed = true; }
        check(failed, "force-stop command allowed for invalid target: " + candidate);
        failed = false;
        try { RestartTargetPolicy.launchCommand(candidate, scope); }
        catch (IllegalArgumentException expected) { failed = true; }
        check(failed, "root launch command allowed for invalid target: " + candidate);
    }

    public static void main(String[] args) {
        Set<String> scope = new LinkedHashSet<>(Arrays.asList(args));
        // The module's own package appears in xposed_scope and must be excluded.
        reject("com.waenhancer", scope);
        reject("com.waenhancer.internal", scope);
        check(RestartTargetPolicy.isSupportedTarget("com.whatsapp", null), "official fallback");
        check(RestartTargetPolicy.isSupportedTarget("com.whatsapp.w4b", null), "business fallback");
        for (String packageName : scope) {
            if (packageName.equals("com.waenhancer")) continue;
            check(RestartTargetPolicy.isSupportedTarget(packageName, scope), "declared clone: " + packageName);
            check(RestartTargetPolicy.forceStopCommand(packageName, scope).equals(
                    "am force-stop '" + packageName + "'"), "force-stop shell quoting");
            check(RestartTargetPolicy.launchCommand(packageName, scope).equals(
                    "ACT=$(pm resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER '"
                            + packageName + "' | tail -n 1) && am start -n \"$ACT\""), "launch scoping");
            check(RestartTargetPolicy.launchCommand(packageName, packageName + "/.Main", scope).equals(
                    "am start -n '" + packageName + "/.Main'"), "explicit component launch");
        }
        reject(null, scope); reject("", scope); reject(" ", scope);
        reject("com.whatsapp;reboot", scope);
        reject("com.whatsapp && echo malicious", scope);
        reject("com.whatsapp'", scope);
        reject("com.whatsapp\n", scope);
        reject("com.whatsapp\r", scope);
        reject("com.whatsapp\t", scope);
        reject("com..whatsapp", scope);
        reject(".com.whatsapp", scope);
        reject("com.whatsapp.", scope);
        reject("com.whatsapp/../../", scope);
        reject("com.whatsapp$(id)", scope);
        reject("com.whatsapp|sh", scope);
        reject("com.whatsapp;am force-stop com.android.settings", scope);
        reject("com.unknown.whatsapp", scope);
        reject("com.android.settings", scope);
        reject("android", scope);
        reject("com.whatsapp" + "x".repeat(230), scope);
        reject("com.whatsapp✔", scope);
        reject("COM.WHATSAPP", scope);
        reject("com.waenhancer", new LinkedHashSet<>(Arrays.asList("com.waenhancer")));

        // Even if someone accidentally inserts the manager into the list, don't restart it.
        check(!RestartTargetPolicy.isSupportedTarget("com.waenhancer", new LinkedHashSet<>(Arrays.asList("com.waenhancer"))),
                "manager forbidden");
        check(!RestartTargetPolicy.isSupportedTarget("com.whatsapp.clone9999", scope),
                "undocumented/guessed package must not get root privileges");
        check(RestartTargetPolicy.reportsRootId("uid=0(root) gid=0(root)"), "root ID");
        check(RestartTargetPolicy.reportsRootId("foo\nuid=0(root)"), "multiline root ID");
        check(!RestartTargetPolicy.reportsRootId(null), "null root result");
        check(!RestartTargetPolicy.reportsRootId("uid=1000(shell) gid=1000(shell)"), "shell is not root");
        check(!RestartTargetPolicy.reportsRootId("notroot uid=100"), "nonroot rejected");
        check(!RestartTargetPolicy.reportsRootId("uid=00(root)"), "exact uid required");
        check(RestartTargetPolicy.reportsRootLaunch("Starting: Intent { cmp=com.whatsapp/.Main }\n"), "launched");
        check(RestartTargetPolicy.reportsRootLaunch("Starting: Intent { act=android.intent.action.MAIN }\nWarning: already running\n"), "multi line start");
        check(!RestartTargetPolicy.reportsRootLaunch("Error: Activity class does not exist"), "error report");
        check(!RestartTargetPolicy.reportsRootLaunch(null), "null root launch output");

        // Test common shell metacharacters against arbitrary declared-scope mistakes.
        String[] invalid = {"'", "\"", ";", "&", "|", String.valueOf((char)96), "$", "(", ")", " ", "\n", "\r", "\\", "<", ">", "?", "#", "*", "[", "]"};
        for (int i = 0; i < 6000; ++i) {
            String name = "com.whatsapp" + invalid[i % invalid.length] + "malicious";
            // Deliberately inject into the scope: syntax filter is a separate boundary.
            Set<String> poisonedScope = new LinkedHashSet<>(scope);
            poisonedScope.add(name);
            reject(name, poisonedScope);
        }
        System.out.println("PASS: " + checks + " policy, scope, shell-injection and root-output assertions");
    }
}
