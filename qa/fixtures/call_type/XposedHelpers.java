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
}
