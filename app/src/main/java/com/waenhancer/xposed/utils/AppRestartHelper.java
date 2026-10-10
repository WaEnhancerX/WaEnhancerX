package com.waenhancer.xposed.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class AppRestartHelper {

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AppRestartHelper() {}

    public static boolean hasRootAccess() {
        String output = runRootCommand("id");
        return output != null && (output.contains("uid=0") || output.contains("root"));
    }

    public static String runRootCommand(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            final Process commandProcess = process;
            StringBuilder output = new StringBuilder(2048);
            Thread drainer = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(commandProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (output) {
                            if (output.length() < 8192) output.append(line, 0,
                                    Math.min(line.length(), 8192 - output.length())).append('\n');
                        }
                    }
                } catch (IOException ignored) { }
            }, "WAEX-root-output");
            drainer.setDaemon(true);
            drainer.start();
            boolean finished = process.waitFor(4, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            drainer.join(500);
            if (process.exitValue() != 0) return null;
            synchronized (output) { return output.toString().trim(); }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (process != null) process.destroy();
        }
    }

    /**
     * Force stops the given target package via root (or IPC broadcast) and then restarts it.
     */
    public static void restartPackage(Context context, String packageName, String appName) {
        if (packageName == null || !packageName.matches("[A-Za-z_][A-Za-z0-9_.]*")) return;
        mainHandler.post(() -> Toast.makeText(context.getApplicationContext(), "Restarting " + appName + "...", Toast.LENGTH_SHORT).show());

        executor.execute(() -> {
            boolean stoppedWithRoot = false;

            // 1. Send broadcast to self-kill if hooked process is active
            try {
                Intent restartBroadcast = new Intent("com.waenhancer.WHATSAPP.RESTART");
                restartBroadcast.putExtra("PKG", packageName);
                restartBroadcast.setPackage(packageName);
                context.sendBroadcast(restartBroadcast);
            } catch (Throwable ignored) {}

            // 2. Perform root force-stop and launch via monkey
            String rootRes = runRootCommand("am force-stop " + packageName + " && sleep 0.4 && monkey -p " + packageName + " -c android.intent.category.LAUNCHER 1");
            if (rootRes != null && !rootRes.isEmpty() && !rootRes.contains("not found")) {
                stoppedWithRoot = true;
            }

            // 3. If root failed or non-root fallback, launch via PackageManager Intent
            if (!stoppedWithRoot) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {}

                mainHandler.post(() -> {
                    try {
                        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            context.startActivity(launchIntent);
                        } else {
                            Intent settingsIntent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            settingsIntent.setData(Uri.parse("package:" + packageName));
                            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(settingsIntent);
                        }
                    } catch (Throwable t) {
                        try {
                            Intent settingsIntent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            settingsIntent.setData(Uri.parse("package:" + packageName));
                            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(settingsIntent);
                        } catch (Throwable ignored) {}
                    }
                });
            }
        });
    }
}
