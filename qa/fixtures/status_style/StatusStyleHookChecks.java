package com.waenhancer.xposed.features.customization;

import android.content.Context;
import android.content.SharedPreferences;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Whole production hooks with explicit preference/cache/Xposed transport fixtures. */
public final class StatusStyleHookChecks {
    private static int checks, failures;
    private static final Context CONTEXT = new Context();
    private static final ClassLoader LOADER = StatusStyleHookChecks.class.getClassLoader();

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) failures++;
        System.out.println((condition ? "PASS: " : "FAIL: ") + label);
    }

    private static final class Preferences implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        @Override public String getString(String key, String fallback) {
            Object value = values.get(key);
            return value == null ? fallback : (String) value;
        }
        @Override public boolean getBoolean(String key, boolean fallback) {
            Object value = values.get(key);
            return value == null ? fallback : (Boolean) value;
        }
    }

    // Shapes match the declarations observed in the supplied WhatsApp APK;
    // names are intentionally unrelated to obfuscated host names.
    public static final class HostProps {
        public static boolean booleanGetter(Object metadata, HostProps owner, Boolean fallback, int id) {
            return false;
        }
        public static int integerGetter(Object metadata, HostProps owner, Integer fallback, int id) {
            return 1;
        }
        public static Object central(Object metadata, HostProps owner, Integer fallback,
                                     Object a, Object b, Object c, int id, boolean flag) {
            return null;
        }
    }
    public static final class HostStatus {
        public int style(boolean mode) { return 1; }
        public String incompatible(boolean mode) { return "host-value"; }
    }

    private static Method styleMethod() throws Exception {
        return HostStatus.class.getDeclaredMethod("style", boolean.class);
    }
    private static void reset() throws Exception {
        PropsOverrideHook.BOOLEANS.clear(); PropsOverrideHook.INTEGERS.clear();
        PropsOverrideHook.FLOATS.clear(); PropsOverrideHook.STRINGS.clear();
        XposedBridge.hooks.clear(); DexSearchEngine.methods.clear();
        DexSearchEngine.methods.put("waex_status_style_method", styleMethod());
    }
    private static XC_MethodHook.MethodHookParam after(Method method, Object result,
                                                       Throwable error, Object... arguments) throws Throwable {
        XC_MethodHook hook = XposedBridge.hooks.get(method);
        if (hook == null) throw new AssertionError("Expected installed callback: " + method);
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.method = method; param.args = arguments; param.thisObject = new HostStatus();
        if (error == null) param.setResult(result); else param.setThrowable(error);
        hook.runAfter(param);
        return param;
    }

    private static void styleValue(Object saved, int expected, String label) throws Throwable {
        reset(); Preferences prefs = new Preferences();
        if (saved != null) prefs.values.put("status_style", saved);
        try {
            new StatusStyleHook(CONTEXT, LOADER, prefs).hook();
            check(PropsOverrideHook.INTEGERS.get(8522).equals(expected), label + " property 8522");
            check(PropsOverrideHook.INTEGERS.get(8521).equals(expected), label + " property 8521");
            check(after(styleMethod(), 1, null, false).getResult().equals(expected), label + " direct callback");
            check(Boolean.TRUE.equals(PropsOverrideHook.BOOLEANS.get(6285))
                    && Integer.valueOf(1).equals(PropsOverrideHook.INTEGERS.get(9973)), label + " supporting flags");
        } catch (ClassCastException failure) {
            check(false, label + " preference type must not crash feature initialization");
        }
    }

    public static void main(String[] args) throws Throwable {
        for (String choice : new String[]{"0", "1", "3", "9"})
            styleValue(choice, Integer.parseInt(choice), "published choice " + choice);
        styleValue(" 3 ", 3, "trimmed published choice");
        for (Object invalid : new Object[]{null, "", "text", "2", "4", "5", "-1", "10", "2147483647", "2147483648", true, 5})
            styleValue(invalid, 1, "invalid/default " + String.valueOf(invalid));

        reset(); Preferences prefs = new Preferences(); prefs.values.put("status_style", "9");
        new StatusStyleHook(CONTEXT, LOADER, prefs).hook();
        Throwable original = new IllegalStateException("fixture host failure");
        XC_MethodHook.MethodHookParam failed = after(styleMethod(), null, original, false);
        check(failed.getThrowable() == original && failed.getResult() == null,
                "status callback preserves original host exception identity");
        check(after(styleMethod(), null, null, false).getResult() == null,
                "status callback retains absent result");

        reset(); Method wrong = HostStatus.class.getDeclaredMethod("incompatible", boolean.class);
        DexSearchEngine.methods.put("waex_status_style_method", wrong);
        new StatusStyleHook(CONTEXT, LOADER, prefs).hook();
        if (XposedBridge.hooks.containsKey(wrong))
            check("host-value".equals(after(wrong, "host-value", null, false).getResult()),
                    "status callback retains incompatible cached result type");
        else check(true, "incompatible cached method not installed");

        reset();
        DexSearchEngine.methods.put("waex_props_boolean_method", HostProps.class.getDeclaredMethod(
                "booleanGetter", Object.class, HostProps.class, Boolean.class, int.class));
        DexSearchEngine.methods.put("waex_props_integer_method", HostProps.class.getDeclaredMethod(
                "integerGetter", Object.class, HostProps.class, Integer.class, int.class));
        new PropsOverrideHook(CONTEXT, LOADER, prefs).hook();
        Method central = HostProps.class.getDeclaredMethod("central", Object.class, HostProps.class,
                Integer.class, Object.class, Object.class, Object.class, int.class, boolean.class);
        check(XposedBridge.hooks.size() == 1 && XposedBridge.hooks.containsKey(central),
                "exact observed central resolver shape selected");
        PropsOverrideHook.INTEGERS.put(8522, 5); PropsOverrideHook.BOOLEANS.put(6285, true);
        Object[] call = {null, new HostProps(), null, null, null, null, 8522, false};
        check(after(central, 1, null, call).getResult().equals(5), "integer property overridden with integer");
        check(after(central, "host-string", null, call).getResult().equals("host-string"),
                "integer property does not replace string result");
        failed = after(central, null, original, call);
        check(failed.getThrowable() == original && failed.getResult() == null,
                "property callback preserves original host exception identity");
        call[6] = 6285;
        check(after(central, false, null, call).getResult().equals(true), "boolean property overridden with boolean");
        call[6] = 999999;
        check(after(central, 37, null, call).getResult().equals(37), "unregistered property retained");

        reset(); Preferences settings = new Preferences();
        settings.values.put("status_style", "9");
        new StatusStyleHook(CONTEXT, LOADER, settings).hook();
        new NewSettingsStyleHook(CONTEXT, LOADER, settings).hook();
        check(!PropsOverrideHook.BOOLEANS.containsKey(14862) && !PropsOverrideHook.INTEGERS.containsKey(18564),
                "disabled settings preference retains original properties");
        DexSearchEngine.methods.put("waex_props_boolean_method", HostProps.class.getDeclaredMethod(
                "booleanGetter", Object.class, HostProps.class, Boolean.class, int.class));
        DexSearchEngine.methods.put("waex_props_integer_method", HostProps.class.getDeclaredMethod(
                "integerGetter", Object.class, HostProps.class, Integer.class, int.class));
        new PropsOverrideHook(CONTEXT, LOADER, settings).hook();
        settings.values.put("novaconfig", true);
        new NewSettingsStyleHook(CONTEXT, LOADER, settings).hook();
        check(Boolean.TRUE.equals(PropsOverrideHook.BOOLEANS.get(14862))
                        && Integer.valueOf(2).equals(PropsOverrideHook.INTEGERS.get(18564)),
                "enabled settings preference retains existing typed overrides");
        check(Integer.valueOf(9).equals(PropsOverrideHook.INTEGERS.get(8522))
                        && Integer.valueOf(9).equals(PropsOverrideHook.INTEGERS.get(8521)),
                "settings and status overrides coexist without replacing each other");
        check(XposedBridge.hooks.get(central).priority == 10000,
                "property callback keeps highest priority");
        call[6] = 18564;
        check(after(central, 0, null, call).getResult().equals(2),
                "settings integer registered after central hook is observed");
        call[6] = 14862;
        check(after(central, false, null, call).getResult().equals(true),
                "settings boolean registered after central hook is observed");
        System.out.println("RESULT checks=" + checks + " failures=" + failures);
        if (failures != 0) throw new AssertionError(failures + " production hook contract failures");
    }
}
