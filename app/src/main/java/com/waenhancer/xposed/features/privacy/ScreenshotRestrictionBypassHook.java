package com.waenhancer.xposed.features.privacy;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.utils.ActivityTracker;

import de.robv.android.xposed.XC_MethodHook;

/** Prevents WhatsApp from applying FLAG_SECURE to any activity or dialog window. */
public final class ScreenshotRestrictionBypassHook extends BaseFeature {

    private static final int FLAG_SECURE = WindowManager.LayoutParams.FLAG_SECURE;

    public ScreenshotRestrictionBypassHook(@NonNull Context context,
                                           @NonNull ClassLoader classLoader,
                                           @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull
    @Override
    public String getName() {
        return "Bypass Screenshot Restriction";
    }

    @NonNull
    @Override
    public String getCategory() {
        return "Privacy";
    }

    @Override
    public void hook() {
        XposedBridge.hookAllMethods(Window.class, "addFlags", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && param.args[0] instanceof Integer) {
                    param.args[0] = ((Integer) param.args[0]) & ~FLAG_SECURE;
                }
            }
        });

        XposedBridge.hookAllMethods(Window.class, "setFlags", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 1
                        && param.args[0] instanceof Integer
                        && param.args[1] instanceof Integer) {
                    int mask = (Integer) param.args[1];
                    if ((mask & FLAG_SECURE) != 0) {
                        param.args[0] = ((Integer) param.args[0]) & ~FLAG_SECURE;
                    }
                }
            }
        });

        XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                clearSecureFlag((Activity) param.thisObject);
            }
        });

        clearSecureFlag(ActivityTracker.getCurrentActivity());
    }

    private static void clearSecureFlag(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        Window window = activity.getWindow();
        if (window != null) window.clearFlags(FLAG_SECURE);
    }
}
