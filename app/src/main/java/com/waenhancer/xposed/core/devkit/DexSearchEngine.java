package com.waenhancer.xposed.core.devkit;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.robv.android.xposed.XposedBridge;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Modern DexKit Search Helper with automated batch processing, cache acceleration, and safe fallbacks.
 */
public final class DexSearchEngine {

    private static volatile DexSearchEngine sInstance;
    private DexKitBridge mBridge;

    static {
        try {
            System.loadLibrary("dexkit");
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Warning: DexKit native library load: " + t.getMessage());
        }
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

    public synchronized void initialize(@NonNull String apkPath) {
        if (mBridge == null) {
            try {
                mBridge = DexKitBridge.create(apkPath);
            } catch (Throwable t) {
                XposedBridge.log("[WAEX] Failed to initialize DexKitBridge for " + apkPath + ": " + t.getMessage());
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
                initialize(context.getApplicationInfo().sourceDir);
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
}
