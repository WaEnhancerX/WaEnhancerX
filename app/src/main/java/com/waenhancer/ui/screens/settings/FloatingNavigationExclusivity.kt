package com.waenhancer.ui.screens.settings

import com.waenhancer.config.ExclusivePreferences

/**
 * WhatsApp's native floating bottom bar and the module's own floating bar both restyle the same
 * navigation view, so turning one on turns the other off.
 */
object FloatingNavigationExclusivity {
    const val MODULE_BAR = ExclusivePreferences.MODULE_FLOATING_BAR
    const val NATIVE_BAR = ExclusivePreferences.NATIVE_FLOATING_NAVIGATION

    /** The key that must be switched off when [key] is switched on, or null when none. */
    @JvmStatic
    fun counterpart(key: String): String? = ExclusivePreferences.partnerOf(key)
}
