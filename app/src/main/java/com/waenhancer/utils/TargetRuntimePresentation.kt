package com.waenhancer.utils

/** Display policy shared by the overview and details. It never changes hook permissions. */
object TargetRuntimePresentation {
    enum class Severity { OK, UNKNOWN, REVIEW }
    data class Status(val text: String, val severity: Severity)
    data class Summary(val text: String, val healthy: Boolean?)

    fun card(app: HookedAppInfo, moduleActive: Boolean, decision: UniversalVersionValidator.Decision): Status = when {
        !app.isInstalled -> Status("Not installed", Severity.UNKNOWN)
        !moduleActive -> Status("Module inactive", Severity.REVIEW)
        !app.isHooked && app.scopeState == TargetScopeState.UNVERIFIED ->
            Status("Scope unverified • ${app.injectionEvidence.label}", Severity.UNKNOWN)
        !app.isHooked -> Status(app.injectionEvidence.label, Severity.UNKNOWN)
        app.runtimeStatus == "COMPATIBILITY_UNAVAILABLE" -> Status("Injected • Compatibility check unavailable", Severity.REVIEW)
        app.runtimeStatus == "UNSUPPORTED_VERSION" -> Status("Injected • Paused by target version guard", Severity.REVIEW)
        app.runtimeStatus == "INITIALIZATION_FAILED" -> Status("Injected • Initialization failed", Severity.REVIEW)
        !decision.allowed -> Status("Injected • Local version check requires review", Severity.REVIEW)
        app.runtimeStatus == "INJECTED" || app.runtimeStatus == "INITIALIZING" -> Status("Injected • Initialization in progress", Severity.UNKNOWN)
        app.bridgeStatus == "FAILED" -> Status("Injected • Settings connection unavailable", Severity.REVIEW)
        app.bridgeStatus == "DISK_FALLBACK" -> Status("Injected • Using settings fallback", Severity.REVIEW)
        app.runtimeStatus == "READY_WITH_ERRORS" -> Status("Injected • Some features failed", Severity.REVIEW)
        app.runtimeStatus != "READY" -> Status("Injected • Feature readiness unconfirmed", Severity.UNKNOWN)
        app.bridgeStatus != "PROVIDER" -> Status("Injected • Settings connection unconfirmed", Severity.UNKNOWN)
        decision == UniversalVersionValidator.Decision.EXPLICIT_BYPASS -> Status("Injected • Version bypass active", Severity.REVIEW)
        decision == UniversalVersionValidator.Decision.CUSTOM_RULE -> Status("Injected • Custom version allowance; unverified", Severity.REVIEW)
        else -> Status("Injected • Initialization reported complete", Severity.OK)
    }

    fun bridge(app: HookedAppInfo): String = lastReport(app, when (app.bridgeStatus) {
        "PROVIDER" -> "Connected directly"
        "DISK_FALLBACK" -> "Using disk fallback"
        "FAILED" -> "Unavailable"
        else -> "Not reported"
    })

    fun initialization(app: HookedAppInfo): String = lastReport(app, when (app.runtimeStatus) {
        "READY" -> "Completed"
        "READY_WITH_ERRORS" -> "Completed with hook failures"
        "INITIALIZING" -> "In progress"
        "COMPATIBILITY_UNAVAILABLE" -> "Paused: compatibility check unavailable"
        "UNSUPPORTED_VERSION" -> "Paused: unsupported version"
        "INITIALIZATION_FAILED" -> "Failed"
        "INJECTED" -> "Injection detected"
        else -> "Not reported"
    })

    private fun lastReport(app: HookedAppInfo, value: String): String =
        if (app.isHooked || value == "Not reported") value else "Last report (not current): $value"

    /**
     * Overall target health. Runtime readiness is judged only for targets that are actually running
     * with the module injected; an installed target that is simply not open must not turn the
     * summary into "unconfirmed". Per-target rows use [card], so the summary, the dashboard cards
     * and the compatibility sheet always agree.
     */
    fun summarize(apps: List<HookedAppInfo>, decisions: Map<String, UniversalVersionValidator.Decision>): Summary {
        val installed = apps.filter { it.isInstalled }
        if (installed.isEmpty()) return Summary("No installed target detected", null)
        if (installed.any { !decisions.getOrDefault(it.packageName, UniversalVersionValidator.Decision.RULES_UNAVAILABLE).allowed })
            return Summary("Version rules require review", false)
        val running = installed.filter { it.isHooked }
        if (running.any { it.runtimeStatus in setOf("UNSUPPORTED_VERSION", "COMPATIBILITY_UNAVAILABLE", "INITIALIZATION_FAILED", "READY_WITH_ERRORS") })
            return Summary("Target runtime requires review", false)
        if (installed.any { decisions[it.packageName] == UniversalVersionValidator.Decision.EXPLICIT_BYPASS })
            return Summary("Version bypass active; compatibility unverified", false)
        if (installed.any { decisions[it.packageName] == UniversalVersionValidator.Decision.CUSTOM_RULE })
            return Summary("Custom version allowance; compatibility unverified", null)
        if (running.isEmpty()) {
            // Distinguish "we could not check" from "nothing is open".
            val blocked = installed.firstOrNull {
                it.injectionEvidence == InjectionEvidence.PROCESS_OBSERVATION_UNAVAILABLE ||
                    it.injectionEvidence == InjectionEvidence.BOOT_UNKNOWN ||
                    it.injectionEvidence == InjectionEvidence.MODULE_INACTIVE
            }
            return if (blocked != null) Summary(blocked.injectionEvidence.label, null)
            else Summary("Rules accepted; open a target app to confirm injection", null)
        }
        if (running.any { it.runtimeStatus != "READY" || it.bridgeStatus != "PROVIDER" })
            return Summary("Version rules accepted; runtime unconfirmed", null)
        // Even current READY reports are not acceptance tests of all individual features.
        return Summary("Rules accepted; initialization reported", true)
    }
}
