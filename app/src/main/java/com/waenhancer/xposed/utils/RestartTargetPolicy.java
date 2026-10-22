package com.waenhancer.xposed.utils;

import java.util.Set;
import java.util.regex.Pattern;

/** Pure-Java validation for the manager's privileged WhatsApp restart actions. */
final class RestartTargetPolicy {
    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");
    private static final Pattern ACTIVITY_NAME = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    private static final Pattern ACTIVITY_LAUNCHED = Pattern.compile("(?m)^Status: ok\\r?$");
    private static final Pattern ROOT_ID = Pattern.compile(
            "(?s).*(?:^|\\s)uid=0(?:\\(|\\s|$).*");

    private RestartTargetPolicy() {}

    /** Do not allow arbitrary installed apps or dynamically guessed clones to be force-stopped. */
    static boolean isSupportedTarget(String packageName, Set<String> declaredScope) {
        if (packageName == null || packageName.length() > 223
                || !PACKAGE_NAME.matcher(packageName).matches()
                || packageName.equals("com.waenhancer")
                || packageName.startsWith("com.waenhancer.")) return false;
        return packageName.equals("com.whatsapp")
                || packageName.equals("com.whatsapp.w4b")
                || declaredScope != null && declaredScope.contains(packageName);
    }

    static String forceStopCommand(String packageName, Set<String> declaredScope, int userId) {
        if (!isSupportedTarget(packageName, declaredScope)) {
            throw new IllegalArgumentException("Unsupported restart target");
        }
        requireUserId(userId);
        // A single shell token is used after strict package-name validation.
        // Omitting --user means USER_ALL, which would stop other profiles too.
        return "am force-stop --user " + userId + " '" + packageName + "'";
    }

    static String launchCommand(String packageName, Set<String> declaredScope,
                                String activityName, int userId) {
        if (!isSupportedTarget(packageName, declaredScope)) {
            throw new IllegalArgumentException("Unsupported restart target");
        }
        requireUserId(userId);
        if (activityName == null || activityName.length() > 1024) {
            throw new IllegalArgumentException("Invalid launcher activity");
        }
        String qualifiedName = activityName.startsWith(".")
                ? packageName + activityName : activityName;
        if (!ACTIVITY_NAME.matcher(qualifiedName).matches()) {
            throw new IllegalArgumentException("Invalid launcher activity");
        }
        // The caller obtains this component from its own PackageManager. Monkey
        // resolves activities for the foreground user, which excludes work profiles.
        return "am start -W --user " + userId + " -n '" + packageName + "/" + qualifiedName
                + "' -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
                + " -f 0x10008000";
    }

    private static void requireUserId(int userId) {
        // Negative IDs are Android's special all/current/current-or-self selectors.
        if (userId < 0 || userId > Integer.MAX_VALUE / 100_000) {
            throw new IllegalArgumentException("Invalid Android user");
        }
    }

    static boolean reportsActivityLaunch(String commandOutput) {
        return commandOutput != null && ACTIVITY_LAUNCHED.matcher(commandOutput).find();
    }

    static boolean reportsRootId(String commandOutput) {
        return commandOutput != null && ROOT_ID.matcher(commandOutput).matches();
    }
}
