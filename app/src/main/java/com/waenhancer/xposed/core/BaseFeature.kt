package com.waenhancer.xposed.core

import android.content.Context
import android.content.SharedPreferences
import de.robv.android.xposed.XC_MethodHook
import java.util.concurrent.ConcurrentHashMap

/**
 * Base abstract class for all modular WAEX enhancements.
 */
abstract class BaseFeature(
    @JvmField protected val context: Context,
    @JvmField protected val classLoader: ClassLoader,
    @JvmField protected val prefs: SharedPreferences
) {
    private val installedHooks = ConcurrentHashMap.newKeySet<XC_MethodHook.Unhook>()

    init {
        HookRegistrar.registerOwner(this)
    }

    companion object {
        // These inherited fields intentionally shadow direct Xposed imports in every feature.
        // Existing and future features therefore participate in lifecycle tracking automatically.
        @JvmField val XposedBridge = HookRegistrar.BRIDGE
        @JvmField val XposedHelpers = HookRegistrar.HELPERS
    }

    fun beginHookTracking() = HookRegistrar.begin(this)

    fun endHookTracking() = HookRegistrar.end(this)

    fun retainHook(hook: XC_MethodHook.Unhook) {
        installedHooks.add(hook)
    }

    fun unhookAll() {
        installedHooks.forEach { hook ->
            try {
                hook.unhook()
            } catch (_: Throwable) {
            }
        }
        installedHooks.clear()
        ViewHookDispatcher.unregister(this)
        HookRegistrar.unregisterOwner(this)
    }

    /**
     * Initializes and registers the bytecode hooks for this feature.
     */
    @Throws(Throwable::class)
    abstract fun hook()

    abstract val name: String

    open val category: String
        get() = "General"

    protected fun isEnabled(key: String, def: Boolean = false): Boolean {
        // 1. Direct root key (Universal mode, or bridge-resolved per-target key)
        if (prefs.contains(key)) {
            return prefs.getBoolean(key, def)
        }
        // 2. Target package scoped key (Per Target App mode: e.g. "com.whatsapp:key", "com.whatsapp.w4b:key")
        val pkg = context.packageName
        if (!pkg.isNullOrEmpty() && prefs.contains("$pkg:$key")) {
            return prefs.getBoolean("$pkg:$key", def)
        }
        // 3. Fallback for Universal mode when previously configured per-target
        val mode = prefs.getString("module_config_mode", "Universal")
        if ("Universal".equals(mode, ignoreCase = true)) {
            if (prefs.getBoolean("com.whatsapp:$key", false) ||
                prefs.getBoolean("com.whatsapp.w4b:$key", false)) {
                return true
            }
        }
        return def
    }

    protected fun getStringPref(key: String, def: String?): String? {
        if (prefs.contains(key)) {
            return prefs.getString(key, def)
        }
        val pkg = context.packageName
        if (!pkg.isNullOrEmpty() && prefs.contains("$pkg:$key")) {
            return prefs.getString("$pkg:$key", def)
        }
        val mode = prefs.getString("module_config_mode", "Universal")
        if ("Universal".equals(mode, ignoreCase = true)) {
            prefs.getString("com.whatsapp:$key", null)?.let { return it }
            prefs.getString("com.whatsapp.w4b:$key", null)?.let { return it }
        }
        return def
    }

}
