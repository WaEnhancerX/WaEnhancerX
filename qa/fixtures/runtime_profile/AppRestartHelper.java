package com.waenhancer.xposed.utils;

/** The only controlled production endpoint: no root subprocess is executed. */
public final class AppRestartHelper {
    public static String output;
    public static String lastCommand;
    public static RuntimeException failure;
    public static int calls;

    private AppRestartHelper() {}

    public static void reset() {
        output = null;
        lastCommand = null;
        failure = null;
        calls = 0;
    }

    public static String runRootCommand(String command) {
        calls++;
        lastCommand = command;
        if (failure != null) throw failure;
        return output;
    }
}
