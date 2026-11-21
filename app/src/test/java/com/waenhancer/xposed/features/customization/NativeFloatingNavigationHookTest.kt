package com.waenhancer.xposed.features.customization

import android.content.SharedPreferences
import com.waenhancer.ui.screens.settings.FloatingNavigationExclusivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class NativeFloatingNavigationHookTest {

    @Test
    fun activeOnlyWhenEnabledAndModuleBarIsOff() {
        assertTrue(NativeFloatingNavigationHook.isActive(prefs("native_floating_navigation" to true)))
        assertFalse(NativeFloatingNavigationHook.isActive(prefs()))
        assertFalse(
            NativeFloatingNavigationHook.isActive(
                prefs("native_floating_navigation" to true, "floating_bottom_bar" to true)
            )
        )
    }

    @Test
    fun floatingBarsAreMutuallyExclusive() {
        assertEquals("native_floating_navigation", FloatingNavigationExclusivity.counterpart("floating_bottom_bar"))
        assertEquals("floating_bottom_bar", FloatingNavigationExclusivity.counterpart("native_floating_navigation"))
        assertNull(FloatingNavigationExclusivity.counterpart("custom_theme"))
    }

    @Test
    fun overridesWhatsAppFloatingNavigationFlag() {
        assertEquals(36795, NativeFloatingNavigationHook.FLOATING_NAVIGATION_PROP)
    }

    private fun prefs(vararg values: Pair<String, Boolean>): SharedPreferences {
        val map = values.toMap()
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getBoolean" -> map[args[0] as String] ?: args[1]
                "getInt" -> args[1]
                else -> null
            }
        } as SharedPreferences
    }
}
