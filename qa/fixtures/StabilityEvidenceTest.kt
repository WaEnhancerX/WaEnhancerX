package com.waenhancer.fixture

import com.waenhancer.utils.InjectionEvidence
import com.waenhancer.utils.classifyInjection
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the real PID/boot classifier in the Android framework fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StabilityEvidenceTest {
    private fun evidence(
        active: Boolean = true, reportedBoot: Int = 7, actualBoot: Int = 7,
        reportedPid: Int = 123, runningPids: Set<Int>? = setOf(123),
        observedAt: Long = 1L
    ) = classifyInjection(active, reportedBoot, actualBoot, reportedPid, runningPids, observedAt)

    @Test fun matchingPidAndBootAreConfirmed() =
        assertEquals(InjectionEvidence.CURRENT, evidence())

    @Test fun inactiveModuleIsNotConfirmed() =
        assertEquals(InjectionEvidence.MODULE_INACTIVE, evidence(active = false))

    @Test fun unavailableBootIsNotConfirmed() =
        assertEquals(InjectionEvidence.BOOT_UNKNOWN, evidence(actualBoot = -1))

    @Test fun missingPidIsNotConfirmed() =
        assertEquals(InjectionEvidence.NO_REPORT, evidence(reportedPid = 0))

    @Test fun missingTimestampIsNotConfirmed() =
        assertEquals(InjectionEvidence.NO_REPORT, evidence(observedAt = 0L))

    @Test fun reportFromPreviousBootIsNotConfirmed() =
        assertEquals(InjectionEvidence.PREVIOUS_BOOT, evidence(reportedBoot = 6))

    @Test fun unavailableProcessListIsNotConfirmed() =
        assertEquals(InjectionEvidence.PROCESS_OBSERVATION_UNAVAILABLE, evidence(runningPids = null))

    @Test fun stoppedProcessIsNotConfirmed() =
        assertEquals(InjectionEvidence.PROCESS_STOPPED, evidence(runningPids = emptySet()))

    @Test fun replacedProcessIsNotConfirmed() =
        assertEquals(InjectionEvidence.PROCESS_CHANGED, evidence(runningPids = setOf(124)))

    @Test fun matchingPidAndBootDoNotRequireArbitraryTimeWindow() =
        assertEquals(InjectionEvidence.CURRENT, evidence(observedAt = 1L))
}
