package com.waenhancer.app;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

/** Opens system settings only. Android and the user remain responsible for granting access. */
public final class OverlayPermissionLauncher {
    private OverlayPermissionLauncher() {}

    public interface Launcher {
        void launch(Intent intent);
    }

    public static boolean openOverlay(String packageName, Launcher launcher) {
        if (tryLaunch(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + packageName)), launcher)) return true;
        return openAppInfo(packageName, launcher);
    }

    public static boolean openAppInfo(String packageName, Launcher launcher) {
        return tryLaunch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + packageName)), launcher);
    }

    private static boolean tryLaunch(Intent intent, Launcher launcher) {
        try {
            launcher.launch(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            return false;
        }
    }
}
