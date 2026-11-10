package com.waenhancer.xposed.features.customization;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.waenhancer.licensing.customprops.CustomAbPropsRuntime;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XCallback;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;

/** Central, type-safe registry for WhatsApp MobileConfig/AB property overrides. */
public final class PropsOverrideHook extends BaseFeature {
    private static final String TAG = "[WAEX][PropsOverride]";
    private static boolean hookInstalled;

    public static final ConcurrentHashMap<Integer, Boolean> BOOLEANS = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<Integer, Integer> INTEGERS = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<Integer, Float> FLOATS = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<Integer, String> STRINGS = new ConcurrentHashMap<>();

    public static boolean isHookInstalled() {
        return hookInstalled;
    }

    public PropsOverrideHook(@NonNull Context context, @NonNull ClassLoader classLoader,
                             @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull @Override public String getName() { return "Props Override"; }
    @NonNull @Override public String getCategory() { return "Core"; }

    @Override
    public void hook() {
        // IGStatus is native-only. WhatsApp's own Chats stories row is controlled
        // by this MobileConfig flag; no module-owned header is injected anymore.
        boolean igStatus = prefs.getBoolean("igstatus", false);
        boolean nativeIgStatus = prefs.getBoolean("native_igstatus", true);
        boolean oldStatus = prefs.getBoolean("oldstatus", false);
        setIntegerOverride(3877, true, oldStatus ? (igStatus ? 2 : 0) : 2);
        setBooleanOverride(25327, igStatus && nativeIgStatus, true);
        synchronized (PropsOverrideHook.class) {
            if (hookInstalled) return;
            Method booleanGetter = findFallbackGetter("waex_props_boolean_method", "Unknown BooleanField");
            Method integerGetter = findFallbackGetter("waex_props_integer_method", "Unknown IntField");
            Method floatGetter = findFallbackGetter("waex_props_float_method", "Unknown FloatField");
            Method stringGetter = findFallbackGetter("waex_props_string_method", "Unknown StringField");
            Method resolver = findCentralResolver(booleanGetter);
            if (resolver != null) {
                hookInstalled = hookPropertyMethod(resolver);
                if (hookInstalled) XposedBridge.log(TAG + " Installed central resolver: "
                        + resolver.getDeclaringClass().getName() + "#" + resolver.getName());
                return;
            }
            XposedBridge.log(TAG + " Central resolver unavailable; installing fallback getters");
            boolean hooked = hookPropertyMethod(booleanGetter);
            hooked |= hookPropertyMethod(integerGetter);
            hooked |= hookPropertyMethod(floatGetter);
            hooked |= hookPropertyMethod(stringGetter);
            hookInstalled = hooked;
        }
    }

    /** Applies a declarative early MobileConfig boolean override from the feature registry. */
    public static void setBooleanOverride(int id, boolean enabled, boolean value) {
        if (enabled) {
            BOOLEANS.put(id, value);
        } else {
            BOOLEANS.remove(id);
        }
    }

    /** Applies a declarative early MobileConfig integer override from the feature registry. */
    public static void setIntegerOverride(int id, boolean enabled, int value) {
        if (enabled) {
            INTEGERS.put(id, value);
        } else {
            INTEGERS.remove(id);
        }
    }

    private Method findFallbackGetter(String cacheKey, String anchor) {
        try {
            return DexSearchEngine.getInstance().findMethodWithCache(
                    context, classLoader, cacheKey, (bridge, loader) -> {
                        MethodData method = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create().addUsingString(
                                        anchor, StringMatchType.Contains))).firstOrNull();
                        return method != null && method.isMethod()
                                ? method.getMethodInstance(loader) : null;
                    });
        } catch (Throwable error) {
            XposedBridge.log(TAG + " Getter unavailable for " + anchor + ": "
                    + error.getClass().getSimpleName());
            return null;
        }
    }

    private static Method findCentralResolver(Method fallbackGetter) {
        if (fallbackGetter == null) return null;
        Method selected = null;
        Class<?> propsClass = fallbackGetter.getDeclaringClass();
        for (Method candidate : propsClass.getDeclaredMethods()) {
            Class<?>[] types = candidate.getParameterTypes();
            if (!Modifier.isStatic(candidate.getModifiers())
                    || candidate.getReturnType() != Object.class
                    || types.length < 4
                    || types[1] != propsClass
                    || types[types.length - 2] != int.class
                    || types[types.length - 1] != boolean.class) continue;
            if (selected != null) return null; // Ambiguous host contract: use safe fallbacks.
            selected = candidate;
        }
        return selected;
    }

    private static boolean hookPropertyMethod(Method method) {
        if (method == null) return false;
        final int idIndex = lastPrimitiveIntParameter(method);
        if (idIndex < 0) return false;
        XposedBridge.hookMethod(method, new XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                if (param.args == null || idIndex >= param.args.length
                        || !(param.args[idIndex] instanceof Integer)) return;
                Object override = resolveOverride((Integer) param.args[idIndex], param.getResult());
                int propertyId = (Integer) param.args[idIndex];
                if (CustomAbPropsRuntime.shouldLogObservation(propertyId)) {
                    Object original = param.getResult();
                    XposedBridge.log(TAG + " Observed custom id=" + propertyId
                            + " originalType=" + (original == null ? "null" : original.getClass().getName())
                            + " overrideType=" + (override == null ? "none" : override.getClass().getName()));
                }
                if (override != null) param.setResult(override);
            }
        });
        return true;
    }

    private static int lastPrimitiveIntParameter(Method method) {
        Class<?>[] types = method.getParameterTypes();
        for (int index = types.length - 1; index >= 0; index--) {
            if (types[index] == int.class) return index;
        }
        return -1;
    }

    static Object resolveOverride(int id, Object original) {
        if (original instanceof Boolean) return BOOLEANS.get(id);
        if (original instanceof Integer) return INTEGERS.get(id);
        if (original instanceof Float) return FLOATS.get(id);
        if (original instanceof String) return STRINGS.get(id);
        if (original != null) return null;
        Object value = BOOLEANS.get(id);
        if (value == null) value = INTEGERS.get(id);
        if (value == null) value = FLOATS.get(id);
        if (value == null) value = STRINGS.get(id);
        return value;
    }
}
