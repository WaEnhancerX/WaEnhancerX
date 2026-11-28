package de.robv.android.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Reflection boundary used by the complete production DexCacheManager. No hook injection. */
public final class XposedHelpers {
    public static Class<?> findClass(String name, ClassLoader loader) {
        try { return Class.forName(name, false, loader); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    public static Field findField(Class<?> owner, String name) {
        try { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    public static Method findMethodExact(Class<?> owner, String name, Object... types) {
        Class<?>[] arguments = new Class<?>[types.length];
        for (int i = 0; i < types.length; i++) arguments[i] = (Class<?>) types[i];
        try { Method method = owner.getDeclaredMethod(name, arguments); method.setAccessible(true); return method; }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    public static Class<?> findClassIfExists(String name, ClassLoader loader) {
        try { return Class.forName(name, false, loader); }
        catch (ClassNotFoundException missing) { return null; }
    }
    public static Field findFieldIfExists(Class<?> owner, String name) {
        try { return findField(owner, name); }
        catch (IllegalStateException missing) { return null; }
    }
    public static Method findMethodExactIfExists(Class<?> owner, String name, Object... types) {
        try { return findMethodExact(owner, name, types); }
        catch (IllegalStateException missing) { return null; }
    }
    public static Method findMethodBestMatch(Class<?> owner, String name, Object... types) {
        throw new AssertionError("Best-match resolution is outside this fixture");
    }
    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> owner, String name, Object... args) {
        throw new AssertionError("Name-based hook installation is outside this fixture");
    }
    public static XC_MethodHook.Unhook findAndHookMethod(String owner, ClassLoader loader, String name, Object... args) {
        throw new AssertionError("Name-based hook installation is outside this fixture");
    }
    public static Object callMethod(Object owner, String name, Object... args) {
        throw new AssertionError("Reflected invocation is outside this fixture");
    }
    public static Object getObjectField(Object owner, String name) {
        throw new AssertionError("Instance field reads are outside this fixture");
    }
    public static Object getAdditionalInstanceField(Object owner, String name) {
        throw new AssertionError("Additional fields are outside this fixture");
    }
    public static Object setAdditionalInstanceField(Object owner, String name, Object value) {
        throw new AssertionError("Additional fields are outside this fixture");
    }
    public static Object removeAdditionalInstanceField(Object owner, String name) {
        throw new AssertionError("Additional fields are outside this fixture");
    }
}
