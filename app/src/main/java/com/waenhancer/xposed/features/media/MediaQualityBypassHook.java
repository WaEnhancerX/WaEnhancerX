package com.waenhancer.xposed.features.media;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.waenhancer.xposed.core.devkit.HookReturnTypeGuard;
import com.waenhancer.xposed.features.customization.PropsOverrideHook;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.UsingFieldData;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Media Quality & Size Bypass Hook:
 * Allows sending full-quality uncompressed HD images, 60fps videos, real video resolution,
 * and higher file size limits.
 */
public class MediaQualityBypassHook extends BaseFeature {

    private static final String TAG = "[WAEX][MediaQuality]";
    private static final int IMAGE_MAX_KB = 50 * 1024;
    private static final int IMAGE_MAX_EDGE = 6000;

    private static final int[] IMAGE_MAX_SIZE_PROPS = {1577, 6030, 2656, 15752, 15746};
    private static final int[] IMAGE_QUALITY_PROPS = {1581, 1575, 1578, 6029, 2655, 15749};
    private static final int[] IMAGE_MAX_EDGE_PROPS = {1576, 2654, 6032, 15748, 3068};

    public MediaQualityBypassHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        hookProcessImageQuality();
        hookImageQuality();
        hookVideoQuality();
    }

    private void hookProcessImageQuality() {
        if (!isImageQualityMaxEnabled()) return;
        try {
            Class<?> qualityClass = DexSearchEngine.getInstance().findClassWithCache(
                    context,
                    classLoader,
                    "waex_process_image_quality_class",
                    (bridge, loader) -> {
                        ClassData data = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create().addUsingString(
                                        "ProcessImageQuality(", StringMatchType.StartsWith)))
                                .firstOrNull();
                        return data != null ? data.getInstance(loader) : null;
                    });
            if (qualityClass == null) {
                XposedBridge.log(TAG + " ProcessImageQuality class not found");
                return;
            }

            Map<String, Field> fields = resolveToStringFields(qualityClass);
            Field maxKb = fields.get("maxKb");
            Field quality = fields.get("quality");
            Field maxEdge = fields.get("maxEdge");
            if (maxKb == null || quality == null || maxEdge == null) {
                XposedBridge.log(TAG + " ProcessImageQuality fields incomplete: " + fields.keySet());
                return;
            }
            maxKb.setAccessible(true);
            quality.setAccessible(true);
            maxEdge.setAccessible(true);
            XposedBridge.hookAllConstructors(qualityClass, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    maxKb.setInt(param.thisObject, IMAGE_MAX_KB);
                    quality.setInt(param.thisObject, 100);
                    maxEdge.setInt(param.thisObject, IMAGE_MAX_EDGE);
                }
            });
            XposedBridge.log(TAG + " Installed ProcessImageQuality constructor hook: "
                    + qualityClass.getName());
        } catch (Throwable error) {
            XposedBridge.log(TAG + " Error hooking ProcessImageQuality: " + error);
        }
    }

    private Map<String, Field> resolveToStringFields(Class<?> targetClass) throws Throwable {
        Method toStringMethod = targetClass.getDeclaredMethod("toString");
        MethodData methodData = DexSearchEngine.getInstance().getBridge()
                .getMethodData(toStringMethod);
        Map<String, Field> result = new HashMap<>();
        if (methodData == null) return result;
        List<String> strings = methodData.getUsingStrings();
        List<UsingFieldData> usingFields = methodData.getUsingFields();
        int fieldIndex = 0;
        for (String raw : strings) {
            if (fieldIndex >= usingFields.size()) break;
            String text = raw.trim();
            int equals = text.lastIndexOf('=');
            if (equals < 1) continue;
            int start = 0;
            for (int index = equals - 1; index >= 0; index--) {
                char character = text.charAt(index);
                if (character == '\'' || character == ',' || character == ' '
                        || character == '(' || character == ')' || character == ':'
                        || character == '{' || character == '}') {
                    start = index + 1;
                    break;
                }
            }
            if (start >= equals) continue;
            FieldData fieldData = usingFields.get(fieldIndex++).getField();
            result.put(text.substring(start, equals), fieldData.getFieldInstance(classLoader));
        }
        return result;
    }

    /**
     * Registers the complete image-quality configuration before WhatsApp starts caching
     * MobileConfig values. User-authored custom props are loaded afterwards and therefore
     * retain final priority.
     */
    public static void configurePropertyOverrides(boolean imageEnabled) {
        for (int id : IMAGE_MAX_SIZE_PROPS) {
            PropsOverrideHook.setIntegerOverride(id, imageEnabled, IMAGE_MAX_KB);
        }
        for (int id : IMAGE_QUALITY_PROPS) {
            PropsOverrideHook.setIntegerOverride(id, imageEnabled, 100);
        }
        for (int id : IMAGE_MAX_EDGE_PROPS) {
            PropsOverrideHook.setIntegerOverride(id, imageEnabled, IMAGE_MAX_EDGE);
        }
        PropsOverrideHook.setBooleanOverride(6033, imageEnabled, true);
        PropsOverrideHook.setBooleanOverride(9569, imageEnabled, false);
        PropsOverrideHook.setBooleanOverride(26289, imageEnabled, true);
        PropsOverrideHook.setBooleanOverride(22375, imageEnabled, true);
        // WhatsApp's manual quality calculation can replace the configured values.
        PropsOverrideHook.setBooleanOverride(14447, imageEnabled, false);
    }

    private void hookImageQuality() {
        try {
            Method imageQualityMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "waex_image_compress_quality",
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
                            // Obfuscation has moved the quality argument between releases. The
                            // matched method is specifically anchored by "image/compress quality",
                            // so normalize its percentage-like integer argument wherever it sits.
                            for (int index = 0; index < param.args.length; index++) {
                                Object argument = param.args[index];
                                if (argument instanceof Integer) {
                                    int value = (Integer) argument;
                                    if (value >= 1 && value <= 100) param.args[index] = 100;
                                }
                            }
                        }
                    }
                });
                XposedBridge.log(TAG + " Installed image encoder quality hook: "
                        + imageQualityMethod.getDeclaringClass().getName() + "#"
                        + imageQualityMethod.getName());
            } else {
                XposedBridge.log(TAG + " Image encoder quality method not found");
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
                    "waex_video_fps_encoder",
                    (bridge, loader) -> {
                        MethodData md = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("video/encoder fps", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                        return md != null ? md.getMethodInstance(loader) : null;
                    }
            );

            if (videoFpsMethod != null && HookReturnTypeGuard.returnsInteger(videoFpsMethod)) {
                XposedBridge.hookMethod(videoFpsMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (isVideo60FpsEnabled()) {
                            param.setResult(60); // Force 60 FPS output
                        }
                    }
                });
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
