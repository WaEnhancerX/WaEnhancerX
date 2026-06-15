package com.waenhancer.core.engine;

// DELIBERATE ARCHITECTURE VIOLATION — used by architectureCheck verification
// This import crosses the core→features boundary which is forbidden.
// Uncomment the line below and run `./gradlew architectureCheck` to verify enforcement.

// import com.waenhancer.features.media.Placeholder;  // ← VIOLATION: core must not import features

/**
 * Verification steps:
 * 1. Uncomment the import above
 * 2. Run: ./gradlew architectureCheck
 * 3. Expected: GradleException with "Forbidden import 'com.waenhancer.features.media.Placeholder' in 'core' layer"
 * 4. Re-comment the import after verification
 */
public final class ArchitectureViolationTest {
    // This file exists solely to document and enable manual enforcement verification.
    // The import is commented out so the build remains green in CI.
}
