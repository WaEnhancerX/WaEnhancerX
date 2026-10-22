package de.robv.android.xposed;

import java.lang.reflect.Member;

/** Native Xposed callback boundary only; Android APIs and production code are real. */
public class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        boolean early;
        public void setResult(Object value) { result = value; early = true; }
        public Object getResult() { return result; }
    }
    public final class Unhook {
        final Member method;
        public Unhook(Member method) { this.method = method; }
        public void unhook() { XposedBridge.remove(method, XC_MethodHook.this); }
    }
}
