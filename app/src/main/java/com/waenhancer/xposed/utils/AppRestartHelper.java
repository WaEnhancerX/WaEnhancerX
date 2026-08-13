package com.waenhancer.xposed.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class AppRestartHelper {

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private AppRestartHelper() {}

    public static boolean hasRootAccess() {
        String output = runRootCommand("id");
        return output != null && (output.contains("uid=0") || output.contains("root"));
    }

    public static String runRootCommand(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            boolean finished = process.waitFor(4, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            return output.toString().trim();
        } catch (Exception ignored) {
            return null;
        } finally {
            if (process != null) process.destroy();
        }
    }

    /**
     * Force stops the given target package via root (or fallback) and then restarts it.
     */
    public static void restartPackage(Context context, String packageName, String appName) {
        executor.execute(() -> {
            boolean stopped = false;
            // 1. Try Root force-stop
            String res = runRootCommand("am force-stop " + packageName);
            if (res != null) {
                stopped = true;
            }

            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {}

            // 2. Launch the package afresh
            try {
                Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    context.startActivity(launchIntent);
                } else if (!stopped) {
                    // Fallback to app details settings if cannot launch or root stop
                    Intent settingsIntent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    settingsIntent.setData(Uri.parse("package:" + packageName));
                    settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(settingsIntent);
                }
            } catch (Exception e) {
                // Fallback to app settings
                try {
                    Intent settingsIntent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    settingsIntent.setData(Uri.parse("package:" + packageName));
                    settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(settingsIntent);
                } catch (Exception ignored) {}
            }
        });
    }
}
