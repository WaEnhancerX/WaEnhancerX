package com.waenhancer.xposed.core

import android.content.Context
import android.content.SharedPreferences
import de.robv.android.xposed.XC_MethodHook
import java.util.concurrent.ConcurrentHashMap
import com.waenhancer.xposed.utils.HookErrors
import com.waenhancer.xposed.core.privacy.PrivacyPrefs

/**
 * Base abstract class for all modular WAEX enhancements.
 */
abstract class BaseFeature(
    @JvmField protected val context: Context,
    @JvmField protected val classLoader: ClassLoader,
    @JvmField protected val prefs: SharedPreferences
) {
    private val installedHooks = ConcurrentHashMap.newKeySet<XC_MethodHook.Unhook>()

    /**
     * Live privacy settings (toggles, Ghost Mode, per-contact rules). Hooks must query this on
     * every event rather than caching values, so runtime switches (quick actions) apply at once.
     */
    protected val privacy: PrivacyPrefs by lazy { PrivacyPrefs.of(context, prefs) }

    init {
        HookRegistrar.registerOwner(this)
    }

    companion object {
        // These inherited fields intentionally shadow direct Xposed imports in every feature.
        // Existing and future features therefore participate in lifecycle tracking automatically.
        @JvmField val XposedBridge = HookRegistrar.BRIDGE
        @JvmField val XposedHelpers = HookRegistrar.HELPERS

        // Preference lookups run inside hot hooks (every SQLite call, every view bind). Building
        // "<package>:<key>" on each miss allocated a string per call. A process hosts one target
        // package, so the scoped key for a given key never changes.
        private val scopedKeys = ConcurrentHashMap<String, String>()
    }

    private fun scopedKey(key: String): String? {
        val pkg = context.packageName
        if (pkg.isNullOrEmpty()) return null
        return scopedKeys.getOrPut(key) { "$pkg:$key" }
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
            } catch (ignored: Throwable) { HookErrors.swallowed("BaseFeature", ignored) }
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
        // 2. Target package scoped key (Per Target App mode: "<package>:key" for this process only)
        val scoped = scopedKey(key)
        if (scoped != null && prefs.contains(scoped)) {
            return prefs.getBoolean(scoped, def)
        }
        // Never consult another target's scoped key: it would apply WA Business settings to
        // WhatsApp (or the reverse), and stale per-target keys must not leak into Universal mode.
        return def
    }

    protected fun getStringPref(key: String, def: String?): String? {
        if (prefs.contains(key)) {
            return prefs.getString(key, def)
        }
        val scoped = scopedKey(key)
        if (scoped != null && prefs.contains(scoped)) {
            return prefs.getString(scoped, def)
        }
        return def
    }

    protected fun getIntPref(key: String, def: Int): Int {
        if (prefs.contains(key)) {
            return prefs.getInt(key, def)
        }
        val scoped = scopedKey(key)
        if (scoped != null && prefs.contains(scoped)) {
            return prefs.getInt(scoped, def)
        }
        return def
    }

}
