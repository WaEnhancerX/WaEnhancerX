package com.waenhancer.core

import com.waenhancer.api.contracts.WaexPreferenceGroup
import com.waenhancer.api.contracts.WaexPreferenceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WaexPreferenceSystemTest {

    @Test
    fun testValidPreferenceKeyCreation() {
        val key = WaexPreferenceKey.boolean("privacy.enableAntiRevoke", false)
        assertEquals("privacy.enableAntiRevoke", key.key)
        assertEquals(false, key.defaultValue)
    }

    @Test
    fun testInvalidCategoryNamespaceThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            WaexPreferenceKey.boolean("invalidNamespace.someKey", false)
        }
    }

    @Test
    fun testInvalidKeyNameThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            WaexPreferenceKey.boolean("privacy.EnableAntiRevoke", false) // Uppercase first letter
        }
        assertThrows(IllegalArgumentException::class.java) {
            WaexPreferenceKey.boolean("privacy.", false) // Empty keyName
        }
    }

    @Test
    fun testPreferenceGroupValidation() {
        val key1 = WaexPreferenceKey.boolean("privacy.keyOne", false)
        val key2 = WaexPreferenceKey.string("privacy.keyTwo", "default")
        
        val group = WaexPreferenceGroup("Privacy Group", "privacy", listOf(key1, key2))
        assertEquals("Privacy Group", group.name)
        assertEquals("privacy", group.category)
        assertEquals(2, group.keys.size)
    }

    @Test
    fun testPreferenceGroupMismatchedCategoryThrows() {
        val key1 = WaexPreferenceKey.boolean("privacy.keyOne", false)
        val key2 = WaexPreferenceKey.string("media.keyTwo", "default")
        
        assertThrows(IllegalArgumentException::class.java) {
            WaexPreferenceGroup("Privacy Group", "privacy", listOf(key1, key2))
        }
    }
}
