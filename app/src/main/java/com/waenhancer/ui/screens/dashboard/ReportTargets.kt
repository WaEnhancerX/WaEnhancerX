package com.waenhancer.ui.screens.dashboard

import com.waenhancer.licensing.report.ReportHook
import com.waenhancer.licensing.report.ReportTarget
import com.waenhancer.utils.HookedAppInfo

/** The licensing module builds reports from neutral data; this translates the app's detection result. */
fun HookedAppInfo.toReportTarget(): ReportTarget = ReportTarget(
    packageName = packageName,
    appName = appName,
    versionName = versionName,
    runtimeStatus = runtimeStatus,
    bridgeStatus = bridgeStatus,
    injectionEvidence = injectionEvidence.toString(),
    scopeState = scopeState.toString(),
    initializationError = initializationError,
    hooks = injectedHooks.map { hook ->
        ReportHook(
            name = hook.name,
            className = hook.className,
            category = hook.category,
            status = hook.status,
            error = hook.error
        )
    }
)
