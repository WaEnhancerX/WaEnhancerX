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
import org.luckypray.dexkit.result.MethodData;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Media Quality & Size Bypass Hook:
 * Applies bounded image-quality configuration and optional verified host predicates.
 * A configured hint does not establish lossless output, resolution or actual FPS.
 */
public class MediaQualityBypassHook extends BaseFeature {

    private static final String TAG = "[WAEX][MediaQuality]";
    private static final int IMAGE_MAX_KB = 50 * 1024;
    private static final int IMAGE_MAX_EDGE = 6000;
    private static final Pattern PROCESS_IMAGE_FORMAT = Pattern.compile(
            "^ProcessImageQuality[(]maxKb=(-?[0-9]+),[ ]*quality=(-?[0-9]+),[ ]*maxEdge=(-?[0-9]+)[)]$");
    private final Set<Method> installedMethods = new LinkedHashSet<>();
    private final Set<Class<?>> installedConstructors = new LinkedHashSet<>();

    private static final int[] IMAGE_MAX_SIZE_PROPS = {1577, 6030, 2656, 15752, 15746};
    private static final int[] IMAGE_QUALITY_PROPS = {1581, 1575, 1578, 6029, 2655, 15749};
    private static final int[] IMAGE_MAX_EDGE_PROPS = {1576, 2654, 6032, 15748, 3068};

    public MediaQualityBypassHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public synchronized void hook() throws Throwable {
        // The profile is bound to the complete inspected base APK, not just a
        // version label or opcode list. This does not alter shared AB preferences.
        if (!MediaHostFingerprint.accepts(context)) {
            if (isImageQualityMaxEnabled() || isVideo60FpsEnabled()) {
                throw unsupportedHost();
            }
            return;
        }
        // Keep the current AB-property path and the exact constructor route.
        // Optional legacy candidates must never replace unknown host arguments.
        hookProcessImageQuality();
        hookImageQuality();
        hookVideoQuality();
        hookQualitySelector();
        if (installedMethods.isEmpty() && installedConstructors.isEmpty()
                && (isImageQualityMaxEnabled() || isVideo60FpsEnabled())) {
            throw new IllegalStateException("Media quality contract unavailable");
        }
    }

    private static IllegalStateException unsupportedHost() {
        return new IllegalStateException("Media quality contract unavailable");
    }

    private void hookProcessImageQuality() {
        try {
            Class<?> qualityClass = DexSearchEngine.getInstance().findClassWithCache(
                    context,
                    classLoader,
                    "waex_process_image_quality_exact_v3",
                    (bridge, loader) -> {
                        Class<?> unique = null;
                        for (ClassData data : bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create().addUsingString(
                                        "ProcessImageQuality(maxKb=", StringMatchType.StartsWith)))) {
                            Class<?> current = data.getInstance(loader);
                            if (current == null || current.isInterface()
                                    || Modifier.isAbstract(current.getModifiers())) continue;
                            if (unique != null && unique != current) return null;
                            unique = current;
                        }
                        return unique;
                    });
            if (qualityClass == null) {
                XposedBridge.log(TAG + " ProcessImageQuality class not found");
                return;
            }

