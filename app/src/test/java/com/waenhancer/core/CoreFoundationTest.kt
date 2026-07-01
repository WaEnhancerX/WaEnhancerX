package com.waenhancer.core

import android.content.SharedPreferences
import com.waenhancer.api.contracts.WaexFeature
import com.waenhancer.api.contracts.WaexFeatureMetadata
import com.waenhancer.core.dashboard.WaexDashboardProviderImpl
import com.waenhancer.core.feature.GenericWaexFeature
import com.waenhancer.core.feature.WaexFeatureRegistryImpl
import com.waenhancer.core.licensing.WaexLicenseManagerImpl
import com.waenhancer.core.preferences.WaexPreferenceManagerImpl
import com.waenhancer.core.search.WaexSearchEngineImpl
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CoreFoundationTest {

    private lateinit var mockPrefs: FakeSharedPreferences
    private lateinit var preferenceManager: WaexPreferenceManagerImpl

    @Before
    fun setUp() {
        mockPrefs = FakeSharedPreferences()
        preferenceManager = WaexPreferenceManagerImpl(mockPrefs)
    }

    @Test
    fun testPreferenceNamespaceValidation() {
        // Valid namespace
        preferenceManager.putBoolean("privacy.testKey", true)
        assertTrue(preferenceManager.getBoolean("privacy.testKey", false))

        // Invalid namespace should throw IllegalArgumentException
        try {
            preferenceManager.putBoolean("invalidNamespace.testKey", true)
            fail("Expected IllegalArgumentException for invalid namespace")
        } catch (e: IllegalArgumentException) {
            // Passed
        }

        // Flat key without dot should throw IllegalArgumentException
        try {
            preferenceManager.putBoolean("flatKey", true)
            fail("Expected IllegalArgumentException for flat key")
        } catch (e: IllegalArgumentException) {
            // Passed
        }
    }

    @Test
    fun testFeatureRegistryAndGrouping() {
        val registry = WaexFeatureRegistryImpl()
        
        val meta1 = WaexFeatureMetadata(
            "privacy.hideSeen", "Hide Seen", "Desc", "privacy", "icon", false, false, 0, "VISIBLE"
        )
        val meta2 = WaexFeatureMetadata(
            "media.fileSpoofer", "File Spoofer", "Desc", "media", "icon", false, false, 0, "VISIBLE"
        )
        
        val feat1 = GenericWaexFeature(meta1, preferenceManager)
        val feat2 = GenericWaexFeature(meta2, preferenceManager)

        registry.registerFeature(feat1)
        registry.registerFeature(feat2)

        assertEquals(feat1, registry.lookupFeature("privacy.hideSeen"))
        assertEquals(2, registry.features.size)

        val grouped = registry.featuresByCategory
        assertEquals(1, grouped["privacy"]?.size)
        assertEquals(1, grouped["media"]?.size)
    }

    @Test
    fun testLicenseActivationFlow() {
        val licenseManager = WaexLicenseManagerImpl(preferenceManager)
        
        assertFalse(licenseManager.isProActivated)

        licenseManager.activateLicense("VALID-KEY-123")
        assertTrue(licenseManager.isProActivated)
        assertEquals("VALID-KEY-123", licenseManager.licenseKey)
    }

    @Test
    fun testDashboardProviderFiltering() {
        val registry = WaexFeatureRegistryImpl()
        val provider = WaexDashboardProviderImpl(registry)

        val metaVisible = WaexFeatureMetadata(
            "privacy.visible", "Visible", "Desc", "privacy", "icon", false, false, 0, "VISIBLE"
        )
        val metaHidden = WaexFeatureMetadata(
            "privacy.hidden", "Hidden", "Desc", "privacy", "icon", false, false, 0, "HIDDEN"
        )

        registry.registerFeature(GenericWaexFeature(metaVisible, preferenceManager))
        registry.registerFeature(GenericWaexFeature(metaHidden, preferenceManager))

        val visibleList = provider.visibleFeatures
        assertEquals(1, visibleList.size)
        assertEquals("privacy.visible", visibleList[0].metadata.id)
    }

    @Test
    fun testSearchEngineKeywordMatching() {
        val registry = WaexFeatureRegistryImpl()
        val searchEngine = WaexSearchEngineImpl(registry)

        val meta1 = WaexFeatureMetadata(
            "privacy.blueTicks", "Blue Ticks Privacy", "Control read receipts", "privacy", "icon", false, false, 0, "VISIBLE"
        )
        val meta2 = WaexFeatureMetadata(
            "media.spoofer", "Size Spoofer", "Modify upload file parameters", "media", "icon", false, false, 0, "VISIBLE"
        )

        registry.registerFeature(GenericWaexFeature(meta1, preferenceManager))
        registry.registerFeature(GenericWaexFeature(meta2, preferenceManager))

        val results = searchEngine.search("ticks")
        assertEquals(1, results.size)
        assertEquals("privacy.blueTicks", results[0].metadata.id)

        val resultsCategory = searchEngine.search("media")
        assertEquals(1, resultsCategory.size)
        assertEquals("media.spoofer", resultsCategory[0].metadata.id)
    }

    // --- Manual mock implementation of SharedPreferences ---
    private class FakeSharedPreferences : SharedPreferences {
        val map = mutableMapOf<String, Any>()

        override fun getAll(): Map<String, *> = map
        
        override fun getString(key: String, defValue: String?): String? {
            return (map[key] as? String) ?: defValue
        }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
            return (map[key] as? Set<String>) ?: defValues
        }

        override fun getInt(key: String, defValue: Int): Int {
            return (map[key] as? Int) ?: defValue
        }

        override fun getLong(key: String, defValue: Long): Long {
            return (map[key] as? Long) ?: defValue
        }

        override fun getFloat(key: String, defValue: Float): Float {
            return (map[key] as? Float) ?: defValue
        }

        override fun getBoolean(key: String, defValue: Boolean): Boolean {
            return (map[key] as? Boolean) ?: defValue
        }

        override fun contains(key: String): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(this)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class FakeEditor(val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
        val tempMap = mutableMapOf<String, Any>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            if (value != null) tempMap[key] = value else tempMap.remove(key)
            return this
        }

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
            if (values != null) tempMap[key] = values else tempMap.remove(key)
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            tempMap[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            tempMap[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            tempMap[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            tempMap[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            tempMap.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            tempMap.clear()
            return this
        }

        override fun commit(): Boolean {
            prefs.map.putAll(tempMap)
            return true
        }

        override fun apply() {
            prefs.map.putAll(tempMap)
        }
    }
}
