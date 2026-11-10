package com.waenhancer.xposed.core.devkit;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Versioned reflection-descriptor cache for DexKit results.
 *
 * <p>Values are stored as typed JSON descriptors rather than live reflection objects. Every
 * persisted descriptor is reconstructed and validated before use. Resolution is single-flight
 * per typed key, so two features cannot run the same expensive DexKit query concurrently.
 */
public final class DexCacheManager {

    private static final String STORE_NAME = "waex_dex_cache";
    private static final String MODULE_PACKAGE = "com.waenhancer";
    private static final long CACHE_SCHEMA_VERSION = 2L;
    private static final String META_PREFIX = "__meta__.";
    private static final String META_HOST_VERSION = META_PREFIX + "host_version_code";
    private static final String META_MODULE_VERSION = META_PREFIX + "module_version_code";
    private static final String META_MODULE_UPDATE = META_PREFIX + "module_update_time";
    private static final String META_SCHEMA = META_PREFIX + "schema";

    private static final String CLASS_PREFIX = "class:";
    private static final String METHOD_PREFIX = "method:";
    private static final String FIELD_PREFIX = "field:";
    private static final String CLASSES_PREFIX = "classes:";
    private static final String METHODS_PREFIX = "methods:";

    private static final long FAILED_RETRY_MS = 60_000L;
    private static final int MAX_FAILED_KEYS = 2048;
    private static volatile DexCacheManager sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Map<String, Object> mMemoryCache = new ConcurrentHashMap<>();
    private final Map<String, Object> mKeyLocks = new ConcurrentHashMap<>();
    private final DexNegativeLookupCache mFailedKeys =
            new DexNegativeLookupCache(FAILED_RETRY_MS, MAX_FAILED_KEYS);
    private final AtomicLong mMemoryHits = new AtomicLong();
    private final AtomicLong mDiskHits = new AtomicLong();
    private final AtomicLong mMisses = new AtomicLong();
    private final AtomicLong mResolutions = new AtomicLong();
    private final AtomicLong mFailures = new AtomicLong();
    private final AtomicLong mInvalidations = new AtomicLong();

