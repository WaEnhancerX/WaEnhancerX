package com.waenhancer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseRepositoryTest {
    private fun release(tag: String) = WaexRelease(tag, tag, "", "", "", null, 0)

    @Test
    fun stableOutranksBetasOfSameVersion() {
        assertTrue(ReleaseRepository.versionCode("3.2.0") > ReleaseRepository.versionCode("3.2.0-beta-99"))
    }

    @Test
    fun channelsSelectTheirNewestEligibleBuild() {
        val releases = listOf(release("3.2.0"), release("3.3.0-beta-2"), release("3.3.0-beta-1"))
        assertEquals("3.2.0", ReleaseRepository.findUpdate(releases, "3.1.0", ReleaseChannel.STABLE)?.tagName)
        assertEquals("3.3.0-beta-2", ReleaseRepository.findUpdate(releases, "3.1.0", ReleaseChannel.BETA)?.tagName)
    }

    @Test
    fun sameOrOlderBuildIsNotAnUpdate() {
        assertNull(ReleaseRepository.findUpdate(listOf(release("v3.2.0")), "3.2.0+42", ReleaseChannel.STABLE))
    }
}
