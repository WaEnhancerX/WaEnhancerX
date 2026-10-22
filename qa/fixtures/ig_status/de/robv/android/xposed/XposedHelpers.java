package de.robv.android.xposed;
public final class XposedHelpers {
    public static Class<?> findClass(String name, ClassLoader loader) {
        try { return Class.forName(name, false, loader); }
        catch (ClassNotFoundException failure) { throw new IllegalArgumentException(failure); }
    }
    public static Class<?> findClassIfExists(String name, ClassLoader loader) {
        if (name.contains("StatusPlaybackActivity") || name.contains("ComposerActivity"))
            throw new AssertionError("Host navigation is outside this fixture");
        try { return Class.forName(name, false, loader); }
        catch (ClassNotFoundException absent) { return null; }
    }
    public static java.lang.reflect.Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { java.lang.reflect.Field field = current.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException absent) { }
        }
        throw new IllegalArgumentException(name);
    }
    public static java.lang.reflect.Method findMethodExact(Class<?> type, String name, Class<?>... parameters) {
        try { java.lang.reflect.Method method = type.getDeclaredMethod(name, parameters); method.setAccessible(true); return method; }
        catch (ReflectiveOperationException failure) { throw new IllegalArgumentException(failure); }
    }
}
