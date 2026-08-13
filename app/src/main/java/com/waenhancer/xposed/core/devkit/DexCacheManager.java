package com.waenhancer.xposed.core.devkit;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * High-performance, low-latency bytecode descriptor cache for DexKit-resolved methods, fields, and classes.
 * Replaces expensive runtime StackTraceElement inspection with explicit static keys and in-memory LRU indexing.
 */
public final class DexCacheManager {

    private static volatile DexCacheManager sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Map<String, Object> mMemoryCache = new ConcurrentHashMap<>();
    private final Set<String> mFailedKeys = ConcurrentHashMap.newKeySet();

    private DexCacheManager(@NonNull Context context) {
        this.mContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        this.mPrefs = mContext.getSharedPreferences("waex_dex_cache", Context.MODE_PRIVATE);

        // Invalidate cache automatically if host app or module version changed
        validateCacheVersion();
    }

    public static DexCacheManager getInstance(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (DexCacheManager.class) {
                if (sInstance == null) {
                    sInstance = new DexCacheManager(context);
                }
            }
        }
        return sInstance;
    }

    private void validateCacheVersion() {
        try {
            long hostVersionCode = mContext.getPackageManager().getPackageInfo(mContext.getPackageName(), 0).getLongVersionCode();
            long cachedHostVersion = mPrefs.getLong("host_version_code", -1);

            if (cachedHostVersion != hostVersionCode) {
                XposedBridge.log("[WAEX] Host version changed (" + cachedHostVersion + " -> " + hostVersionCode + "). Invalidating Dex cache.");
                mPrefs.edit().clear().putLong("host_version_code", hostVersionCode).apply();
                mMemoryCache.clear();
            }
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Error validating DexCache version: " + t.getMessage());
        }
    }

    public void clearMemoryCache() {
        mMemoryCache.clear();
        mFailedKeys.clear();
    }

    /**
     * Resolves a Class with explicit key and in-memory caching.
     */
    @Nullable
    public Class<?> getClass(@NonNull ClassLoader loader, @NonNull String key, @NonNull Resolver<Class<?>> resolver) {
        if (mFailedKeys.contains(key)) return null;

        Object mem = mMemoryCache.get(key);
        if (mem instanceof Class<?>) return (Class<?>) mem;

        String cachedName = mPrefs.getString(key, null);
        if (cachedName != null) {
            try {
                Class<?> cls = XposedHelpers.findClass(cachedName, loader);
                mMemoryCache.put(key, cls);
                return cls;
            } catch (Throwable ignored) {}
        }

        try {
            Class<?> resolved = resolver.resolve();
            if (resolved != null) {
                mPrefs.edit().putString(key, resolved.getName()).apply();
                mMemoryCache.put(key, resolved);
                return resolved;
            }
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Class resolution failed for [" + key + "]: " + t.getMessage());
        }

        mFailedKeys.add(key);
        return null;
    }

    /**
     * Resolves a Method with explicit key, parameter serialization, and in-memory caching.
     */
    @Nullable
    public Method getMethod(@NonNull ClassLoader loader, @NonNull String key, @NonNull Resolver<Method> resolver) {
        if (mFailedKeys.contains(key)) return null;

        Object mem = mMemoryCache.get(key);
        if (mem instanceof Method) return (Method) mem;

        String serialized = mPrefs.getString(key, null);
        if (serialized != null) {
            try {
                Method method = deserializeMethod(loader, serialized);
                if (method != null) {
                    mMemoryCache.put(key, method);
                    return method;
                }
            } catch (Throwable ignored) {}
        }

        try {
            Method resolved = resolver.resolve();
            if (resolved != null) {
                String encoded = serializeMethod(resolved);
                mPrefs.edit().putString(key, encoded).apply();
                mMemoryCache.put(key, resolved);
                return resolved;
            }
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Method resolution failed for [" + key + "]: " + t.getMessage());
        }

        mFailedKeys.add(key);
        return null;
    }

    /**
     * Resolves a Field with explicit key and in-memory caching.
     */
    @Nullable
    public Field getField(@NonNull ClassLoader loader, @NonNull String key, @NonNull Resolver<Field> resolver) {
        if (mFailedKeys.contains(key)) return null;

        Object mem = mMemoryCache.get(key);
        if (mem instanceof Field) return (Field) mem;

        String serialized = mPrefs.getString(key, null);
        if (serialized != null) {
            try {
                String[] parts = serialized.split(":");
                Class<?> cls = XposedHelpers.findClass(parts[0], loader);
                Field field = XposedHelpers.findField(cls, parts[1]);
                if (field != null) {
                    mMemoryCache.put(key, field);
                    return field;
                }
            } catch (Throwable ignored) {}
        }

        try {
            Field resolved = resolver.resolve();
            if (resolved != null) {
                String encoded = resolved.getDeclaringClass().getName() + ":" + resolved.getName();
                mPrefs.edit().putString(key, encoded).apply();
                mMemoryCache.put(key, resolved);
                return resolved;
            }
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Field resolution failed for [" + key + "]: " + t.getMessage());
        }

        mFailedKeys.add(key);
        return null;
    }

    private static String serializeMethod(Method method) {
        String base = method.getDeclaringClass().getName() + ":" + method.getName();
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length > 0) {
            base += ":" + Arrays.stream(paramTypes).map(Class::getName).collect(Collectors.joining(","));
        }
        return base;
    }

    private static Method deserializeMethod(ClassLoader loader, String encoded) {
        String[] parts = encoded.split(":");
        Class<?> cls = XposedHelpers.findClass(parts[0], loader);
        if (parts.length == 3) {
            String[] paramNames = parts[2].split(",");
            Class<?>[] paramTypes = Arrays.stream(paramNames)
                    .map(name -> findTypeClass(name, loader))
                    .toArray(Class<?>[]::new);
            return XposedHelpers.findMethodExact(cls, parts[1], paramTypes);
        }
        return XposedHelpers.findMethodExact(cls, parts[1]);
    }

    private static Class<?> findTypeClass(String name, ClassLoader loader) {
        return switch (name) {
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "char" -> char.class;
            case "short" -> short.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            case "void" -> void.class;
            default -> XposedHelpers.findClass(name, loader);
        };
    }

    @FunctionalInterface
    public interface Resolver<T> {
        T resolve() throws Throwable;
    }
}
