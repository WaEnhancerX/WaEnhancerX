package com.waenhancer.utils;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.StatFs;

import com.waenhancer.config.PreferenceStores;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Reads selected OS and WAEX facts once, on the explicit export action only. */
public final class DiagnosticsEnvironment {
    private DiagnosticsEnvironment() {}

    public static DiagnosticsSnapshot capture(Context context) {
        DiagnosticsSnapshot result = new DiagnosticsSnapshot();
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown";
        result.addDevice(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SECURITY_PATCH, abi);

        long total = -1, available = -1, storage = -1;
        int battery = -1;
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                manager.getMemoryInfo(info);
                total = info.totalMem;
                available = info.availMem;
            }
        } catch (RuntimeException ignored) {}
        try {
            storage = new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes();
        } catch (RuntimeException ignored) {}
        try {
            BatteryManager manager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (manager != null) battery = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (RuntimeException ignored) {}
        Runtime runtime = Runtime.getRuntime();
        long heap = runtime.totalMemory() - runtime.freeMemory();
        result.addResources(total, available, heap, storage, battery);

        try {
            SharedPreferences preferences = PreferenceStores.publicStore(context);
            String framework = "unknown";
            int api = -1;
            try {
                framework = preferences.getString("active_xposed_framework", "unknown");
            } catch (ClassCastException ignored) {}
            try {
                api = preferences.getInt("active_xposed_api_version", -1);
            } catch (ClassCastException ignored) {}
            result.addRuntime(framework, api);
            for (Map.Entry<String, ?> setting : preferences.getAll().entrySet()) {
                if (!(setting.getValue() instanceof Boolean)) continue;
                String key = setting.getKey();
                if (key == null) continue;
                boolean targetScoped = false;
                int colon = key.indexOf(':');
                if (colon > 0) {
                    if (!DeclaredWhatsAppTargets.contains(key.substring(0, colon))) continue;
                    key = key.substring(colon + 1);
                    targetScoped = true;
                }
                result.addPreference(key, (Boolean) setting.getValue(), targetScoped);
            }

            Set<String> targets = new LinkedHashSet<>();
            targets.add("com.whatsapp");
            targets.add("com.whatsapp.w4b");
            Set<String> reported = null;
            try {
                reported = preferences.getStringSet("hooked_whatsapp_packages", null);
            } catch (ClassCastException ignored) {}
            if (reported != null) {
                for (String name : reported) {
                    if (DeclaredWhatsAppTargets.contains(name) && targets.size() < 32) {
                        targets.add(name);
                    }
                }
            }
            PackageManager pm = context.getPackageManager();
            for (String target : targets) {
                try {
                    PackageInfo info = pm.getPackageInfo(target, 0);
                    long versionCode = Build.VERSION.SDK_INT >= 28
                            ? info.getLongVersionCode() : info.versionCode;
                    result.addTargetVersion(target, info.versionName, versionCode);
                } catch (PackageManager.NameNotFoundException | SecurityException ignored) {
                    // A target missing or invisible to this Android user is not installed evidence.
                }
            }
        } catch (RuntimeException ignored) {
            // An inaccessible or corrupt preference store must not prevent exporting events.
        }

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (manager != null) {
                    long now = System.currentTimeMillis();
                    for (ApplicationExitInfo exit :
                            manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 16)) {
                        result.addExit(now, exit.getTimestamp(), exit.getReason(), exit.getStatus(),
                                exit.getPss(), exit.getRss());
                    }
                }
            } catch (RuntimeException ignored) {}
        }
        return result;
    }
}