    private DexCacheManager(@NonNull Context context) {
        Context app = context.getApplicationContext();
        mContext = app != null ? app : context;
        mPrefs = mContext.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE);
        validateCacheVersion();
    }

    public static DexCacheManager getInstance(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (DexCacheManager.class) {
                if (sInstance == null) sInstance = new DexCacheManager(context);
            }
        }
        return sInstance;
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode() : info.versionCode;
    }

    private void validateCacheVersion() {
        try {
            PackageInfo host = mContext.getPackageManager().getPackageInfo(mContext.getPackageName(), 0);
            long hostVersion = versionCode(host);
            long moduleVersion = -1L;
            long moduleUpdate = -1L;
            try {
                PackageInfo module = mContext.getPackageManager().getPackageInfo(MODULE_PACKAGE, 0);
                moduleVersion = versionCode(module);
                moduleUpdate = module.lastUpdateTime;
            } catch (Throwable ignored) {
                // Some cloned hosts cannot see the manager package. Schema + host invalidation
                // remain available and stable sentinels avoid clearing on every process start.
            }

            boolean stale = mPrefs.getLong(META_SCHEMA, -1L) != CACHE_SCHEMA_VERSION
                    || mPrefs.getLong(META_HOST_VERSION, -1L) != hostVersion
                    || mPrefs.getLong(META_MODULE_VERSION, -1L) != moduleVersion
                    || mPrefs.getLong(META_MODULE_UPDATE, -1L) != moduleUpdate;
            if (stale) {
                mPrefs.edit().clear()
                        .putLong(META_SCHEMA, CACHE_SCHEMA_VERSION)
                        .putLong(META_HOST_VERSION, hostVersion)
                        .putLong(META_MODULE_VERSION, moduleVersion)
                        .putLong(META_MODULE_UPDATE, moduleUpdate)
                        .apply();
                mMemoryCache.clear();
                mFailedKeys.clear();
                mInvalidations.incrementAndGet();
            }
        } catch (Throwable error) {
            XposedBridge.log("[WAEX][DexCache] version validation failed: "
                    + error.getClass().getSimpleName());
        }
    }

    public void clearMemoryCache() {
        mMemoryCache.clear();
        mFailedKeys.clear();
    }

    /** Removes one typed descriptor from memory, disk, and the negative cooldown. */
    public void evict(@NonNull Kind kind, @NonNull String key) {
        String typedKey = typedKey(kind, key);
        mMemoryCache.remove(typedKey);
        mPrefs.edit().remove(typedKey).apply();
        mFailedKeys.clearFailure(negativeKind(kind), typedKey);
        mInvalidations.incrementAndGet();
    }

    @Nullable
    public Class<?> getClass(@NonNull ClassLoader loader, @NonNull String key,
                             @NonNull Resolver<Class<?>> resolver) {
        return resolve(Kind.CLASS, loader, key, resolver, new Codec<Class<?>>() {
            @Override public String encode(Class<?> value) { return classDescriptor(value); }
            @Override public Class<?> decode(ClassLoader cl, String raw) {
                Descriptor descriptor = Descriptor.parse(raw, "class");
                return XposedHelpers.findClass(descriptor.owner, cl);
            }
        });
    }

    @Nullable
    public Method getMethod(@NonNull ClassLoader loader, @NonNull String key,
                            @NonNull Resolver<Method> resolver) {
        return resolve(Kind.METHOD, loader, key, resolver, METHOD_CODEC);
    }

    @Nullable
    public Field getField(@NonNull ClassLoader loader, @NonNull String key,
                          @NonNull Resolver<Field> resolver) {
        return resolve(Kind.FIELD, loader, key, resolver, FIELD_CODEC);
    }

    @Nullable
    public Class<?>[] getClasses(@NonNull ClassLoader loader, @NonNull String key,
                                 @NonNull Resolver<Class<?>[]> resolver) {
        return resolve(Kind.CLASSES, loader, key, resolver, arrayCodec("classes",
                value -> classDescriptor(value), (cl, raw) -> {
                    Descriptor d = Descriptor.parse(raw, "class");
                    return XposedHelpers.findClass(d.owner, cl);
                }, Class<?>[]::new));
    }

    @Nullable
    public Method[] getMethods(@NonNull ClassLoader loader, @NonNull String key,
                               @NonNull Resolver<Method[]> resolver) {
        return resolve(Kind.METHODS, loader, key, resolver, arrayCodec("methods",
                METHOD_CODEC::encode, METHOD_CODEC::decode, Method[]::new));
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private <T> T resolve(Kind kind, ClassLoader loader, String key, Resolver<T> resolver,
                          Codec<T> codec) {
        String typedKey = typedKey(kind, key);
        Object memory = mMemoryCache.get(typedKey);
        if (memory != null) {
            mMemoryHits.incrementAndGet();
            return (T) memory;
        }
        DexNegativeLookupCache.Kind negativeKind = negativeKind(kind);
        if (mFailedKeys.shouldSkip(negativeKind, typedKey, SystemClock.elapsedRealtime())) return null;

        Object lock = mKeyLocks.computeIfAbsent(typedKey, unused -> new Object());
        try {
            synchronized (lock) {
                memory = mMemoryCache.get(typedKey);
                if (memory != null) {
                    mMemoryHits.incrementAndGet();
                    return (T) memory;
                }
                String persisted = mPrefs.getString(typedKey, null);
                if (persisted != null) {
                    try {
                        T decoded = codec.decode(loader, persisted);
                        mMemoryCache.put(typedKey, decoded);
                        mFailedKeys.clearFailure(negativeKind, typedKey);
                        mDiskHits.incrementAndGet();
                        return decoded;
                    } catch (Throwable stale) {
                        // A descriptor that no longer validates must never be retried forever.
                        mPrefs.edit().remove(typedKey).apply();
                        mInvalidations.incrementAndGet();
                        XposedBridge.log("[WAEX][DexCache] evicted invalid " + typedKey);
                    }
                }

                mMisses.incrementAndGet();
                try {
                    T value = resolver.resolve();
                    if (value != null) {
                        mPrefs.edit().putString(typedKey, codec.encode(value)).apply();
                        mMemoryCache.put(typedKey, value);
                        mFailedKeys.clearFailure(negativeKind, typedKey);
                        mResolutions.incrementAndGet();
                        return value;
                    }
                } catch (Throwable error) {
                    XposedBridge.log("[WAEX][DexCache] resolution failed for " + typedKey + ": "
                            + error.getClass().getSimpleName());
                }
                mFailures.incrementAndGet();
                mFailedKeys.recordFailure(negativeKind, typedKey, SystemClock.elapsedRealtime());
                return null;
            }
        } finally {
            mKeyLocks.remove(typedKey, lock);
        }
    }

    public Diagnostics diagnostics() {
        return new Diagnostics(mMemoryHits.get(), mDiskHits.get(), mMisses.get(),
                mResolutions.get(), mFailures.get(), mInvalidations.get());
    }

    public enum Kind { CLASS, METHOD, FIELD, CLASSES, METHODS }

    public record Diagnostics(long memoryHits, long diskHits, long misses, long resolutions,
                              long failures, long invalidations) {}

    private static String typedKey(Kind kind, String key) {
        String prefix = switch (kind) {
            case CLASS -> CLASS_PREFIX;
            case METHOD -> METHOD_PREFIX;
            case FIELD -> FIELD_PREFIX;
            case CLASSES -> CLASSES_PREFIX;
            case METHODS -> METHODS_PREFIX;
        };
        return prefix + key;
    }

    private static DexNegativeLookupCache.Kind negativeKind(Kind kind) {
        return switch (kind) {
            case CLASS, CLASSES -> DexNegativeLookupCache.Kind.CLASS;
            case METHOD, METHODS -> DexNegativeLookupCache.Kind.METHOD;
            case FIELD -> DexNegativeLookupCache.Kind.FIELD;
        };
    }

    private interface Codec<T> {
        String encode(T value) throws Throwable;
        T decode(ClassLoader loader, String raw) throws Throwable;
    }

    private interface ElementEncoder<T> { String encode(T value) throws Throwable; }
    private interface ElementDecoder<T> { T decode(ClassLoader loader, String raw) throws Throwable; }
    private interface ArrayFactory<T> { T[] create(int size); }

    private static <T> Codec<T[]> arrayCodec(String kind, ElementEncoder<T> encoder,
                                              ElementDecoder<T> decoder, ArrayFactory<T> factory) {
        return new Codec<T[]>() {
            @Override public String encode(T[] values) throws Throwable {
                StringBuilder out = new StringBuilder("{\"schema\":2,\"kind\":\"")
                        .append(kind).append("\",\"items\":[");
                for (int i = 0; i < values.length; i++) {
                    if (i > 0) out.append(',');
                    out.append('"').append(encodeText(encoder.encode(values[i]))).append('"');
                }
                return out.append("]}").toString();
            }

            @Override public T[] decode(ClassLoader loader, String raw) throws Throwable {
                Descriptor.verifyHeader(raw, kind);
                String body = Descriptor.array(raw, "items");
                if (body.isEmpty()) return factory.create(0);
                String[] encoded = body.split(",", -1);
                T[] result = factory.create(encoded.length);
                for (int i = 0; i < encoded.length; i++) {
                    String item = encoded[i].trim();
                    if (item.length() < 2 || item.charAt(0) != '"'
                            || item.charAt(item.length() - 1) != '"') {
                        throw new IllegalArgumentException("Invalid cached array item");
                    }
                    result[i] = decoder.decode(loader, decodeText(item.substring(1, item.length() - 1)));
                }
                return result;
            }
        };
    }

    private static final Codec<Method> METHOD_CODEC = new Codec<Method>() {
        @Override public String encode(Method method) { return methodDescriptor(method); }

        @Override public Method decode(ClassLoader loader, String raw) throws Throwable {
            Descriptor d = Descriptor.parse(raw, "method");
            Class<?> owner = XposedHelpers.findClass(d.owner, loader);
            Class<?>[] parameters = new Class<?>[d.parameters.size()];
            for (int i = 0; i < parameters.length; i++) {
                parameters[i] = findTypeClass(d.parameters.get(i), loader);
            }
            Method method = XposedHelpers.findMethodExact(owner, d.name, parameters);
            if (!method.getReturnType().getName().equals(d.type)) {
                throw new NoSuchMethodException("Cached return type changed");
            }
            method.setAccessible(true);
            return method;
        }
    };

    private static final Codec<Field> FIELD_CODEC = new Codec<Field>() {
        @Override public String encode(Field field) { return fieldDescriptor(field); }

        @Override public Field decode(ClassLoader loader, String raw) throws Throwable {
            Descriptor d = Descriptor.parse(raw, "field");
            Field field = XposedHelpers.findField(XposedHelpers.findClass(d.owner, loader), d.name);
            if (!field.getType().getName().equals(d.type)) {
                throw new NoSuchFieldException("Cached field type changed");
            }
            field.setAccessible(true);
            return field;
        }
    };

    private static String classDescriptor(Class<?> value) {
        return descriptor("class", value.getName(), "", "", new Class<?>[0]);
    }

    private static String methodDescriptor(Method value) {
        return descriptor("method", value.getDeclaringClass().getName(), value.getName(),
                value.getReturnType().getName(), value.getParameterTypes());
    }

    private static String fieldDescriptor(Field value) {
        return descriptor("field", value.getDeclaringClass().getName(), value.getName(),
                value.getType().getName(), new Class<?>[0]);
    }

    private static String descriptor(String kind, String owner, String name, String type,
                                     Class<?>[] parameters) {
        StringBuilder out = new StringBuilder("{\"schema\":2,\"kind\":\"")
                .append(kind).append("\",\"owner\":\"").append(encodeText(owner))
                .append("\",\"name\":\"").append(encodeText(name))
                .append("\",\"type\":\"").append(encodeText(type)).append("\",\"params\":[");
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) out.append(',');
            out.append('"').append(encodeText(parameters[i].getName())).append('"');
        }
        return out.append("]}").toString();
    }

    private static String encodeText(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        char[] encoded = new char[bytes.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int current = bytes[i] & 0xff;
            encoded[i * 2] = digits[current >>> 4];
            encoded[i * 2 + 1] = digits[current & 0x0f];
        }
        return new String(encoded);
    }

    private static String decodeText(String value) {
        if ((value.length() & 1) != 0) throw new IllegalArgumentException("Invalid cache text");
        byte[] decoded = new byte[value.length() / 2];
        for (int i = 0; i < decoded.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("Invalid cache text");
            decoded[i] = (byte) ((high << 4) | low);
        }
        return new String(decoded, StandardCharsets.UTF_8);
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

    /** Minimal strict parser for descriptors emitted above; Base64 keeps string values escaped. */
    private static final class Descriptor {
        final String owner;
        final String name;
        final String type;
        final List<String> parameters;

        Descriptor(String owner, String name, String type, List<String> parameters) {
            this.owner = owner;
            this.name = name;
            this.type = type;
            this.parameters = parameters;
        }

        static Descriptor parse(String raw, String expectedKind) {
            verifyHeader(raw, expectedKind);
            List<String> parameters = new ArrayList<>();
            String params = array(raw, "params");
            if (!params.isEmpty()) {
                for (String encoded : params.split(",", -1)) {
                    String item = encoded.trim();
                    if (item.length() < 2 || item.charAt(0) != '"'
                            || item.charAt(item.length() - 1) != '"') {
                        throw new IllegalArgumentException("Invalid cached parameter");
                    }
                    parameters.add(decodeText(item.substring(1, item.length() - 1)));
                }
            }
            return new Descriptor(decodeText(string(raw, "owner")), decodeText(string(raw, "name")),
                    decodeText(string(raw, "type")), parameters);
        }

        static void verifyHeader(String raw, String kind) {
            if (!raw.startsWith("{\"schema\":2,")) throw new IllegalArgumentException("Cache schema mismatch");
            if (!kind.equals(string(raw, "kind"))) throw new IllegalArgumentException("Cache kind mismatch");
        }

        static String string(String raw, String key) {
            String marker = "\"" + key + "\":\"";
            int start = raw.indexOf(marker);
            if (start < 0) throw new IllegalArgumentException("Missing cache field " + key);
            start += marker.length();
            int end = raw.indexOf('"', start);
            if (end < 0) throw new IllegalArgumentException("Invalid cache field " + key);
            return raw.substring(start, end);
        }

        static String array(String raw, String key) {
            String marker = "\"" + key + "\":[";
            int start = raw.indexOf(marker);
            if (start < 0) throw new IllegalArgumentException("Missing cache array " + key);
            start += marker.length();
            int end = raw.indexOf(']', start);
            if (end < 0) throw new IllegalArgumentException("Invalid cache array " + key);
            return raw.substring(start, end).trim();
        }
    }

    @FunctionalInterface
    public interface Resolver<T> {
        T resolve() throws Throwable;
    }
}