            List<Field> fields = ProcessImageQualityContract.resolve(
                    DexSearchEngine.getInstance().getBridge(), qualityClass, classLoader);
            if (fields == null || fields.size() != 3) {
                XposedBridge.log(TAG + " ProcessImageQuality value-object contract unavailable");
                return;
            }
            // Candidates are deliberately unordered. Labels are associated with
            // unique field values independently for each completed construction.
            Field maxKb = fields.get(0), quality = fields.get(1), maxEdge = fields.get(2);
            if (installedConstructors.contains(qualityClass)) return;
            maxKb.setAccessible(true);
            quality.setAccessible(true);
            maxEdge.setAccessible(true);
            Set<?> handles = XposedBridge.hookAllConstructors(qualityClass,
                    createProcessImageCallback(qualityClass, maxKb, quality, maxEdge));
            if (handles != null && !handles.isEmpty()) {
                installedConstructors.add(qualityClass);
                XposedBridge.log(TAG + " ProcessImageQuality callback installed; per-instance validation required");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            XposedBridge.log(TAG + " ProcessImageQuality unavailable: " + error.getClass().getSimpleName());
        }
    }

    /** Callback factory is package-private so the exact production path is executable in QA. */
    XC_MethodHook createProcessImageCallback(Class<?> qualityClass, Field maxKb,
                                             Field quality, Field maxEdge) {
        final AtomicBoolean warned = new AtomicBoolean(false);
        final AtomicBoolean writeFailed = new AtomicBoolean(false);
        return new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!isImageQualityMaxEnabled() || param.hasThrowable()
                            || writeFailed.get() || param.thisObject == null
                            || param.thisObject.getClass() != qualityClass) return;
                    Object instance = param.thisObject;
                    Field[] resolved = resolveImageFields(instance, maxKb, quality, maxEdge);
                    if (resolved == null) {
                        // No semantic field mapping: preserve the constructor's original values.
                        if (warned.compareAndSet(false, true)) {
                            XposedBridge.log(TAG + " ProcessImageQuality field identity unconfirmed");
                        }
                        return;
                    }
                    Field actualKb = resolved[0], actualQuality = resolved[1], actualEdge = resolved[2];
                    int[] effective = effectiveImageParameters();
                    if (effective == null) return;
                    try {
                        int oldKb = actualKb.getInt(instance);
                        int oldQuality = actualQuality.getInt(instance);
                        int oldEdge = actualEdge.getInt(instance);
                        try {
                            // Use the final property map, after user Custom A/B overrides.
                            // Never silently overwrite explicit custom values with defaults.
                            actualKb.setInt(instance, Math.max(oldKb, effective[0]));
                            actualQuality.setInt(instance, effective[1]);
                            actualEdge.setInt(instance, Math.max(oldEdge, effective[2]));
                        } catch (ReflectiveOperationException | RuntimeException unavailable) {
                            writeFailed.set(true);
                            // Stop future writes on this route. Rollback is best effort,
                            // never claim an atomic update when any field cannot be restored.
                            boolean restored = restoreField(actualKb, instance, oldKb);
                            restored &= restoreField(actualQuality, instance, oldQuality);
                            restored &= restoreField(actualEdge, instance, oldEdge);
                            XposedBridge.log(restored
                                    ? TAG + " ProcessImageQuality write failed; route disabled"
                                    : TAG + " ProcessImageQuality rollback incomplete; route disabled");
                        }
                    } catch (ReflectiveOperationException | RuntimeException unavailable) {
                        if (warned.compareAndSet(false, true)) {
                            XposedBridge.log(TAG + " ProcessImageQuality field read unavailable");
                        }
                    }
                }
        };
    }

    /** Best effort only; false must never be reported as a completed rollback. */
    static boolean restoreField(Field field, Object instance, int original) {
        try {
            field.setInt(instance, original);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Do not suppress the host result or report private reflective values.
            return false;
        }
    }

    /**
     * Preserve the existing MobileConfig precedence: explicit user Custom A/B
     * properties are written after the built-in quality defaults.
     *
     * The 2477/2475/2476 property family is an observed alternative host route;
     * when explicitly configured it is not safe to apply the 1577/1578/1576
     * constructor profile without proving which provider built this instance.
     */
    static int[] effectiveImageParameters() {
        // A constructor has no property-ID provenance. Do not guess which custom
        // route produced it or force one ID family's value onto another family.
        // Explicit non-default or missing configuration disables this extra write;
        // the independent property dispatcher remains responsible for its values.
        if (PropsOverrideHook.INTEGERS.containsKey(2477)
                || PropsOverrideHook.INTEGERS.containsKey(2475)
                || PropsOverrideHook.INTEGERS.containsKey(2476)) return null;
        for (int id : IMAGE_MAX_SIZE_PROPS) {
            if (!Integer.valueOf(IMAGE_MAX_KB).equals(PropsOverrideHook.INTEGERS.get(id))) return null;
        }
        for (int id : IMAGE_QUALITY_PROPS) {
            if (!Integer.valueOf(100).equals(PropsOverrideHook.INTEGERS.get(id))) return null;
        }
        for (int id : IMAGE_MAX_EDGE_PROPS) {
            if (!Integer.valueOf(IMAGE_MAX_EDGE).equals(PropsOverrideHook.INTEGERS.get(id))) return null;
        }
        if (!Boolean.TRUE.equals(PropsOverrideHook.BOOLEANS.get(6033))
                || !Boolean.FALSE.equals(PropsOverrideHook.BOOLEANS.get(9569))
                || !Boolean.TRUE.equals(PropsOverrideHook.BOOLEANS.get(26289))
                || !Boolean.TRUE.equals(PropsOverrideHook.BOOLEANS.get(22375))
                || !Boolean.FALSE.equals(PropsOverrideHook.BOOLEANS.get(14447))) return null;
        return new int[]{IMAGE_MAX_KB, 100, IMAGE_MAX_EDGE};
    }

    /** Field names are not inferred from positional integers or reflection order. */
    static boolean distinctPrimitiveIntFields(Field first, Field second, Field third) {
        if (first == null || second == null || third == null
                || first.equals(second) || first.equals(third) || second.equals(third)
                || first.getDeclaringClass() != second.getDeclaringClass()
                || first.getDeclaringClass() != third.getDeclaringClass()) return false;
        for (Field field : new Field[]{first, second, third}) {
            if (field.getType() != int.class || Modifier.isStatic(field.getModifiers())
                    || field.isSynthetic()) return false;
        }
        return true;
    }

    /**
     * Validate the field-to-label mapping on a real constructed value before writing.
     * DexKit's using-string and using-field collections do not prove positional identity.
     * Equal values are ambiguous and intentionally left untouched.
     */
    static boolean verifiedImageFieldValues(Object value, Field maxKb, Field quality, Field maxEdge) {
        Field[] resolved = resolveImageFields(value, maxKb, quality, maxEdge);
        return resolved != null && resolved[0].equals(maxKb)
                && resolved[1].equals(quality) && resolved[2].equals(maxEdge);
    }

    /** Resolve semantic labels from unique values, never relative metadata-list order. */
    static Field[] resolveImageFields(Object instance, Field first, Field second, Field third) {
        if (instance == null || !distinctPrimitiveIntFields(first, second, third)
                || instance.getClass() != first.getDeclaringClass()) return null;
        try {
            String rendered = instance.toString();
            if (rendered == null || rendered.length() > 192) return null;
            Matcher matcher = PROCESS_IMAGE_FORMAT.matcher(rendered);
            if (!matcher.matches()) return null;
            int[] expected = {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))};
            if (expected[0] <= 0 || expected[2] <= 0 || expected[1] <= 0 || expected[1] > 100
                    || expected[0] == expected[1] || expected[0] == expected[2] || expected[1] == expected[2]) return null;
            Field[] candidates = {first, second, third};
            int[] observed = {first.getInt(instance), second.getInt(instance), third.getInt(instance)};
            Field[] result = new Field[3];
            for (int label = 0; label < 3; label++) {
                for (int field = 0; field < 3; field++) {
                    if (observed[field] != expected[label]) continue;
                    if (result[label] != null) return null;
                    result[label] = candidates[field];
                }
                if (result[label] == null) return null;
            }
            return distinctPrimitiveIntFields(result[0], result[1], result[2]) ? result : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError | AssertionError unavailable) {
            return null;
        }
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

    /**
     * Accept only one concrete method per cache generation. Never choose the first
     * match from obfuscated methods and never mutate unrelated integer parameters.
     */
    private Method uniqueMethod(String key, String anchor, StringMatchType match,
                                Predicate<Method> accepts) {
        Method resolved = DexSearchEngine.getInstance().findMethodWithCache(context, classLoader, key,
                (bridge, loader) -> {
                    Method unique = null;
                    for (MethodData data : bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create().addUsingString(anchor, match)))) {
                        if (!data.isMethod()) continue;
                        Method candidate = data.getMethodInstance(loader);
                        if (!concreteInstance(candidate) || !accepts.test(candidate)) continue;
                        if (unique != null && !unique.equals(candidate)) return null;
                        unique = candidate;
                    }
                    return unique;
                });
        // A persisted descriptor must not bypass the current shape checks.
        return concreteInstance(resolved) && accepts.test(resolved) ? resolved : null;
    }

    private void hookImageQuality() {
        try {
            Method method = uniqueMethod("waex_image_quality_semantic_v3",
                    "image/compress quality", StringMatchType.Contains,
                    candidate -> candidate.getReturnType() == void.class
                            && candidate.getParameterCount() == 1
                            && candidate.getParameterTypes()[0] == int.class);
            if (!concreteInstance(method) || installedMethods.contains(method)) return;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isImageQualityMaxEnabled() || effectiveImageParameters() == null
                            || param.args == null || param.args.length != 1 || !(param.args[0] instanceof Integer)) return;
                    int quality = (Integer) param.args[0];
                    // 0 and negatives can be caller-specific sentinel values, not percentages.
                    if (quality >= 1 && quality <= 100) param.args[0] = 100;
                }
            });
            installedMethods.add(method);
        } catch (RuntimeException | LinkageError unavailable) {
            XposedBridge.log(TAG + " Image quality contract unavailable: "
                    + unavailable.getClass().getSimpleName());
        }
    }

    private void hookVideoQuality() {
        try {
            Method method = uniqueMethod("waex_video_fps_encoder_v3",
                    "video/encoder fps", StringMatchType.Contains,
                    candidate -> candidate.getParameterCount() == 0
                            && HookReturnTypeGuard.returnsInteger(candidate));
            if (!concreteInstance(method) || installedMethods.contains(method)) return;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!isVideo60FpsEnabled() || param.hasThrowable()
                            || !(param.getResult() instanceof Integer)) return;
                    int original = (Integer) param.getResult();
                    if (original > 0 && original < 60) param.setResult(60);
                }
            });
            installedMethods.add(method);
        } catch (RuntimeException | LinkageError unavailable) {
            XposedBridge.log(TAG + " Video FPS contract unavailable: "
                    + unavailable.getClass().getSimpleName());
        }
    }

    private void hookQualitySelector() {
        try {
            Method method = MediaQualitySelectorContract.resolve(
                    DexSearchEngine.getInstance().getBridge(), classLoader);
            if (method == null || installedMethods.contains(method)) return;
            final Class<?> owner = method.getDeclaringClass();
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || !(param.getResult() instanceof Boolean)
                            || param.args == null || param.args.length != 3
                            || !owner.isInstance(param.args[0])
                            || !MediaQualitySelectorContract.FLAG.equals(param.args[1])
                            || !(param.args[2] instanceof Boolean)) return;
                    // This changes only the requested presentation flag. The
                    // enclosing gallery predicate still evaluates every later guard.
                    if (isImageQualityMaxEnabled() || isVideo60FpsEnabled()) param.setResult(true);
                }
            });
            installedMethods.add(method);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            XposedBridge.log(TAG + " HD flag-reader contract unavailable: "
                    + unavailable.getClass().getSimpleName());
        }
    }

    private static boolean concreteInstance(Method method) {
        if (method == null) return false;
        int flags = method.getModifiers();
        return !Modifier.isAbstract(flags) && !Modifier.isNative(flags)
                && !Modifier.isStatic(flags) && !method.isBridge() && !method.isSynthetic();
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
