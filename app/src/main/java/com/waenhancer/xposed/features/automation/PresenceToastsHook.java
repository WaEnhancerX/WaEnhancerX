package com.waenhancer.xposed.features.automation;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import java.lang.reflect.Method;

/**
 * Presence & Activity Toasts Hook:
 * Displays real-time toast alerts when contacts come online, type, view status, or delete messages.
 */
public class PresenceToastsHook extends BaseFeature {

    private static final String TAG = "[WAEX][PresenceToasts]";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public PresenceToastsHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        hookIncomingPresence();
    }

    private void hookIncomingPresence() {
        try {
            Method presenceMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "wpp_presence_incoming_update",
                    (bridge, loader) -> {
                        MethodData md = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("PresenceManager/onPresenceReceived", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                        return md != null ? md.getMethodInstance(loader) : null;
                    }
            );

            if (presenceMethod != null) {
                XposedBridge.hookMethod(presenceMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (isOnlineToastEnabled()) {
                            showToastSafely("Contact is Online");
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked presence incoming update: " + presenceMethod.getName());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking presence toasts: " + t.getMessage());
        }
    }

    private void showToastSafely(String message) {
        mainHandler.post(() -> {
            try {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        });
    }

    private boolean isOnlineToastEnabled() {
        return isEnabled("showonline", false) || isEnabled("typing_online_toasts", false);
    }

    @NonNull
    @Override
    public String getName() {
        return "Presence Toasts";
    }
}
