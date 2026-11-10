package com.waenhancer.xposed.features.customization;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Customizes WhatsApp Stories / Status layout style via MobileConfig/AB properties
 * and direct method hooking.
 */
public final class StatusStyleHook extends BaseFeature {

    private static final String TAG = "[WAEX][StatusStyle]";

    // AB properties for status style and UI rendering
    private static final int PROP_STATUS_STYLE_A = 8522;
    private static final int PROP_STATUS_STYLE_B = 8521;
    private static final int PROP_STATUS_HEADER_STYLE = 9973;
    private static final int PROP_STATUS_ENABLED = 6285;

    public StatusStyleHook(@NonNull Context context,
                           @NonNull ClassLoader classLoader,
                           @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull
    @Override
    public String getName() {
        return "Stories & Status Style";
    }

    @NonNull
    @Override
    public String getCategory() {
        return "Customization";
    }

    @Override
    public void hook() throws Throwable {
        String styleStr;
        try {
            styleStr = prefs.getString("status_style", "1");
        } catch (ClassCastException ignored) {
            // A stale or imported value must not prevent the feature from loading.
            styleStr = null;
        }
        final int targetStyle = parseStyle(styleStr);

        // Register overrides in central PropsOverrideHook
        PropsOverrideHook.INTEGERS.put(PROP_STATUS_STYLE_A, targetStyle);
        PropsOverrideHook.INTEGERS.put(PROP_STATUS_STYLE_B, targetStyle);
        PropsOverrideHook.INTEGERS.put(PROP_STATUS_HEADER_STYLE, 1);
        PropsOverrideHook.BOOLEANS.put(PROP_STATUS_ENABLED, true);

        // Find and hook status style resolution method for direct calls
        try {
            Method statusStyleMethod = resolveStatusStyleMethod();
            if (statusStyleMethod != null) {
                XposedBridge.hookMethod(statusStyleMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable() || !(param.getResult() instanceof Integer)) return;
                        param.setResult(targetStyle);
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Status style direct method hook skipped or failed: " + t.getMessage());
        }
    }

    private static int parseStyle(String value) {
        if (value == null) return 1;
        try {
            int style = Integer.parseInt(value.trim());
            // Only values verified against current WhatsApp builds are published.
            // Obsolete restored values fail closed to the host default.
            switch (style) {
                case 0:
                case 1:
                case 3:
                case 9:
                    return style;
                default:
                    return 1;
            }
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private Method resolveStatusStyleMethod() {
        try {
            return DexSearchEngine.getInstance().findMethodWithCache(
                    context, classLoader, "waex_status_style_method", (bridge, loader) -> {
                        MethodData methodData = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingNumber(PROP_STATUS_STYLE_A)
                                        .returnType(int.class)
                                )).firstOrNull();
                        return methodData != null && methodData.isMethod()
                                ? methodData.getMethodInstance(loader) : null;
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Method lookup error: " + t.getMessage());
            return null;
        }
    }
}
