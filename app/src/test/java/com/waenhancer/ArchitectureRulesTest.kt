package com.waenhancer

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automated architecture enforcement test for WA Enhancer X.
 * Enforces layer boundaries and isolation constraints.
 */
class ArchitectureRulesTest {

    private val layerRules: Map<String, Set<String>> = mapOf(
        "core" to setOf("com.waenhancer.features", "com.waenhancer.ui", "com.waenhancer.hooks", "com.waenhancer.plugins"),
        "features" to setOf("com.waenhancer.core", "com.waenhancer.hooks", "com.waenhancer.ui", "com.waenhancer.plugins", "com.waenhancer.compatibility"),
        "ui" to setOf("com.waenhancer.core", "com.waenhancer.hooks", "com.waenhancer.plugins", "com.waenhancer.compatibility"),
        "hooks" to setOf("com.waenhancer.features", "com.waenhancer.ui", "com.waenhancer.plugins", "com.waenhancer.compatibility"),
        "api" to setOf("com.waenhancer.core", "com.waenhancer.hooks", "com.waenhancer.features", "com.waenhancer.ui", "com.waenhancer.plugins", "com.waenhancer.compatibility"),
        "plugins" to setOf("com.waenhancer.core", "com.waenhancer.hooks", "com.waenhancer.features", "com.waenhancer.ui", "com.waenhancer.compatibility"),
        "app" to setOf("com.waenhancer.core", "com.waenhancer.hooks", "com.waenhancer.plugins", "com.waenhancer.compatibility"),
        "compatibility" to setOf("com.waenhancer.core", "com.waenhancer.features", "com.waenhancer.hooks", "com.waenhancer.ui", "com.waenhancer.plugins", "com.waenhancer.app")
    )

    @Test
    fun `core must not import forbidden packages`() {
        assertViolation("core", "com.waenhancer.features.Placeholder")
        assertViolation("core", "com.waenhancer.ui.Placeholder")
        assertViolation("core", "com.waenhancer.hooks.Placeholder")
        assertViolation("core", "com.waenhancer.plugins.Placeholder")
    }

    @Test
    fun `features must not import forbidden packages`() {
        assertViolation("features", "com.waenhancer.core.Placeholder")
        assertViolation("features", "com.waenhancer.hooks.Placeholder")
        assertViolation("features", "com.waenhancer.ui.Placeholder")
        assertViolation("features", "com.waenhancer.compatibility.Placeholder")
    }

    @Test
    fun `ui must not import forbidden packages`() {
        assertViolation("ui", "com.waenhancer.core.Placeholder")
        assertViolation("ui", "com.waenhancer.hooks.Placeholder")
        assertViolation("ui", "com.waenhancer.compatibility.Placeholder")
    }

    @Test
    fun `compatibility must not import forbidden packages`() {
        assertViolation("compatibility", "com.waenhancer.core.Placeholder")
        assertViolation("compatibility", "com.waenhancer.features.Placeholder")
        assertViolation("compatibility", "com.waenhancer.hooks.Placeholder")
        assertViolation("compatibility", "com.waenhancer.ui.Placeholder")
        assertViolation("compatibility", "com.waenhancer.plugins.Placeholder")
        assertViolation("compatibility", "com.waenhancer.app.Placeholder")
    }

    @Test
    fun `allowed imports check`() {
        assertAllowed("core", "com.waenhancer.api.contracts.Placeholder")
        assertAllowed("core", "com.waenhancer.compatibility.Placeholder")
        assertAllowed("features", "com.waenhancer.api.contracts.Placeholder")
        assertAllowed("ui", "com.waenhancer.api.contracts.Placeholder")
        assertAllowed("hooks", "com.waenhancer.api.contracts.Placeholder")
        assertAllowed("hooks", "com.waenhancer.core.Placeholder")
        assertAllowed("compatibility", "com.waenhancer.api.contracts.Placeholder")
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
