package com.waenhancer.xposed.core.devkit;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.waenhancer.app.BuildConfig;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Reflection descriptor cache for DexKit-resolved methods, fields and classes.
 * Maintains positive in-memory and persisted results, with bounded negative-lookup cooldowns.
 */
public final class DexCacheManager {

    private static volatile DexCacheManager sInstance;

    // Bump for incompatible descriptor/selection semantics within a module version.
    // Compiled module identity avoids cross-profile/package-visibility lookups at startup.
    private static final int DESCRIPTOR_SCHEMA_VERSION = 1;
    private static final String MODULE_CACHE_GENERATION = DESCRIPTOR_SCHEMA_VERSION + ":"
            + BuildConfig.VERSION_CODE + ":" + BuildConfig.VERSION_NAME;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final boolean mPersistentCacheValid;
    private final Map<String, Object> mMemoryCache = new ConcurrentHashMap<>();
    // A missing/temporarily unavailable DexKit match is not permanently negative.
    // Suppress repeated attempts for 60 seconds per key/kind (not a single-flight lock).
    private static final long FAILED_RETRY_MS = 60_000L;
    private static final int MAX_FAILED_KEYS = 2048;
    private final DexNegativeLookupCache mFailedKeys =
            new DexNegativeLookupCache(FAILED_RETRY_MS, MAX_FAILED_KEYS);

    private DexCacheManager(@NonNull Context context) {
        this.mContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        this.mPrefs = mContext.getSharedPreferences("waex_dex_cache", Context.MODE_PRIVATE);

        // Invalidate cache automatically if host app or module version changed
        mPersistentCacheValid = validateCacheVersion();
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

    @SuppressWarnings("deprecation") // versionCode is the platform API before Android 9.
    private boolean validateCacheVersion() {
        try {
            PackageInfo host = mContext.getPackageManager().getPackageInfo(mContext.getPackageName(), 0);
            long hostVersionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? host.getLongVersionCode() : host.versionCode;
            long cachedHostVersion = mPrefs.getLong("host_version_code", -1);
            String cachedModuleGeneration = mPrefs.getString("module_cache_generation", null);

            if (cachedHostVersion != hostVersionCode || !MODULE_CACHE_GENERATION.equals(cachedModuleGeneration)) {
                mPrefs.edit().clear().putLong("host_version_code", hostVersionCode)
                        .putString("module_cache_generation", MODULE_CACHE_GENERATION).apply();
                mMemoryCache.clear();
                mFailedKeys.clear();
            }
            return true;
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Error validating DexCache version: " + t.getClass().getSimpleName());
            // A still-resolvable persisted member may have been selected by another
            // host/module. Resolve into memory until a later process can validate disk.
            return false;
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
        Object mem = mMemoryCache.get(key);
        if (mem instanceof Class<?>) return (Class<?>) mem;
        if (mFailedKeys.shouldSkip(DexNegativeLookupCache.Kind.CLASS,
                key, SystemClock.elapsedRealtime())) return null;

        String cachedName = mPersistentCacheValid ? mPrefs.getString(key, null) : null;
        if (cachedName != null) {
            try {
                Class<?> cls = XposedHelpers.findClass(cachedName, loader);
                mMemoryCache.put(key, cls);
                mFailedKeys.clearFailure(DexNegativeLookupCache.Kind.CLASS, key);
                return cls;
            } catch (Throwable ignored) {}
        }

        try {
            Class<?> resolved = resolver.resolve();
            if (resolved != null) {
                if (mPersistentCacheValid) mPrefs.edit().putString(key, resolved.getName()).apply();
                mMemoryCache.put(key, resolved);
                mFailedKeys.clearFailure(DexNegativeLookupCache.Kind.CLASS, key);
                return resolved;
            }
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Class resolution failed for [" + key + "]: " + t.getMessage());
        }

        mFailedKeys.recordFailure(DexNegativeLookupCache.Kind.CLASS,
                key, SystemClock.elapsedRealtime());
        return null;
    }

    /**
     * Resolves a Method with explicit key, parameter serialization, and in-memory caching.
     */
    @Nullable
    public Method getMethod(@NonNull ClassLoader loader, @NonNull String key, @NonNull Resolver<Method> resolver) {
        Object mem = mMemoryCache.get(key);
        if (mem instanceof Method) return (Method) mem;
        if (mFailedKeys.shouldSkip(DexNegativeLookupCache.Kind.METHOD,
                key, SystemClock.elapsedRealtime())) return null;

        String serialized = mPersistentCacheValid ? mPrefs.getString(key, null) : null;
        if (serialized != null) {
            try {
                Method method = deserializeMethod(loader, serialized);
                if (method != null) {
                    mMemoryCache.put(key, method);
                    mFailedKeys.clearFailure(DexNegativeLookupCache.Kind.METHOD, key);
                    return method;
                }
            } catch (Throwable ignored) {}
        }

        try {
            Method resolved = resolver.resolve();
            if (resolved != null) {
                String encoded = serializeMethod(resolved);
                if (mPersistentCacheValid) mPrefs.edit().putString(key, encoded).apply();
                mMemoryCache.put(key, resolved);
                mFailedKeys.clearFailure(DexNegativeLookupCache.Kind.METHOD, key);
                return resolved;
            }
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Method resolution failed for [" + key + "]: " + t.getMessage());
        }

        mFailedKeys.recordFailure(DexNegativeLookupCache.Kind.METHOD,
                key, SystemClock.elapsedRealtime());
        return null;
    }

    /**
     * Resolves a Field with explicit key and in-memory caching.
     */
    @Nullable
    public Field getField(@NonNull ClassLoader loader, @NonNull String key, @NonNull Resolver<Field> resolver) {
        Object mem = mMemoryCache.get(key);
        if (mem instanceof Field) return (Field) mem;
        if (mFailedKeys.shouldSkip(DexNegativeLookupCache.Kind.FIELD,
                key, SystemClock.elapsedRealtime())) return null;

        String serialized = mPersistentCacheValid ? mPrefs.getString(key, null) : null;
        if (serialized != null) {
            try {
                String[] parts = serialized.split(":");
                Class<?> cls = XposedHelpers.findClass(parts[0], loader);
                Field field = XposedHelpers.findField(cls, parts[1]);
                if (field != null) {
                    mMemoryCache.put(key, field);
                    mFailedKeys.clearFailure(DexNegativeLookupCache.Kind.FIELD, key);
                    return field;
                }
            } catch (Throwable ignored) {}
        }

        try {
            Field resolved = resolver.resolve();
            if (resolved != null) {
                String encoded = resolved.getDeclaringClass().getName() + ":" + resolved.getName();
                if (mPersistentCacheValid) mPrefs.edit().putString(key, encoded).apply();
                mMemoryCache.put(key, resolved);
                mFailedKeys.clearFailure(DexNegativeLookupCache.Kind.FIELD, key);
                return resolved;
            }
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Field resolution failed for [" + key + "]: " + t.getMessage());
        }

        mFailedKeys.recordFailure(DexNegativeLookupCache.Kind.FIELD,
                key, SystemClock.elapsedRealtime());
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
