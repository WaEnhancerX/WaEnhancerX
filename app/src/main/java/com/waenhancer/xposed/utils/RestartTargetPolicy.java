package com.waenhancer.xposed.utils;

import java.util.Set;
import java.util.regex.Pattern;

/** Pure-Java validation for the manager's privileged WhatsApp restart actions. */
final class RestartTargetPolicy {
    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");
    private static final Pattern ROOT_LAUNCHED = Pattern.compile(
            "(?s).*Starting:\\s*Intent\\s*\\{.*");
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

    static String forceStopCommand(String packageName, Set<String> declaredScope) {
        if (!isSupportedTarget(packageName, declaredScope)) {
            throw new IllegalArgumentException("Unsupported restart target");
        }
        // A single shell token is used after strict package-name validation.
        return "am force-stop '" + packageName + "'";
    }

    private static final Pattern COMPONENT_NAME = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+/\\.?[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");

    static String launchCommand(String packageName, Set<String> declaredScope) {
        return launchCommand(packageName, null, declaredScope);
    }

    static String launchCommand(String packageName, String activityComponent, Set<String> declaredScope) {
        if (!isSupportedTarget(packageName, declaredScope)) {
            throw new IllegalArgumentException("Unsupported restart target");
        }
        if (activityComponent != null
                && activityComponent.startsWith(packageName + "/")
                && COMPONENT_NAME.matcher(activityComponent).matches()) {
            return "am start -n '" + activityComponent + "'";
        }
        return "ACT=$(pm resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER '"
                + packageName + "' | tail -n 1) && am start -n \"$ACT\"";
    }

    static boolean reportsRootLaunch(String commandOutput) {
        return commandOutput != null
                && ROOT_LAUNCHED.matcher(commandOutput).matches()
                && !commandOutput.contains("Error:");
    }

    static boolean reportsRootId(String commandOutput) {
        return commandOutput != null && ROOT_ID.matcher(commandOutput).matches();
    }
}
