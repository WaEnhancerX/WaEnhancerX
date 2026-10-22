package de.robv.android.xposed;
import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
public final class XposedBridge {
    public static final Map<Member,List<XC_MethodHook>> hooks = new LinkedHashMap<>();
    public static final List<String> logs = new ArrayList<>();
    public static void log(String value) { logs.add(value); }
    public static void hookMethod(Member member, XC_MethodHook hook) {
        hooks.computeIfAbsent(member, key -> new ArrayList<>()).add(hook);
    }
    public static void fire(Member member, Object owner, Object[] args, Object result, boolean before) throws Throwable {
        List<XC_MethodHook> callbacks = hooks.get(member);
        if (callbacks == null) throw new AssertionError("Production hook was not registered: " + member);
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.method = member; param.thisObject = owner; param.args = args; param.setResult(result);
        for (XC_MethodHook hook : new ArrayList<>(callbacks)) {
            if (before) hook.beforeHookedMethod(param); else hook.afterHookedMethod(param);
        }
    }
    public static void reset() { hooks.clear(); logs.clear(); }
}
