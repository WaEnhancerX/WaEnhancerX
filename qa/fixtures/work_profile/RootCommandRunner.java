package com.waenhancer.xposed.utils;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Explicit privileged-process endpoint: records commands and never starts su, am or pm. */
public final class RootCommandRunner {
    public interface Starter { Process start(String command) throws IOException; }
    public static final List<String> commands = new CopyOnWriteArrayList<>();
    public static volatile String mode = "root";
    public static volatile long copiedBytes;
    public static volatile CountDownLatch entered, release, cleaned;

    public static void reset() {
        commands.clear();
        mode = "root";
        copiedBytes = 0;
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        cleaned = new CountDownLatch(1);
    }

    public static String run(String command, long timeout, Starter ignored) {
        return run(command, timeout);
    }

    public static String run(String command, long timeout) {
        commands.add(command);
        if ("blocked".equals(mode)) {
            entered.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Unreleased root fixture");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
            return null;
        }
        if ("denied".equals(mode)) return null;
        if ("id".equals(command)) return "uid=0(root) gid=0(root)";
        if (command.startsWith("am force-stop")) return "";
        if (command.startsWith("am start") || command.startsWith("monkey")) {
            if ("start-denied".equals(mode)) return null;
            if ("start-timeout".equals(mode)) return "Status: timeout\nComplete\n";
            if ("start-malformed".equals(mode)) return "Status: okay\nComplete\n";
            return command.startsWith("monkey") ? "Events injected: 1\n"
                    : "Starting: Intent {...}\nStatus: ok\nComplete\n";
        }
        if (command.equals("mktemp /data/local/tmp/waex.XXXXXX")) return "/data/local/tmp/waex.FIXTURE1\n";
        if (command.startsWith("cat ") && command.endsWith("wc -c < '/data/local/tmp/waex.FIXTURE1'")) {
            return Long.toString(copiedBytes);
        }
        // Even a successful install is deliberately not simulated: no delayed kill is scheduled.
        if (command.startsWith("pm install ")) return "Failure [QA_NO_INSTALLATION_PERFORMED]\n";
        if (command.equals("rm -f -- '/data/local/tmp/waex.FIXTURE1'")) {
            cleaned.countDown();
            return "";
        }
        throw new AssertionError("Unexpected privileged command: " + command);
    }
}
