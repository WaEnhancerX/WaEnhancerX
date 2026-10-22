package com.waenhancer.xposed.core.devkit;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Separate JVM processes and separately compiled module constants share a real
 * temporary preference file. Both old and corrected host descriptors remain valid.
 * Android SharedPreferences/PackageManager and Xposed resolution are boundary doubles.
 */
public final class DexCacheGenerationTest {
    public static final class OldTarget { public int oldField; public void oldMethod() {} }
    public static final class CorrectedTarget { public int newField; public void newMethod() {} }

    private static final class FilePrefs implements SharedPreferences {
        final Path path;
        final Properties data = new Properties();
        int clears;
        FilePrefs(Path path) throws Exception {
            this.path = path;
            if (Files.exists(path)) try (InputStream input = Files.newInputStream(path)) { data.load(input); }
        }
        @Override public String getString(String key, String fallback) {
            String value = data.getProperty(key);
            return value != null && value.startsWith("S:") ? value.substring(2) : fallback;
        }
        @Override public long getLong(String key, long fallback) {
            String value = data.getProperty(key);
            return value != null && value.startsWith("L:") ? Long.parseLong(value.substring(2)) : fallback;
        }
        @Override public Editor edit() { return new Edit(); }
        private final class Edit implements Editor {
            boolean clear;
            final Map<String, String> changes = new HashMap<>();
            @Override public Editor clear() { clear = true; return this; }
            @Override public Editor putString(String key, String value) { changes.put(key, "S:" + value); return this; }
            @Override public Editor putLong(String key, long value) { changes.put(key, "L:" + value); return this; }
            @Override public void apply() {
                if (clear) { data.clear(); clears++; }
                data.putAll(changes);
                try (OutputStream output = Files.newOutputStream(path)) { data.store(output, "JVM fixture only"); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }
        }
    }
    private static final class Host extends Context {
        final FilePrefs prefs;
        final PackageManager manager = new PackageManager();
        Host(Path path) throws Exception {
            prefs = new FilePrefs(path);
            manager.info.code = 1234L;
            manager.info.versionCode = 1234;
        }
        @Override public Context getApplicationContext() { return this; }
        @Override public String getPackageName() { return "com.whatsapp"; }
        @Override public PackageManager getPackageManager() { return manager; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Host host = new Host(Path.of(args[0]));
        String phase = args[1];
        boolean seed = phase.equals("seed");
        boolean fresh = phase.equals("fresh");
        boolean reuseOld = phase.equals("reuse-old");
        Class<?> expected = seed || reuseOld ? OldTarget.class : CorrectedTarget.class;
        Class<?> fallback = fresh || seed ? expected : OldTarget.class;
        Method expectedMethod = expected.getMethod(expected == OldTarget.class ? "oldMethod" : "newMethod");
        Field expectedField = expected.getField(expected == OldTarget.class ? "oldField" : "newField");
        Constructor<DexCacheManager> constructor = DexCacheManager.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        DexCacheManager cache = constructor.newInstance(host);
        ClassLoader loader = DexCacheGenerationTest.class.getClassLoader();
        AtomicInteger scans = new AtomicInteger();
        Class<?> type = cache.getClass(loader, "route-class", () -> { scans.incrementAndGet(); return fallback; });
        Method method = cache.getMethod(loader, "route-method", () -> {
            scans.incrementAndGet(); return fallback.getMethod(fallback == OldTarget.class ? "oldMethod" : "newMethod");
        });
        Field field = cache.getField(loader, "route-field", () -> {
            scans.incrementAndGet(); return fallback.getField(fallback == OldTarget.class ? "oldField" : "newField");
        });
        System.out.println("GENERATION_OBSERVED phase=" + phase + " class=" + type.getSimpleName()
                + " method=" + method.getName() + " field=" + field.getName() + " resolver_calls=" + scans.get());
        check(type == expected, "module generation returned an obsolete but still resolvable class");
        check(expectedMethod.equals(method), "module generation returned an obsolete but still resolvable method");
        check(expectedField.equals(field), "module generation returned an obsolete but still resolvable field");
        check(scans.get() == (seed || fresh ? 3 : 0), "one resolution per kind only after generation change");
        check(host.prefs.clears == (seed || fresh ? 1 : 0), "cache invalidates once on generation change, never every launch");
        System.out.println("GENERATION_PASS phase=" + phase + " resolver_calls=" + scans.get()
                + " invalidations=" + host.prefs.clears);
    }
}
