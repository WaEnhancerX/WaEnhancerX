package com.waenhancer.xposed.core;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Routes Xposed registrations through their owning feature so lazy hooks can be released. */
public final class HookRegistrar {
    public static final Bridge BRIDGE = new Bridge();
    public static final Helpers HELPERS = new Helpers();

    private static final ThreadLocal<BaseFeature> INSTALLING = new ThreadLocal<>();
    private static final ConcurrentHashMap<String, BaseFeature> OWNERS = new ConcurrentHashMap<>();

    private HookRegistrar() {}

    static void registerOwner(BaseFeature owner) {
        OWNERS.put(owner.getClass().getName(), owner);
    }

    static void unregisterOwner(BaseFeature owner) {
        OWNERS.remove(owner.getClass().getName(), owner);
    }

    static void begin(BaseFeature owner) {
        INSTALLING.set(owner);
    }

    static void end(BaseFeature owner) {
        if (INSTALLING.get() == owner) INSTALLING.remove();
    }

    private static <T> T retain(T result) {
        BaseFeature owner = INSTALLING.get();
        if (owner == null) {
            for (StackTraceElement frame : new Throwable().getStackTrace()) {
                String name = frame.getClassName();
                while (name != null) {
                    owner = OWNERS.get(name);
                    if (owner != null) break;
                    int nested = name.lastIndexOf('$');
                    name = nested > 0 ? name.substring(0, nested) : null;
                }
                if (owner != null) break;
            }
        }
        if (owner != null) {
            if (result instanceof XC_MethodHook.Unhook) {
                owner.retainHook((XC_MethodHook.Unhook) result);
            } else if (result instanceof Set) {
                for (Object item : (Set<?>) result) {
                    if (item instanceof XC_MethodHook.Unhook) owner.retainHook((XC_MethodHook.Unhook) item);
                }
            }
        }
        return result;
    }

    public static final class Bridge {
        public XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
            return retain(XposedBridge.hookMethod(method, callback));
        }

        public Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName,
                                                        XC_MethodHook callback) {
            return retain(XposedBridge.hookAllMethods(hookClass, methodName, callback));
        }

        public Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass,
                                                             XC_MethodHook callback) {
            return retain(XposedBridge.hookAllConstructors(hookClass, callback));
        }

        public void log(String text) { XposedBridge.log(text); }
        public void log(Throwable error) { XposedBridge.log(error); }
    }

    public static final class Helpers {
        public XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName,
                                                      Object... parameterTypesAndCallback) {
            return retain(XposedHelpers.findAndHookMethod(
                    clazz, methodName, parameterTypesAndCallback));
        }

        public XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader,
                                                      String methodName, Object... parameterTypesAndCallback) {
            return retain(XposedHelpers.findAndHookMethod(
                    className, classLoader, methodName, parameterTypesAndCallback));
        }

        public Class<?> findClass(String className, ClassLoader classLoader) {
            return XposedHelpers.findClass(className, classLoader);
        }
        public Class<?> findClassIfExists(String className, ClassLoader classLoader) {
            return XposedHelpers.findClassIfExists(className, classLoader);
        }
        public Field findField(Class<?> clazz, String fieldName) {
            return XposedHelpers.findField(clazz, fieldName);
        }
        public Field findFieldIfExists(Class<?> clazz, String fieldName) {
            return XposedHelpers.findFieldIfExists(clazz, fieldName);
        }
        public Method findMethodBestMatch(Class<?> clazz, String methodName, Object... args) {
            return XposedHelpers.findMethodBestMatch(clazz, methodName, args);
        }
        public Method findMethodExact(Class<?> clazz, String methodName, Object... parameterTypes) {
            return XposedHelpers.findMethodExact(clazz, methodName, parameterTypes);
        }
        public Method findMethodExactIfExists(Class<?> clazz, String methodName, Object... parameterTypes) {
            return XposedHelpers.findMethodExactIfExists(clazz, methodName, parameterTypes);
        }
        public Object callMethod(Object obj, String methodName, Object... args) {
            return XposedHelpers.callMethod(obj, methodName, args);
        }
        public Object getObjectField(Object obj, String fieldName) {
            return XposedHelpers.getObjectField(obj, fieldName);
        }
        public Object getAdditionalInstanceField(Object obj, String key) {
            return XposedHelpers.getAdditionalInstanceField(obj, key);
        }
        public Object setAdditionalInstanceField(Object obj, String key, Object value) {
            return XposedHelpers.setAdditionalInstanceField(obj, key, value);
        }
        public Object removeAdditionalInstanceField(Object obj, String key) {
            return XposedHelpers.removeAdditionalInstanceField(obj, key);
        }
    }
}
