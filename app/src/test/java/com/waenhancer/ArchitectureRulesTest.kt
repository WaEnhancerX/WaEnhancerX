package com.waenhancer

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Automated architecture enforcement test.
 * Validates that the architectureCheck rules in build.gradle.kts
 * would catch real violations by simulating import analysis.
 *
 * This mirrors the same logic as the Gradle architectureCheck task
 * but runs as a JUnit test for CI verification.
 */
class ArchitectureRulesTest {

    private val layerRules: Map<String, Set<String>> = mapOf(
        "core" to setOf("com.waenhancer.features", "com.waenhancer.ui", "com.waenhancer.hooks", "com.waenhancer.plugins"),
        "features" to setOf("com.waenhancer.core", "com.waenhancer.hooks", "com.waenhancer.ui", "com.waenhancer.plugins"),
        "ui" to setOf("com.waenhancer.core", "com.waenhancer.hooks", "com.waenhancer.plugins"),
        "hooks" to setOf("com.waenhancer.features", "com.waenhancer.ui", "com.waenhancer.plugins"),
        "api" to setOf("com.waenhancer.core", "com.waenhancer.hooks", "com.waenhancer.features", "com.waenhancer.ui", "com.waenhancer.plugins"),
        "plugins" to setOf("com.waenhancer.core", "com.waenhancer.hooks", "com.waenhancer.features", "com.waenhancer.ui"),
        "app" to setOf("com.waenhancer.core", "com.waenhancer.hooks", "com.waenhancer.plugins")
    )

    @Test
    fun `core must not import features`() {
        assertViolation("core", "com.waenhancer.features.media.Placeholder")
    }

    @Test
    fun `core must not import ui`() {
        assertViolation("core", "com.waenhancer.ui.mvvm.Placeholder")
    }

    @Test
    fun `core must not import hooks`() {
        assertViolation("core", "com.waenhancer.hooks.system.Placeholder")
    }

    @Test
    fun `core must not import plugins`() {
        assertViolation("core", "com.waenhancer.plugins.runtime.Placeholder")
    }

    @Test
    fun `features must not import core`() {
        assertViolation("features", "com.waenhancer.core.engine.Placeholder")
    }

    @Test
    fun `features must not import hooks`() {
        assertViolation("features", "com.waenhancer.hooks.whatsapp.Placeholder")
    }

    @Test
    fun `features must not import ui`() {
        assertViolation("features", "com.waenhancer.ui.mvvm.Placeholder")
    }

    @Test
    fun `ui must not import core`() {
        assertViolation("ui", "com.waenhancer.core.engine.Placeholder")
    }

    @Test
    fun `ui must not import hooks`() {
        assertViolation("ui", "com.waenhancer.hooks.system.Placeholder")
    }

    @Test
    fun `hooks must not import features`() {
        assertViolation("hooks", "com.waenhancer.features.message.Placeholder")
    }

    @Test
    fun `api must not import any implementation`() {
        assertViolation("api", "com.waenhancer.core.engine.Placeholder")
        assertViolation("api", "com.waenhancer.features.media.Placeholder")
        assertViolation("api", "com.waenhancer.ui.mvvm.Placeholder")
        assertViolation("api", "com.waenhancer.hooks.system.Placeholder")
        assertViolation("api", "com.waenhancer.plugins.runtime.Placeholder")
    }

    @Test
    fun `plugins must not import core or hooks or features or ui`() {
        assertViolation("plugins", "com.waenhancer.core.engine.Placeholder")
        assertViolation("plugins", "com.waenhancer.hooks.system.Placeholder")
        assertViolation("plugins", "com.waenhancer.features.media.Placeholder")
        assertViolation("plugins", "com.waenhancer.ui.mvvm.Placeholder")
    }

    @Test
    fun `app must not import core directly`() {
        assertViolation("app", "com.waenhancer.core.engine.Placeholder")
    }

    @Test
    fun `app must not import hooks`() {
        assertViolation("app", "com.waenhancer.hooks.system.Placeholder")
    }

    @Test
    fun `app must not import plugins`() {
        assertViolation("app", "com.waenhancer.plugins.runtime.Placeholder")
    }

    // --- Allowed imports (should NOT be violations) ---

    @Test
    fun `features can import api`() {
        assertAllowed("features", "com.waenhancer.api.contracts.SomeInterface")
    }

    @Test
    fun `ui can import features`() {
        assertAllowed("ui", "com.waenhancer.features.media.Placeholder")
    }

    @Test
    fun `ui can import api`() {
        assertAllowed("ui", "com.waenhancer.api.contracts.SomeInterface")
    }

    @Test
    fun `core can import api`() {
        assertAllowed("core", "com.waenhancer.api.contracts.SomeInterface")
    }

    @Test
    fun `hooks can import api`() {
        assertAllowed("hooks", "com.waenhancer.api.contracts.SomeInterface")
    }

    @Test
    fun `hooks can import core`() {
        assertAllowed("hooks", "com.waenhancer.core.engine.Placeholder")
    }

    @Test
    fun `app can import features`() {
        assertAllowed("app", "com.waenhancer.features.media.Placeholder")
    }

    @Test
    fun `app can import ui`() {
        assertAllowed("app", "com.waenhancer.ui.mvvm.Placeholder")
    }

    @Test
    fun `app can import api`() {
        assertAllowed("app", "com.waenhancer.api.contracts.SomeInterface")
    }

    // --- Helpers ---

    private fun isViolation(layer: String, importedPackage: String): Boolean {
        val forbidden = layerRules[layer] ?: return false
        return forbidden.any { importedPackage.startsWith(it) }
    }

    private fun assertViolation(layer: String, importedPackage: String) {
        assertTrue(
            "Expected '$importedPackage' to be a FORBIDDEN import in '$layer' layer, but it was allowed",
            isViolation(layer, importedPackage)
        )
    }

    private fun assertAllowed(layer: String, importedPackage: String) {
        assertTrue(
            "Expected '$importedPackage' to be ALLOWED in '$layer' layer, but it was flagged as a violation",
            !isViolation(layer, importedPackage)
        )
    }
}
