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
        return prefs.getBoolean(key, def)
    }

}
