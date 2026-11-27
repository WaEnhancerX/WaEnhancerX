package com.waenhancer.fixture

import com.waenhancer.utils.InjectionEvidence
import com.waenhancer.utils.classifyInjection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain-JVM tests for the real PID/boot classifier; no Android API is used here.
 * Preserves all methods/assertions from the evidence and legacy clock fixtures.
 * The evidence mode selects this suite once; it does not exercise a Compose clock.
 * Repeated assertions are not new coverage or installed Android/LSPosed evidence.
 */
class StabilityEvidenceTest {
    private val boot = 7
    private val pid = 123

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

    @Test fun currentPidInCurrentBootHasNoArbitraryExpiry() {
        assertEquals(InjectionEvidence.CURRENT, evidence(observedAt = 1L))
        assertEquals(InjectionEvidence.CURRENT, evidence(observedAt = Long.MAX_VALUE))
        assertEquals(InjectionEvidence.CURRENT, evidence(runningPids = setOf(8, pid, 99)))
    }

    @Test fun moduleAndBootChecksTakePrecedenceOverLivePid() {
        assertEquals(InjectionEvidence.MODULE_INACTIVE, evidence(active = false))
        assertEquals(InjectionEvidence.MODULE_INACTIVE, evidence(active = false, actualBoot = -1))
        assertEquals(InjectionEvidence.BOOT_UNKNOWN, evidence(actualBoot = -1))
    }

    @Test fun reportMustIdentifyARealPidInTheCurrentBoot() {
        assertEquals(InjectionEvidence.NO_REPORT, evidence(reportedPid = 0))
        assertEquals(InjectionEvidence.NO_REPORT, evidence(observedAt = 0L))
        assertEquals(InjectionEvidence.PREVIOUS_BOOT, evidence(reportedBoot = boot - 1))
    }

    @Test fun unavailableStoppedAndChangedProcessesRemainDistinct() {
        assertEquals(InjectionEvidence.PROCESS_OBSERVATION_UNAVAILABLE, evidence(runningPids = null))
        assertEquals(InjectionEvidence.PROCESS_STOPPED, evidence(runningPids = emptySet()))
        assertEquals(InjectionEvidence.PROCESS_CHANGED, evidence(runningPids = setOf(pid + 1)))
    }

    @Test fun restartRequiresAnUpdatedInjectionReport() {
        val restartedPid = pid + 1
        assertEquals(InjectionEvidence.PROCESS_CHANGED, evidence(runningPids = setOf(restartedPid)))
        assertEquals(InjectionEvidence.CURRENT, evidence(reportedPid = restartedPid, runningPids = setOf(restartedPid)))
        assertEquals(InjectionEvidence.PREVIOUS_BOOT, evidence(reportedBoot = boot - 1, reportedPid = restartedPid, runningPids = setOf(restartedPid)))
    }
}
