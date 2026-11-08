package com.waenhancer.xposed.features.customization;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Locale;

public class CustomTimeHook extends BaseFeature {

    private static final String TAG = "[WAEX][CustomTime]";

    public CustomTimeHook(@NonNull Context context,
                          @NonNull ClassLoader classLoader,
                          @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull
    @Override
    public String getName() {
        return "Custom Timestamp";
    }

    @NonNull
    @Override
    public String getCategory() {
        return "Customization";
    }

    @Override
    public void hook() throws Throwable {
        boolean secondsToTime = prefs.getBoolean("segundos", false);
        if (!secondsToTime) {
            return;
        }

        DexSearchEngine engine = DexSearchEngine.getInstance();
        engine.initialize(context);

        try {
            Method timeMethod = engine.findMethodWithCache(
                    context, classLoader, "waex_time_to_seconds_method",
                    (bridge, loader) -> {
                        MethodData md = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .usingNumbers(223, 224)
                                        .modifiers(Modifier.STATIC)
                                        .returnType("java.lang.String")
                                        .paramCount(2)
                                        .paramTypes(null, "java.util.Calendar"))
                        ).firstOrNull();
                        return md != null && md.isMethod() ? md.getMethodInstance(loader) : null;
                    }
            );

            if (timeMethod != null) {
                XposedBridge.hookMethod(timeMethod, new XC_MethodHook() {
                    private String cachedPattern = null;
                    private DateTimeFormatter cachedFormatter = null;

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length > 1 && param.args[1] instanceof Calendar) {
                            Calendar calendar = (Calendar) param.args[1];
                            String formatChoice = prefs.getString("timestamp_format", "HH:mm a");
                            String pattern;
                            if ("Custom Pattern".equals(formatChoice)) {
                                pattern = prefs.getString("timestamp_custom_pattern", "HH:mm a");
                                if (pattern == null || pattern.trim().isEmpty()) {
                                    pattern = "HH:mm a";
                                }
                            } else {
                                pattern = formatChoice != null && !formatChoice.trim().isEmpty() ? formatChoice : "HH:mm a";
                            }

                            DateTimeFormatter formatter = cachedFormatter;
                            if (formatter == null || !pattern.equals(cachedPattern)) {
                                try {
                                    formatter = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
                                            .withZone(ZoneId.systemDefault());
                                } catch (Throwable t) {
                                    formatter = DateTimeFormatter.ofPattern("HH:mm a", Locale.getDefault())
                                            .withZone(ZoneId.systemDefault());
                                }
                                cachedPattern = pattern;
                                cachedFormatter = formatter;
                            }

                            String timeStr = formatter.format(Instant.ofEpochMilli(calendar.getTimeInMillis()));
                            String template = prefs.getString("timestamp_custom_text", "${t}");
                            if (template == null || template.isEmpty()) {
                                template = "${t}";
                            }
                            String result;
                            if (template.contains("${t}")) {
                                result = template.replace("${t}", timeStr);
                            } else {
                                result = template;
                            }
                            param.setResult(result);
                        }
                    }
                });
            } else {
                XposedBridge.log(TAG + " Could not find timeToSeconds method via DexKit");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Failed to hook timestamp formatting: " + t.getMessage());
        }
    }
}
