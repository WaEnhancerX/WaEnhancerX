package com.waenhancer.utils

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics
import java.util.Locale
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual UiText + real compiled Resources; contexts adapt an isolated test APK. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class LocalizationResourcesTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private fun prefs(code: String): SharedPreferences = app.getSharedPreferences("locale-test", 0).also {
        assertTrue(it.edit().clear().putString("app_language", code).commit())
    }
    private fun module(): Context {
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val cookie = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
            .invoke(assets, System.getProperty("waex.locale.apk")) as Int
        assertTrue("The real compiled locale table must be loaded", cookie > 0)
        fun configured(configuration: Configuration): Context = object : ContextWrapper(app) {
            private val table = Resources(assets, DisplayMetrics().apply { setToDefaults() }, configuration)
            override fun getResources(): Resources = table
            override fun getPackageName(): String = "com.waenhancer"
            override fun getApplicationContext(): Context = this
            override fun createConfigurationContext(overrideConfiguration: Configuration): Context =
                configured(Configuration(overrideConfiguration))
        }
        return configured(Configuration().apply { setLocale(Locale.ENGLISH) })
    }
    private fun id(c: Context, name: String, type: String = "string"): Int =
        c.resources.getIdentifier(name, type, "com.waenhancer").also {
            assertTrue("Missing $type/$name in the actual compiled table", it != 0)
            assertEquals("com.waenhancer", c.resources.getResourcePackageName(it))
        }
    @After fun releaseTestContext() { UiText.initialize(app, null) }

    @Test fun explicitAndLegacySpanishUseCompiledValues() {
        val c = module()
        for (tag in listOf("es-419", "es")) {
            UiText.initialize(c, prefs(tag))
            assertEquals("es-419", UiText.displayLocale().toLanguageTag())
            assertEquals("en línea", UiText.get(id(c, "waex_ui_online"), "online"))
            assertEquals("No se pudo guardar el ajuste", UiText.get(id(c, "waex_ui_unable_to_save_setting"), "Unable to save setting"))
        }
    }
    @Test fun preferenceChangesInvalidateLocaleCacheWithoutChangingGlobalLocale() {
        val original = Locale.getDefault()
        val c = module(); val p = prefs("es-419")
        UiText.initialize(c, p)
        val key = id(c, "waex_ui_online")
        assertEquals("en línea", UiText.get(key, "online"))
        assertTrue(p.edit().putString("app_language", "en").commit())
        assertEquals("online", UiText.get(key, "online"))
        assertTrue(p.edit().putString("app_language", "es-419").commit())
        assertEquals("en línea", UiText.get(key, "online"))
        assertEquals(original, Locale.getDefault())
    }
    @Test fun formattingAndPluralSelectionUseRealResources() {
        val c = module(); UiText.initialize(c, prefs("es-419"))
        assertEquals("última conexión: 18:05", UiText.get(id(c, "waex_ui_last_seen_1_s"), "last seen %1\$s", "18:05"))
        val plural = id(c, "waex_recordings_deleted", "plurals")
        assertEquals("Se eliminó 1 grabación", UiText.quantity(plural, 1, "Deleted %1\$d recordings", 1))
        assertEquals("Se eliminaron 2 grabaciones", UiText.quantity(plural, 2, "Deleted %1\$d recordings", 2))
    }
    @Test fun visibleHostResolvesOnlyTheModuleResourcePackage() {
        val c = module()
        val host = object : ContextWrapper(c) {
            override fun getPackageName() = "com.whatsapp"
            override fun getApplicationContext(): Context = this
            override fun createPackageContext(name: String, flags: Int): Context {
                assertEquals("com.waenhancer", name); return c
            }
        }
        UiText.initialize(host, prefs("es-419"))
        assertEquals("en línea", UiText.get(id(c, "waex_ui_online"), "online"))
        // android.R ids refer to a different table owner, never a translated module key.
        assertEquals("explicit fallback", UiText.get(android.R.string.ok, "explicit fallback"))
    }
    @Test fun hiddenResourcesAndUnknownIdRetainExplicitSafeFallback() {
        val c = module()
        val hidden = object : ContextWrapper(c) {
            override fun getPackageName() = "com.whatsapp"
            override fun getApplicationContext(): Context = this
            override fun createPackageContext(name: String, flags: Int): Context = throw PackageManager.NameNotFoundException()
        }
        UiText.initialize(hidden, prefs("es-419"))
        assertEquals("online", UiText.get(id(c, "waex_ui_online"), "online"))
        UiText.initialize(c, prefs("es-419"))
        assertEquals("missing", UiText.get(0, "missing"))
    }
    @Test fun locallyGeneratedHmaReasonsAreLocalizedWithoutChangingTemplateNames() {
        val c = module(); UiText.initialize(c, prefs("es-419"))
        // AGP library R IDs and the independently linked resource-only APK are
        // different namespaces of numeric bindings. Bind the actual helper's
        // non-final library fields to that table for this fixture, then restore.
        val names = listOf("not_configured", "visible", "allowlist_invisible",
            "allowlist_missing", "blocklist_extra", "template", "preset")
        val resourceClass = Class.forName("com.waenhancer.app.R\$string")
        val fields = names.map { resourceClass.getField("waex_ui_hma_reason_" + it) }
        val saved = fields.map { it.getInt(null) }
        try {
            fields.forEach { it.setInt(null, id(c, it.name)) }
            assertEquals("No está incluida en el ámbito de HMA", InternalLabels.hmaReason("Not configured in HMA scope"))
            assertEquals("Activa en HMA; el módulo es visible", InternalLabels.hmaReason("Active in HMA, module visible"))
            assertEquals("Oculto por el ajuste predefinido «xposed» de HMA", InternalLabels.hmaReason("Hidden by HMA \"xposed\" preset"))
            val name = "Mine %1\$s \" 50%"
            assertEquals("Oculto por la plantilla de bloqueo «$name»", InternalLabels.hmaReason("Hidden by blacklist template \"$name\""))
            assertEquals("unknown reason", InternalLabels.hmaReason("unknown reason"))
            assertEquals("Hidden by blacklist template \"unfinished", InternalLabels.hmaReason("Hidden by blacklist template \"unfinished"))
        } finally {
            fields.forEachIndexed { index, field -> field.setInt(null, saved[index]) }
        }
    }
    @Test fun systemLanguageAndUnreadablePreferenceDoNotMutateSettings() {
        val c = module(); val p = prefs("system")
        UiText.initialize(c, p)
        assertEquals(Resources.getSystem().configuration.locales[0], UiText.displayLocale())
        assertEquals("system", p.getString("app_language", null))
        assertTrue(p.edit().putInt("app_language", 42).commit())
        assertEquals(Resources.getSystem().configuration.locales[0], UiText.displayLocale())
        assertEquals(42, p.getInt("app_language", 0))
    }
}
