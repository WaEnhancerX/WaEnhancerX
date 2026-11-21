package com.waenhancer.xposed.utils;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.NonNull;
import de.robv.android.xposed.XposedBridge;

/** Delegates restart to the external WAEX manager process so relaunch survives process death. */
public final class WhatsAppProcessRestarter {
    private WhatsAppProcessRestarter() {}

    public static void restart(@NonNull Activity activity) {
        try {
            String packageName = activity.getPackageName();
            Bundle request = new Bundle();
            request.putString("package", packageName);
            Bundle response = activity.getContentResolver().call(
                    Uri.parse("content://com.waenhancer.hookprovider"),
                    "restart_target", packageName, request);
            if (response == null || !response.getBoolean("accepted", false)) {
                throw new IllegalStateException("WAEX manager rejected restart request");
            }
        } catch (Throwable throwable) {
            XposedBridge.log("[WAEX] WhatsApp external restart request failed: "
                    + throwable.getMessage());
        }
    }
}
