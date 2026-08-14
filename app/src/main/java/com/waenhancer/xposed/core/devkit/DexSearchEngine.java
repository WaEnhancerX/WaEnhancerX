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
            }
        } catch (Throwable t) {
            try {
                System.loadLibrary("dexkit");
            } catch (Throwable ignored) {}
        }
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

    public synchronized void close() {
        if (mBridge != null) {
            try {
                mBridge.close();
            } catch (Throwable ignored) {}
            mBridge = null;
        }
    }

    @FunctionalInterface
    public interface DexKitQuery {
        Method execute(@NonNull DexKitBridge bridge, @NonNull ClassLoader loader) throws Throwable;
    }

    @FunctionalInterface
    public interface DexKitClassQuery {
        Class<?> execute(@NonNull DexKitBridge bridge, @NonNull ClassLoader loader) throws Throwable;
    }
}
