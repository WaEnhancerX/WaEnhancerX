package com.waenhancer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseRepositoryTest {

    @Test
    fun `official repository tags expose semantic version`() {
        assertEquals("2.0.3", ReleaseRepository.versionFromTag("20300-2.0.3"))
        assertEquals("2.0.0-beta-3", ReleaseRepository.versionFromTag("2003-2.0.0-beta-3"))
        assertEquals("3.2.1", ReleaseRepository.versionFromTag("v3.2.1"))
    }

    @Test
    fun `only official repository APK assets are accepted`() {
        assertTrue(ReleaseRepository.isAllowedAssetUrl(
            "https://github.com/Xposed-Modules-Repo/com.waenhancer/releases/download/20300-2.0.3/WaEnhancerX-v2.0.3_release.apk"
        ))
        assertFalse(ReleaseRepository.isAllowedAssetUrl(
            "https://github.com/another-owner/another-repo/releases/download/2.0.3/WaEnhancerX-v2.0.3_release.apk"
        ))
        assertFalse(ReleaseRepository.isAllowedAssetUrl("https://waex.mubashar.dev/update.apk"))
    }

    @Test
    fun `asset version must match its release`() {
        assertTrue(ReleaseRepository.assetMatchesRelease(
            "WaEnhancerX-v2.0.2_release.apk",
            "2.0.2"
        ))
        assertTrue(ReleaseRepository.assetMatchesRelease(
            "WaEnhancerX-v2.0.0-beta-3_debug.apk",
            "2.0.0-beta-3"
        ))
        assertFalse(ReleaseRepository.assetMatchesRelease(
            "WaEnhancerX-v2.0.1_release.apk",
            "2.0.2"
        ))
    }
    private fun release(tag: String) = WaexRelease(tag, tag, "", "", "")

    @Test
    fun newerVersionOutranksOlderVersion() {
        assertTrue(ReleaseRepository.versionCode("3.2.1") > ReleaseRepository.versionCode("3.2.0"))
        assertTrue(ReleaseRepository.versionCode("3.3.0") > ReleaseRepository.versionCode("3.2.9"))
        assertTrue(ReleaseRepository.versionCode("4.0.0") > ReleaseRepository.versionCode("3.99.99"))
        assertTrue(ReleaseRepository.versionCode("1.6.9-beta-9") > ReleaseRepository.versionCode("1.6.9-beta-8"))
        assertTrue(ReleaseRepository.versionCode("1.6.9") > ReleaseRepository.versionCode("1.6.9-beta-9"))
    }

    @Test
    fun selectsNewestEligibleBuild() {
        val releases = listOf(release("1.6.8"), release("1.6.9-beta-9"), release("1.6.9"))
        assertEquals("1.6.9", ReleaseRepository.findUpdate(releases, "1.6.8")?.tagName)
        // A beta build only follows its own channel, so the stable 1.6.9 is not offered automatically.
        assertNull(ReleaseRepository.findUpdate(releases, "1.6.9-beta-9"))
        assertEquals("1.6.9", ReleaseRepository.findUpdate(releases, "1.6.9-beta-9", selected = UpdateChannel.STABLE)?.tagName)
    }

    @Test
    fun sameOrOlderBuildIsNotAnUpdate() {
        assertNull(ReleaseRepository.findUpdate(listOf(release("v3.2.0")), "3.2.0+42"))
        assertNull(ReleaseRepository.findUpdate(listOf(release("v3.1.0")), "3.2.0"))
        assertNull(ReleaseRepository.findUpdate(listOf(release("2.0.0-beta-3")), "2.0.1"))
    }

    @Test
    fun assetMatchingToleratesBuildMetadataButNotWrongVersions() {
        assertTrue(ReleaseRepository.assetMatchesRelease("WaEnhancerX-v2.0.9-nightly.12_release.apk", "2.0.9-nightly.12"))
        assertTrue(ReleaseRepository.assetMatchesRelease("WaEnhancerX-v2.0.9-nightly.12+0123456_release.apk", "2.0.9-nightly.12"))
        assertFalse(ReleaseRepository.assetMatchesRelease("WaEnhancerX-v2.0.9-nightly.1_release.apk", "2.0.9-nightly.12"))
        assertFalse(ReleaseRepository.assetMatchesRelease("WaEnhancerX-v2.0.9-nightly.12+0123456_release.apk", "2.0.9-beta-3"))
        assertFalse(ReleaseRepository.assetMatchesRelease("WaEnhancerX-v2.0.9-beta-3_release.apk", "2.0.9"))
    }
}
