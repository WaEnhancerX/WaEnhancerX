package com.waenhancer.ui.screens.settings

/**
 * WhatsApp's native floating bottom bar and the module's own floating bar both restyle the same
 * navigation view, so turning one on turns the other off.
 */
object FloatingNavigationExclusivity {
    const val MODULE_BAR = "floating_bottom_bar"
    const val NATIVE_BAR = "native_floating_navigation"

    /** The key that must be switched off when [key] is switched on, or null when none. */
    @JvmStatic
    fun counterpart(key: String): String? = when (key) {
        MODULE_BAR -> NATIVE_BAR
        NATIVE_BAR -> MODULE_BAR
        else -> null
    }
}
