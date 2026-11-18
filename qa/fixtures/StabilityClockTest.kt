package com.waenhancer.fixture

import com.waenhancer.utils.InjectionEvidence
import com.waenhancer.utils.classifyInjection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 'Clock' is a legacy QA fixture/CLI name. WAEX removed the observation clock in
 * commit 1b029ec: injection is now determined by PID and boot, not a time window.
 * These plain-JVM JUnit tests exercise the real classifier, not an emulated heartbeat.
 */
class StabilityClockTest {
    private val boot = 19
    private val pid = 2324

    private fun evidence(
        moduleActive: Boolean = true,
        recordedBoot: Int = boot,
        currentBoot: Int = boot,
        reportedPid: Int = pid,
        runningPids: Set<Int>? = setOf(pid),
        lastReport: Long = 1L
    ) = classifyInjection(moduleActive, recordedBoot, currentBoot, reportedPid, runningPids, lastReport)

    @Test fun currentPidInCurrentBootHasNoArbitraryExpiry() {
        assertEquals(InjectionEvidence.CURRENT, evidence(lastReport = 1L))
        assertEquals(InjectionEvidence.CURRENT, evidence(lastReport = Long.MAX_VALUE))
        assertEquals(InjectionEvidence.CURRENT, evidence(runningPids = setOf(8, pid, 99)))
    }

    @Test fun moduleAndBootChecksTakePrecedenceOverLivePid() {
        assertEquals(InjectionEvidence.MODULE_INACTIVE, evidence(moduleActive = false))
        assertEquals(InjectionEvidence.MODULE_INACTIVE, evidence(moduleActive = false, currentBoot = -1))
        assertEquals(InjectionEvidence.BOOT_UNKNOWN, evidence(currentBoot = -1))
    }

    @Test fun reportMustIdentifyARealPidInTheCurrentBoot() {
        assertEquals(InjectionEvidence.NO_REPORT, evidence(reportedPid = 0))
        assertEquals(InjectionEvidence.NO_REPORT, evidence(lastReport = 0L))
        assertEquals(InjectionEvidence.PREVIOUS_BOOT, evidence(recordedBoot = boot - 1))
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
        assertEquals(InjectionEvidence.PREVIOUS_BOOT, evidence(recordedBoot = boot - 1, reportedPid = restartedPid, runningPids = setOf(restartedPid)))
    }
}
