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
        try { RestartTargetPolicy.forceStopCommand(candidate, scope, 10); }
        catch (IllegalArgumentException expected) { failed = true; }
        check(failed, "force-stop command allowed for invalid target: " + candidate);
        failed = false;
        try { RestartTargetPolicy.launchCommand(candidate, scope, "com.whatsapp.Launcher", 10); }
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
            check(RestartTargetPolicy.forceStopCommand(packageName, scope, 10).equals(
                    "am force-stop --user 10 '" + packageName + "'"), "force-stop shell quoting");
            check(RestartTargetPolicy.launchCommand(packageName, scope, packageName + ".Launcher", 10).equals(
                    "am start -W --user 10 -n '" + packageName + "/" + packageName + ".Launcher' -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10008000"), "launch scoping");
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
        check(RestartTargetPolicy.reportsActivityLaunch("Starting: Intent {...}\nStatus: ok\nComplete\n"), "launch acknowledged");
        check(RestartTargetPolicy.reportsActivityLaunch("Status: ok\r\nComplete\r\n"), "CRLF acknowledgment");
        for (String output : new String[]{null, "", "Events injected: 1", "Status: timeout", "Status: okay", "Fake Status: ok", "Status: ok trailing"}) {
            check(!RestartTargetPolicy.reportsActivityLaunch(output), "invalid launch acknowledgment");
        }
        for (int userId : new int[]{-1, -2, -3, Integer.MIN_VALUE, Integer.MAX_VALUE, 21475}) {
            boolean rejected = false;
            try { RestartTargetPolicy.forceStopCommand("com.whatsapp", scope, userId); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "special/out-of-range stop user accepted");
            rejected = false;
            try { RestartTargetPolicy.launchCommand("com.whatsapp", scope, "com.whatsapp.Launcher", userId); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "special/out-of-range launch user accepted");
        }
        for (String activity : new String[]{null, "", ".", "Launcher", "com.whatsapp.Launcher;id", "com.whatsapp.Launcher'", "com.whatsapp.Launcher\n", "com.whatsapp/Launcher", "x".repeat(1025)}) {
            boolean rejected = false;
            try { RestartTargetPolicy.launchCommand("com.whatsapp", scope, activity, 10); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "invalid activity accepted");
        }
        check(RestartTargetPolicy.launchCommand("com.whatsapp", scope, ".Launcher", 11)
                .contains("--user 11 -n 'com.whatsapp/com.whatsapp.Launcher'"), "relative launcher normalized");
        check(RestartTargetPolicy.launchCommand("com.whatsapp", scope, "androidx.activity.ComponentActivity", 0)
                .contains("--user 0 -n 'com.whatsapp/androidx.activity.ComponentActivity'"), "library activity stays in target package");
        check(com.waenhancer.utils.AndroidUserScope.userIdForUid(12345) == 0, "primary user UID");
        check(com.waenhancer.utils.AndroidUserScope.userIdForUid(1012345) == 10, "work profile UID");
        check(com.waenhancer.utils.AndroidUserScope.userIdForUid(1112345) == 11, "secondary profile UID");
        check(com.waenhancer.utils.AndroidUserScope.userIdForUid(Integer.MAX_VALUE) == 21474, "UID upper bound");
        boolean invalidUid = false;
        try { com.waenhancer.utils.AndroidUserScope.userIdForUid(-1); }
        catch (IllegalArgumentException expected) { invalidUid = true; }
        check(invalidUid, "negative UID refused");

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
