package com.waenhancer.xposed.features.media;

import android.content.Context;
import android.content.SharedPreferences;
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
 * Media Quality & Size Bypass Hook:
 * Allows sending full-quality uncompressed HD images, 60fps videos, real video resolution,
 * and higher file size limits.
 */
public class MediaQualityBypassHook extends BaseFeature {

    private static final String TAG = "[WAEX][MediaQuality]";

    public MediaQualityBypassHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        hookImageQuality();
        hookVideoQuality();
    }

    private void hookImageQuality() {
        try {
            Method imageQualityMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "wpp_image_compress_quality",
                    (bridge, loader) -> {
                        MethodData md = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("image/compress quality", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                        return md != null ? md.getMethodInstance(loader) : null;
                    }
            );

            if (imageQualityMethod != null) {
                XposedBridge.hookMethod(imageQualityMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isImageQualityMaxEnabled()) {
                            // If compression quality is passed as integer parameter, set to 100
                            if (param.args.length > 0 && param.args[0] instanceof Integer) {
                                param.args[0] = 100;
                            }
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked image compression quality: " + imageQualityMethod.getName());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking image quality: " + t.getMessage());
        }
    }

    private void hookVideoQuality() {
        try {
            Method videoFpsMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "wpp_video_fps_encoder",
                    (bridge, loader) -> {
                        MethodData md = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("video/encoder fps", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                        return md != null ? md.getMethodInstance(loader) : null;
                    }
            );

            if (videoFpsMethod != null && (videoFpsMethod.getReturnType() == int.class
                    || videoFpsMethod.getReturnType() == Integer.class)) {
                XposedBridge.hookMethod(videoFpsMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (isVideo60FpsEnabled()) {
                            param.setResult(60); // Force 60 FPS output
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked video encoder FPS: " + videoFpsMethod.getName());
            } else if (videoFpsMethod != null) {
                XposedBridge.log(TAG + " Skipped incompatible video FPS return type: " + videoFpsMethod);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking video quality: " + t.getMessage());
        }
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
