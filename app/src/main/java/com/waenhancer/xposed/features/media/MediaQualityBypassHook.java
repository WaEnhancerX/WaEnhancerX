package com.waenhancer.xposed.features.media;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.waenhancer.xposed.core.devkit.HookReturnTypeGuard;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Enables supported media quality controls and adjusts JPEG quality/FPS hints.
 * Matching a quality hint does not establish resolution, bitrate, lossless media,
 * or an upload-size limit. Unknown/ambiguous host contracts remain unchanged.
 */
public class MediaQualityBypassHook extends BaseFeature {
    private static final String TAG = "[WAEX][MediaQuality]";
    private final Set<Method> installed = new LinkedHashSet<>();

    public MediaQualityBypassHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public synchronized void hook() throws Throwable {
        // Install independently of the snapshot value: callbacks support live preference changes.
        hookImageQuality();
        hookVideoQuality();
        hookQualitySelector("enable_media_quality_tool", "wpp_hd_quality_tool_v1");
        hookQualitySelector("show_media_quality_toggle", "wpp_hd_quality_toggle_v1");
        if (installed.isEmpty() && (isImageQualityMaxEnabled() || isVideo60FpsEnabled())) {
            throw new IllegalStateException("Media quality contract unavailable");
        }
    }

    private Method uniqueMethod(String key, String anchor, StringMatchType match, Predicate<Method> accepts) {
        return DexSearchEngine.getInstance().findMethodWithCache(context, classLoader, key, (bridge, loader) -> {
            Method result = null;
            for (MethodData data : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .addUsingString(anchor, match)))) {
                if (!data.isMethod()) continue;
                Method candidate = data.getMethodInstance(loader);
                if (!isConcrete(candidate) || !accepts.test(candidate)) continue;
                if (result != null && !result.equals(candidate)) return null;
                result = candidate;
            }
            return result;
        });
    }

    private void hookImageQuality() {
        try {
            // New cache generation rejects signatures chosen by the old first-result query.
            Method method = uniqueMethod("wpp_image_compress_quality_v2", "image/compress quality",
                    StringMatchType.Contains, HookReturnTypeGuard::firstParameterIsInteger);
            if (!isConcrete(method) || !HookReturnTypeGuard.firstParameterIsInteger(method)
                    || installed.contains(method)) return;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isImageQualityMaxEnabled() || param.args == null || param.args.length == 0
                            || !(param.args[0] instanceof Integer)) return;
                    int original = (Integer) param.args[0];
                    // Only a valid JPEG quality percentage; preserve host sentinel/unknown values.
                    if (original >= 0 && original <= 100) param.args[0] = 100;
                }
            });
            installed.add(method);
        } catch (RuntimeException | LinkageError failure) {
            XposedBridge.log(TAG + " Image quality contract unavailable: " + failure.getClass().getSimpleName());
        }
    }

    private void hookVideoQuality() {
        try {
            Method method = uniqueMethod("wpp_video_fps_encoder_v2", "video/encoder fps",
                    StringMatchType.Contains, HookReturnTypeGuard::returnsInteger);
            if (!isConcrete(method) || !HookReturnTypeGuard.returnsInteger(method)
                    || installed.contains(method)) return;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!isVideo60FpsEnabled() || param.hasThrowable() || !(param.getResult() instanceof Integer)) return;
                    int original = (Integer) param.getResult();
                    // Do not erase codec failures, convert null/sentinels to success, or reduce higher rates.
                    if (original > 0 && original < 60) param.setResult(60);
                }
            });
            installed.add(method);
        } catch (RuntimeException | LinkageError failure) {
            XposedBridge.log(TAG + " Video FPS contract unavailable: " + failure.getClass().getSimpleName());
        }
    }

    /**
     * Public host family with an explicitly named boolean HD-tool availability predicate.
     * This exposes the host's own editor control; it does not select a bitrate or promise HD output.
     */
    private void hookQualitySelector(String anchor, String key) {
        try {
            Method method = uniqueMethod(key, anchor, StringMatchType.Equals,
                    candidate -> candidate.getReturnType() == boolean.class);
            if (!isConcrete(method) || method.getReturnType() != boolean.class || installed.contains(method)) return;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || !(param.getResult() instanceof Boolean)) return;
                    if (isImageQualityMaxEnabled() || isVideo60FpsEnabled()) param.setResult(true);
                }
            });
            installed.add(method);
        } catch (RuntimeException | LinkageError failure) {
            XposedBridge.log(TAG + " HD selector contract unavailable: " + failure.getClass().getSimpleName());
        }
    }

    private static boolean isConcrete(Method method) {
        if (method == null) return false;
        int flags = method.getModifiers();
        return !Modifier.isAbstract(flags) && !Modifier.isNative(flags) && !method.isBridge() && !method.isSynthetic();
    }

    private boolean isImageQualityMaxEnabled() {
        return isEnabled("imagequality", false);
    }

    private boolean isVideo60FpsEnabled() {
        return isEnabled("video_maxfps", false);
    }

    @NonNull
    @Override
    public String getName() {
        return "Media Quality Bypass";
    }
}
