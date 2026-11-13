package com.waenhancer.xposed.utils;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AppRestartHelper {
    public static boolean databaseAvailable = true;
    private static final Pattern COPY = Pattern.compile("cp -f /data/adb/lspd/config/modules_config\\.db '([^']+)' ");

    private AppRestartHelper() {}

    public static String runRootCommand(String command) throws IOException {
        if (!databaseAvailable) return "WAEX_LSPOSED_DB_MISSING";
        Matcher matcher = COPY.matcher(command);
        if (!matcher.find()) throw new IllegalStateException("Unexpected root copy command");
        File copy = new File(matcher.group(1));
        if (!copy.createNewFile()) throw new IOException("Expected a fresh DB copy");
        return "WAEX_LSPOSED_DB_OK";
    }
}
