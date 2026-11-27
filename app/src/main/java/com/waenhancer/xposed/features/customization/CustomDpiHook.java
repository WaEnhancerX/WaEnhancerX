package com.waenhancer.xposed.features.customization;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;
import androidx.annotation.NonNull;
import com.waenhancer.theme.ThemeHeader;
import com.waenhancer.xposed.core.BaseFeature;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Applies the configured display density to every newly visible WhatsApp Activity. */
public final class CustomDpiHook extends BaseFeature {
    public CustomDpiHook(@NonNull Context context, @NonNull ClassLoader classLoader,
                         @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull @Override public String getName() { return "Custom DPI"; }

    @Override public void hook() {
        int dpi = TypedStyleHookPolicy.parseDpi(prefs.getString("change_dpi", "0"));
        // Like WaEnhancer, an explicit setting wins and the theme header supplies the fallback.
        if (dpi == 0) dpi = TypedStyleHookPolicy.parseDpi(themeHeaderDpi());
        if (dpi == 0) return;
        final int targetDpi = dpi;
        XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof Activity) applyDpi((Activity) param.thisObject, targetDpi);
            }
        });
    }

    private String themeHeaderDpi() {
        String css = getStringPref("css_theme", null);
        if (css == null) css = getStringPref("custom_css", "");
        if (css == null || css.isEmpty()) return "0";
        String value = ThemeHeader.Companion.properties(css).get("change_dpi");
        return value != null ? value : "0";
    }

    private static void applyDpi(Activity activity, int dpi) {
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        metrics.density = dpi / 160f;
        metrics.densityDpi = dpi;
    }
}
