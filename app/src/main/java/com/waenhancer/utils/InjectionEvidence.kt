package com.waenhancer.utils

/**
 * Evidence that a target process is running with the module injected, separate from settings and
 * feature readiness. WAEX is root-only, so this is decided at read time from a report the target
 * made at injection plus a live process list; there is no heartbeat or time window.
 */
enum class InjectionEvidence(val label: String) {
    CURRENT("Injection confirmed"), MODULE_INACTIVE("Module inactive"),
    BOOT_UNKNOWN("Injection unconfirmed: boot identity unavailable"),
    PREVIOUS_BOOT("Injection unconfirmed: report from another boot"),
    NO_REPORT("Injection unconfirmed: no runtime report"),
    PROCESS_OBSERVATION_UNAVAILABLE("Injection unconfirmed: root process check failed"),
    PROCESS_STOPPED("Reported process is not running"),
    PROCESS_CHANGED("Injection unconfirmed: process changed")
}

/**
 * A report names the pid that injected. It is current only if that same pid is alive now in the
 * same boot. A restarted process either reports again (new pid) or shows up as [PROCESS_CHANGED].
 */
internal fun classifyInjection(
    isModuleActive: Boolean, recordedBoot: Int, currentBoot: Int, reportedPid: Int,
    runningPids: Set<Int>?, lastActiveTime: Long
): InjectionEvidence = when {
    !isModuleActive -> InjectionEvidence.MODULE_INACTIVE
    currentBoot < 0 -> InjectionEvidence.BOOT_UNKNOWN
    reportedPid <= 0 || lastActiveTime <= 0L -> InjectionEvidence.NO_REPORT
    recordedBoot != currentBoot -> InjectionEvidence.PREVIOUS_BOOT
    runningPids == null -> InjectionEvidence.PROCESS_OBSERVATION_UNAVAILABLE
    runningPids.isEmpty() -> InjectionEvidence.PROCESS_STOPPED
    reportedPid !in runningPids -> InjectionEvidence.PROCESS_CHANGED
    else -> InjectionEvidence.CURRENT
}
