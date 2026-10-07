package com.waenhancer.xposed.core

import android.content.Context
import android.content.SharedPreferences
import com.waenhancer.xposed.bridge.client.PreferenceBridgeClient
import de.robv.android.xposed.XSharedPreferences

/**
 * Base abstract class for all modular WAEX enhancements in Kotlin.
 */
abstract class BaseFeature(
    @JvmField protected val context: Context,
    @JvmField protected val classLoader: ClassLoader,
    @JvmField protected val prefs: SharedPreferences
) {
    /**
     * Initializes and registers the bytecode hooks for this feature.
     */
    @Throws(Throwable::class)
    abstract fun hook()

    abstract val name: String

    protected fun isEnabled(key: String, def: Boolean = false): Boolean {
        return prefs.getBoolean(key, def)
    }
}

