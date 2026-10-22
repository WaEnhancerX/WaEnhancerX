package com.waenhancer.xposed.core.devkit;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Compiles and executes the actual production DexCacheManager against Android/Xposed doubles. */
public final class TestDexCacheManager {
    public static final class Sample {
        public String value;
        public String correctedValue;
        public int length(int x) { return x; }
        public int correctedLength(int x) { return x; }
    }

    private static final class MemoryPrefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        @Override public synchronized String getString(String key, String fallback) {
            Object v = values.get(key); return v instanceof String ? (String) v : fallback;
        }
        @Override public synchronized long getLong(String key, long fallback) {
            Object v = values.get(key); return v instanceof Long ? (Long) v : fallback;
        }
        @Override public Editor edit() { return new Edit(); }
        private final class Edit implements Editor {
            boolean clear;
            Map<String,Object> modifications = new HashMap<>();
            @Override public Editor clear() { clear = true; return this; }
            @Override public Editor putString(String key, String value) { modifications.put(key, value); return this; }
            @Override public Editor putLong(String key, long value) { modifications.put(key, value); return this; }
            @Override public void apply() {
                synchronized (MemoryPrefs.this) {
                    if (clear) values.clear();
                    values.putAll(modifications);
                }
            }
        }
    }

    private static final class TestContext extends Context {
        final MemoryPrefs preferences = new MemoryPrefs();
        final PackageManager manager = new PackageManager();
        @Override public Context getApplicationContext() { return this; }
        @Override public String getPackageName() { return "com.whatsapp"; }
        @Override public PackageManager getPackageManager() { return manager; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
    }

    private static int checks;
    private static void check(boolean expected, String message) {
        checks++;
        if (!expected) throw new AssertionError(message);
    }
    private static DexCacheManager newManager(Context context) throws Exception {
        Constructor<DexCacheManager> constructor = DexCacheManager.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        return constructor.newInstance(context);
    }

    public static void main(String[] args) throws Exception {
        int api = args.length == 0 ? 28 : Integer.parseInt(args[0]);
        Build.VERSION.SDK_INT = api;
        // API 28 must preserve the upper bits; older PackageInfo exposes only int versionCode.
        long initialVersion = api >= 28 ? (1L << 32) + 1234L : 1234L;
        long updatedVersion = api >= 28 ? (2L << 32) + 1234L : 2345L;
        TestContext context = new TestContext();
        context.manager.info.code = initialVersion;
        context.manager.info.versionCode = (int) initialVersion;
        SystemClock.now = 5_000L;
        DexCacheManager cache = newManager(context);
        ClassLoader loader = TestDexCacheManager.class.getClassLoader();
        AtomicInteger scans = new AtomicInteger();

        check(cache.getClass(loader, "test-missing-class", () -> {scans.incrementAndGet(); return null;}) == null,
                "first class miss");
        check(scans.get() == 1, "class scanned once");
        SystemClock.now = 64_999L;
        check(cache.getClass(loader, "test-missing-class", () -> {scans.incrementAndGet(); return Sample.class;}) == null,
                "class miss must cool down");
        check(scans.get() == 1, "no repeated expensive scan");
        SystemClock.now = 65_000L;
        check(cache.getClass(loader, "test-missing-class", () -> {scans.incrementAndGet(); return Sample.class;}) == Sample.class,
                "class recovers after 60 seconds");
        check(scans.get() == 2, "class retries exactly once after cooldown");
        check(cache.getClass(loader, "test-missing-class", () -> {throw new AssertionError("memory cache ignored");}) == Sample.class,
                "positive class cache");
        cache.clearMemoryCache();
        check(cache.getClass(loader, "test-missing-class", () -> {throw new AssertionError("persistent cache ignored");}) == Sample.class,
                "persistent class cache");

        // A stale persisted descriptor is not treated as a permanent failure.
        context.preferences.values.put("stale-class", "old.removed.HostClass");
        AtomicInteger staleLookups = new AtomicInteger();
        check(cache.getClass(loader, "stale-class", () -> {
            staleLookups.incrementAndGet(); return Sample.class;
        }) == Sample.class, "stale descriptor falls back to resolver");
        check(staleLookups.get() == 1, "resolver called after stale persisted class");
        check(Sample.class.getName().equals(context.preferences.getString("stale-class", null)),
                "stale persisted class replaced with valid descriptor");

        // Failed method and failed field are independent of each other, even with same key.
        SystemClock.now = 70_000L;
        AtomicInteger methods = new AtomicInteger();
        AtomicInteger fields = new AtomicInteger();
        Method length = Sample.class.getDeclaredMethod("length", int.class);
        Field field = Sample.class.getDeclaredField("value");
        check(cache.getMethod(loader, "shared-key", () -> {methods.incrementAndGet(); return null;}) == null,
                "method miss");
        check(cache.getField(loader, "shared-key", () -> {fields.incrementAndGet(); return null;}) == null,
                "field miss independently scanned");
        check(methods.get()==1 && fields.get()==1, "each kind own cooldown");
        check(cache.getMethod(loader, "shared-key", () -> {methods.incrementAndGet(); return length;}) == null,
                "method cooldown independent of field");
        check(cache.getField(loader, "shared-key", () -> {fields.incrementAndGet(); return field;}) == null,
                "field cooldown independent of method");
        check(methods.get()==1 && fields.get()==1, "no repeated scans during cooldown");

        SystemClock.now = 130_000L;
        check(cache.getMethod(loader, "method-key", () -> {methods.incrementAndGet(); return length;}) == length,
                "method resolver success");
        cache.clearMemoryCache();
        check(cache.getMethod(loader, "method-key", () -> {throw new AssertionError("persistent method ignored");}).equals(length),
                "persistent method deserialization");
        check(cache.getField(loader, "field-key", () -> {fields.incrementAndGet(); return field;}) == field,
                "field resolver success");
        cache.clearMemoryCache();
        check(cache.getField(loader, "field-key", () -> {throw new AssertionError("persistent field ignored");}).equals(field),
                "persistent field deserialization");

        AtomicInteger err = new AtomicInteger();
        SystemClock.now = 200_000L;
        check(cache.getMethod(loader, "temporary-exception", () -> {
            err.incrementAndGet(); throw new IllegalStateException("temporary scan error");
        }) == null, "scanning exception must fail safely");
        check(cache.getMethod(loader, "temporary-exception", () -> {err.incrementAndGet(); return length;}) == null,
                "exception causes bounded cooldown");
        check(err.get()==1, "exception does not spin");
        SystemClock.now += 60_000L;
        check(cache.getMethod(loader, "temporary-exception", () -> {err.incrementAndGet(); return length;}) == length,
                "scanning exception can recover");

        SystemClock.now += 10L;
        AtomicInteger reset = new AtomicInteger();
        check(cache.getClass(loader, "clearable-negative", () -> {reset.incrementAndGet(); return null;}) == null,
                "clear test first miss");
        cache.clearMemoryCache();
        check(cache.getClass(loader, "clearable-negative", () -> {reset.incrementAndGet(); return Sample.class;}) == Sample.class,
                "explicit invalidation immediately retries");
        check(reset.get()==2, "two resolver calls after invalidation");

        // Reinstantiation with new host version invalidates positive descriptors.
        long versionOld = context.preferences.getLong("host_version_code", -1L);
        check(versionOld == initialVersion, "first host version persisted on API " + api);
        context.manager.info.code = updatedVersion;
        context.manager.info.versionCode = (int) updatedVersion;
        cache = newManager(context);
        check(context.preferences.getLong("host_version_code", -1L)==updatedVersion, "host update recorded");
        check(context.preferences.getString("method-key", null)==null, "old host descriptor removed");
        AtomicInteger reread = new AtomicInteger();
        check(cache.getMethod(loader, "method-key", () -> {reread.incrementAndGet(); return length;}) == length,
                "method re-resolved on host update");
        check(reread.get()==1, "host-upgraded method scanned anew");

        // A negative is never persisted and must not block a new manager/host process.
        AtomicInteger fresh = new AtomicInteger();
        check(cache.getMethod(loader, "no-disk-negative", () -> {fresh.incrementAndGet(); return null;}) == null,
                "temporary negative recorded");
        check(context.preferences.getString("no-disk-negative", null)==null, "negative must not be persisted");
        DexCacheManager restarted = newManager(context);
        check(restarted.getMethod(loader, "no-disk-negative", () -> {fresh.incrementAndGet(); return length;}) == length,
                "new process retries previous missing key");
        check(fresh.get()==2, "fresh manager invokes resolver");
        context.preferences.values.put("same-version-descriptor", "retained");
        newManager(context);
        check("retained".equals(context.preferences.getString("same-version-descriptor", null)),
                "unchanged host version preserves descriptors");

        // Without host identity, old members still resolve but cannot be trusted.
        // Use fresh resolver results only in memory; never stamp unknown disk data as current.
        DexCacheManager trusted = newManager(context);
        check(trusted.getClass(loader, "test-missing-class", () -> Sample.class) == Sample.class,
                "seed existing class for failed validation");
        check(trusted.getField(loader, "field-key", () -> field).equals(field),
                "seed existing field for failed validation");
        context.manager.failLookup = true;
        DexCacheManager unknownHost = newManager(context);
        AtomicInteger safeScans = new AtomicInteger();
        Method corrected = Sample.class.getDeclaredMethod("correctedLength", int.class);
        Field correctedField = Sample.class.getDeclaredField("correctedValue");
        String beforeClass = context.preferences.getString("test-missing-class", null);
        String beforeMethod = context.preferences.getString("method-key", null);
        String beforeField = context.preferences.getString("field-key", null);
        check(unknownHost.getClass(loader, "test-missing-class", () -> {
            safeScans.incrementAndGet(); return String.class;
        }) == String.class, "unvalidated host bypasses disk class descriptor");
        check(unknownHost.getMethod(loader, "method-key", () -> {
            safeScans.incrementAndGet(); return corrected;
        }).equals(corrected), "unvalidated host bypasses disk method descriptor");
        check(unknownHost.getField(loader, "field-key", () -> {
            safeScans.incrementAndGet(); return correctedField;
        }).equals(correctedField), "unvalidated host bypasses disk field descriptor");
        check(safeScans.get() == 3, "unvalidated host resolves each kind once");
        check(unknownHost.getMethod(loader, "method-key", () -> {
            safeScans.incrementAndGet(); return length;
        }).equals(corrected) && safeScans.get() == 3, "safe memory result avoids repeated scan");
        check(java.util.Objects.equals(beforeClass, context.preferences.getString("test-missing-class", null)),
                "unvalidated class result is not persisted");
        check(java.util.Objects.equals(beforeMethod, context.preferences.getString("method-key", null)),
                "unvalidated method result is not persisted");
        check(java.util.Objects.equals(beforeField, context.preferences.getString("field-key", null)),
                "unvalidated field result is not persisted");
        context.manager.failLookup = false;
        check(newManager(context).getMethod(loader, "method-key", () -> corrected).equals(length),
                "later validated process can reuse its unchanged trusted disk generation");
        System.out.println("PASS: " + checks + " production DexCacheManager integration checks; API " + api + " double");
    }
}
