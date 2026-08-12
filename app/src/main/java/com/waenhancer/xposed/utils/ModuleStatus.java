package com.waenhancer.xposed.utils;

import androidx.annotation.Keep;

/**
 * Self-hook sentinel class for Xposed module status verification.
 */
@Keep
public final class ModuleStatus {

    private ModuleStatus() {}

    /**
     * Hooked dynamically by MainHook to return true in the manager app process.
     */
    public static boolean isModuleActive() {
        return false;
    }
}
