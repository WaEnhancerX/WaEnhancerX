package com.waenhancer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateChannelTest {
    private fun code(name: String) = UpdateChannel.versionCodeOf(name)!!
    private fun release(tag: String) = WaexRelease(tag, tag, "", "", "")

    @Test fun versionCodeVectors() {
        assertEquals(20009999L, code("2.0.9"))
        assertEquals(20009501L, code("2.0.9-beta-1"))
        assertEquals(20009503L, code("2.0.9-beta-3"))
        assertEquals(20009001L, code("2.0.9-nightly.1"))
        assertEquals(20009012L, code("2.0.9-nightly.12"))
        assertEquals(20010001L, code("2.0.10-nightly.1"))
    }

    @Test fun orderingAcrossChannels() {
        assertTrue(code("2.0.9-nightly.499") < code("2.0.9-beta-1"))
        assertTrue(code("2.0.9-beta-498") < code("2.0.9"))
        assertTrue(code("2.0.9-nightly.12") > code("2.0.9-nightly.3"))
        assertTrue(code("2.0.9-nightly.12") < code("2.0.9-beta-3"))
        assertTrue(code("2.0.10-nightly.1") > code("2.0.9"))
        assertTrue(code("2.0.9-nightly.1") > 20800L)
        assertTrue(code("2.0.9-nightly.1") > 17113L)
    }

    @Test fun invalidSlotsAndBasesAreRejected() {
        for (name in listOf("2.0.9-nightly.0", "2.0.9-nightly.500", "2.0.9-beta-0", "2.0.9-beta-499",
            "2.100.0", "2.0.100", "210.0.0", "2.0.9-nightly.x", "garbage")) {
            assertNull(name, UpdateChannel.versionCodeOf(name))
        }
    }

    @Test fun channelParsing() {
        assertEquals(UpdateChannel.NIGHTLY, UpdateChannel.fromVersionName("2.0.9-nightly.12+0123456"))
        assertEquals(UpdateChannel.BETA, UpdateChannel.fromVersionName("2.0.9-beta-3"))
        assertEquals(UpdateChannel.BETA, UpdateChannel.fromVersionName("1.7.1-beta-10"))
        assertEquals(UpdateChannel.BETA, UpdateChannel.fromVersionName("2.0.9-rc-2"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromVersionName("2.0.9"))
        assertEquals(code("2.0.9-nightly.12"), UpdateChannel.versionCodeOf("2.0.9-nightly.12+0123456"))
    }

    @Test fun selectionFallsBackToInstalledChannel() {
        assertEquals(UpdateChannel.NIGHTLY, UpdateChannel.resolveSelected(null, "2.0.9-nightly.1"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.resolveSelected("bogus", "2.0.9"))
        assertEquals(UpdateChannel.BETA, UpdateChannel.resolveSelected("beta", "2.0.9"))
    }

    @Test fun legacyInstallUsesNameDerivedCode() {
        val legacy = InstalledBuild.of("2.0.3", 20300L)
        assertEquals(code("2.0.3"), legacy.versionCode)
        assertNull(ReleaseRepository.findUpdate(listOf(release("20003999-2.0.3")), legacy.versionName, legacy.versionCode, UpdateChannel.STABLE))
    }

    @Test fun updateSelectionPerChannel() {
        val releases = listOf(release("2.0.9"), release("2.0.9-beta-3"), release("2.0.10-nightly.2"), release("2.0.8-nightly.5"))
        val stableUser = InstalledBuild.of("2.0.8", 0)
        assertEquals("2.0.9", ReleaseRepository.findUpdate(releases, stableUser.versionName, stableUser.versionCode, UpdateChannel.STABLE)?.tagName)
        assertEquals("2.0.9", ReleaseRepository.findUpdate(releases, stableUser.versionName, stableUser.versionCode, UpdateChannel.BETA)?.tagName)
        assertEquals("2.0.10-nightly.2", ReleaseRepository.findUpdate(releases, stableUser.versionName, stableUser.versionCode, UpdateChannel.NIGHTLY)?.tagName)
        val onlyPre = listOf(release("2.0.9-beta-3"), release("2.0.9-nightly.4"))
        assertNull(ReleaseRepository.findUpdate(onlyPre, "2.0.8", code("2.0.8"), UpdateChannel.STABLE))
        assertEquals("2.0.9-beta-3", ReleaseRepository.findUpdate(onlyPre, "2.0.8", code("2.0.8"), UpdateChannel.BETA)?.tagName)
    }

    @Test fun nightlyUserIsOfferedMatchingStable() {
        val releases = listOf(release("2.0.9"), release("2.0.9-nightly.12"))
        val found = ReleaseRepository.findUpdate(releases, "2.0.9-nightly.12", code("2.0.9-nightly.12"), UpdateChannel.NIGHTLY)
        assertEquals("2.0.9", found?.tagName)
    }

    @Test fun invalidSlotReleaseNeverOffered() {
        assertEquals(0L, release("2.0.9-nightly.500").versionCode)
        assertNull(ReleaseRepository.findUpdate(listOf(release("2.0.9-nightly.500")), "2.0.8", code("2.0.8"), UpdateChannel.NIGHTLY))
    }

    @Test fun lessStableChannelNeedsConfirmationOnly() {
        assertTrue(UpdateChannel.requiresRiskConfirmation(UpdateChannel.STABLE, UpdateChannel.BETA))
        assertTrue(UpdateChannel.requiresRiskConfirmation(UpdateChannel.STABLE, UpdateChannel.NIGHTLY))
        assertTrue(UpdateChannel.requiresRiskConfirmation(UpdateChannel.BETA, UpdateChannel.NIGHTLY))
        assertFalse(UpdateChannel.requiresRiskConfirmation(UpdateChannel.NIGHTLY, UpdateChannel.STABLE))
        assertFalse(UpdateChannel.requiresRiskConfirmation(UpdateChannel.BETA, UpdateChannel.BETA))
    }
}
