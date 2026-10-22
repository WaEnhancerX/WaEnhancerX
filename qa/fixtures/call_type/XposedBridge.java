package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Captures registrations and original invocations; never performs Xposed injection. */
public final class XposedBridge {
    public static final Map<Member, List<XC_MethodHook>> hooks = new LinkedHashMap<>();
    public static final List<String> logs = new ArrayList<>();
    public static int registrations, originals, failRegistration = -1;
    public static boolean failUnhook;
    public static void reset() {
        hooks.clear(); logs.clear(); registrations = originals = 0;
        failRegistration = -1; failUnhook = false;
    }
    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
        if (++registrations == failRegistration) throw new IllegalStateException("fixture installation failure");
        hooks.computeIfAbsent(method, key -> new ArrayList<>()).add(callback);
        return callback.new Unhook(method);
    }
    static void remove(Member method, XC_MethodHook callback) {
        if (failUnhook) throw new IllegalStateException("fixture unhook failure");
        List<XC_MethodHook> values = hooks.get(method);
        if (values != null) {
            values.remove(callback);
            if (values.isEmpty()) hooks.remove(method);
        }
    }
    public static Object invokeOriginalMethod(Member member, Object owner, Object[] args) throws Throwable {
        originals++;
        Method method = (Method) member;
        method.setAccessible(true);
        return method.invoke(owner, args);
    }
    public static Object dispatch(Method method, Object owner, Object... args) throws Throwable {
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.method = method; param.thisObject = owner; param.args = args;
        List<XC_MethodHook> registered = hooks.get(method);
        if (registered != null) for (XC_MethodHook callback : new ArrayList<>(registered)) {
            callback.beforeHookedMethod(param);
            if (param.early) break;
        }
        return param.early ? param.getResult() : invokeOriginalMethod(method, owner, args);
    }
    public static void log(String text) { logs.add(text); }
}
