package com.waenhancer.xposed.core.devkit;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.robv.android.xposed.XposedBridge;
import org.luckypray.dexkit.DexKitBridge;
import java.io.File;
import java.lang.reflect.Method;

/**
 * Singleton wrapper around DexKitBridge for runtime signature-based class and method unobfuscation.
 */
public final class DexSearchEngine {

    private static volatile DexSearchEngine sInstance;
    private DexKitBridge mBridge;
    private int leases;
    private boolean closePending;

    static {
        try {
            System.loadLibrary("dexkit");
        } catch (Throwable ignored) {}
    }

    private DexSearchEngine() {}

    public static DexSearchEngine getInstance() {
        if (sInstance == null) {
            synchronized (DexSearchEngine.class) {
                if (sInstance == null) {
                    sInstance = new DexSearchEngine();
                }
            }
        }
        return sInstance;
    }

    public static void loadLibrary(@NonNull Context context) {
        try {
            String nativeLibraryDir = context.getPackageManager()
                    .getApplicationInfo("com.waenhancer", 0).nativeLibraryDir;
            File libFile = new File(nativeLibraryDir, "libdexkit.so");
            if (libFile.exists()) {
                System.load(libFile.getAbsolutePath());
                return;
            }
        } catch (Throwable ignored) {}

        try {
            String modulePath = com.waenhancer.xposed.MainHook.sModulePath;
            if (modulePath != null) {
                File baseApk = new File(modulePath);
                File parent = baseApk.getParentFile();
                if (parent != null) {
                    for (String abi : android.os.Build.SUPPORTED_ABIS) {
                        File libFile = new File(parent, "lib/" + abi + "/libdexkit.so");
                        if (libFile.exists()) {
                            System.load(libFile.getAbsolutePath());
                            return;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            System.loadLibrary("dexkit");
        } catch (Throwable ignored) {}
    }

    public synchronized void initialize(@NonNull Context context) {
        if (mBridge == null) {
            try {
                loadLibrary(context);
                String apkPath = context.getApplicationInfo().sourceDir;
                mBridge = DexKitBridge.create(apkPath);
            } catch (Throwable t) {
                XposedBridge.log("[WAEX] Failed to initialize DexKitBridge: " + t.getMessage());
            }
        }
    }

    /** Keeps the native bridge alive across one complete feature-resolution transaction. */
    public synchronized void acquireLease() {
        leases++;
        closePending = false;
    }

    /** Releases a feature-resolution lease and honors a close requested while it was active. */
    public synchronized void releaseLease() {
        if (leases > 0) leases--;
        if (leases == 0 && closePending) closeNow();
    }

    @Nullable
    public DexKitBridge getBridge() {
        return mBridge;
    }

    /**
     * Resolves a method using DexCacheManager Tier 1 cache, falling back to DexKit scanning Tier 2.
     */
    @Nullable
    public Method findMethodWithCache(
            @NonNull Context context,
            @NonNull ClassLoader loader,
            @NonNull String cacheKey,
            @NonNull DexKitQuery query
    ) {
        DexCacheManager cache = DexCacheManager.getInstance(context);
        return cache.getMethod(loader, cacheKey, () -> {
            if (mBridge == null) {
                initialize(context);
            }
            if (mBridge == null) return null;
            return query.execute(mBridge, loader);
        });
    }

    /**
     * Resolves a Class using DexCacheManager Tier 1 cache, falling back to DexKit scanning Tier 2.
     */
    @Nullable
    public Class<?> findClassWithCache(
            @NonNull Context context,
            @NonNull ClassLoader loader,
            @NonNull String cacheKey,
            @NonNull DexKitClassQuery query
    ) {
        DexCacheManager cache = DexCacheManager.getInstance(context);
        return cache.getClass(loader, cacheKey, () -> {
            if (mBridge == null) {
                initialize(context);
            }
            if (mBridge == null) return null;
            return query.execute(mBridge, loader);
        });
    }

    /** Resolves and persists multiple methods produced by one DexKit query. */
    @Nullable
    public Method[] findMethodsWithCache(
            @NonNull Context context,
            @NonNull ClassLoader loader,
            @NonNull String cacheKey,
            @NonNull DexKitMethodsQuery query
    ) {
        DexCacheManager cache = DexCacheManager.getInstance(context);
        return cache.getMethods(loader, cacheKey, () -> {
            if (mBridge == null) initialize(context);
            if (mBridge == null) return null;
            return query.execute(mBridge, loader);
        });
    }

    /** Resolves and persists multiple classes produced by one DexKit query. */
    @Nullable
    public Class<?>[] findClassesWithCache(
            @NonNull Context context,
            @NonNull ClassLoader loader,
            @NonNull String cacheKey,
            @NonNull DexKitClassesQuery query
    ) {
        DexCacheManager cache = DexCacheManager.getInstance(context);
        return cache.getClasses(loader, cacheKey, () -> {
            if (mBridge == null) initialize(context);
            if (mBridge == null) return null;
            return query.execute(mBridge, loader);
        });
    }

    public synchronized void close() {
        if (leases > 0) {
            closePending = true;
            return;
        }
        closeNow();
    }

    private void closeNow() {
        if (mBridge != null) {
            try {
                mBridge.close();
            } catch (Throwable ignored) {}
            mBridge = null;
        }
        closePending = false;
    }

    @FunctionalInterface
    public interface DexKitQuery {
        Method execute(@NonNull DexKitBridge bridge, @NonNull ClassLoader loader) throws Throwable;
    }

    @FunctionalInterface
    public interface DexKitClassQuery {
        Class<?> execute(@NonNull DexKitBridge bridge, @NonNull ClassLoader loader) throws Throwable;
    }

    @FunctionalInterface
    public interface DexKitMethodsQuery {
        Method[] execute(@NonNull DexKitBridge bridge, @NonNull ClassLoader loader) throws Throwable;
    }

    @FunctionalInterface
    public interface DexKitClassesQuery {
        Class<?>[] execute(@NonNull DexKitBridge bridge, @NonNull ClassLoader loader) throws Throwable;
    }
}
