package com.waenhancer.utils

import android.content.Context
import com.waenhancer.xposed.utils.AppRestartHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

/**
 * Diagnostics utility for detecting Magisk DenyList activity and checking
 * whether any WhatsApp target package is blocked by DenyList enforcement.
 *
 * DenyList unmounts Zygisk modules (including LSPosed) from targeted processes,
 * preventing WAEX hooks from loading in WhatsApp / WhatsApp Business.
 */
object MagiskDenylistDiagnostics {

    private val DENYLIST_LOG_PATTERN = Pattern.compile("\\[([^\\]]+)] is on the denylist")
    private const val LSP_LOG_GLOB = "/data/adb/lspd/log/*.log"

    private val cachedResult = AtomicReference<DenylistReport?>(null)

    enum class DenylistState {
        CHECKING,
        NOT_ACTIVE, // Magisk not installed or DenyList not enforced
        CLEAN,      // Magisk installed and enforced, but no WhatsApp packages denylisted
        BLOCKING_TARGETS // WhatsApp packages are on the enforced DenyList
    }

    enum class TargetState {
        BLOCKED,
        NOT_DENYLISTED,
        DENYLIST_NOT_ENFORCED,
        MAGISK_NOT_ACTIVE
    }

    data class DenylistedTarget(
        val packageName: String,
        val appName: String,
        val isBlocked: Boolean,
        val state: TargetState,
        val processDetails: String? = null
    )

    data class DenylistReport(
        val state: DenylistState,
        val isMagiskInstalled: Boolean = false,
        val isDenylistEnforced: Boolean = false,
        val blockedPackages: List<String> = emptyList(),
        val targets: List<DenylistedTarget> = emptyList()
    )

    fun cached(): DenylistReport? = cachedResult.get()

    /**
     * Inspects Magisk DenyList state and verifies if any target package in [targetPackages]
     * is configured on an enforced DenyList.
     */
    suspend fun checkDenylistStatus(
        context: Context,
        targetPackages: Collection<String>,
        scopeSnapshot: LsposedScopeSnapshot? = null,
        forceRefresh: Boolean = false
    ): DenylistReport = withContext(Dispatchers.IO) {
        val liveScope = (scopeSnapshot ?: LsposedScopeReader.read(context)).packages
        val requestedPackages = packagesToCheck(targetPackages, liveScope)
        if (!forceRefresh) {
            cachedResult.get()
                ?.takeIf { cached -> cached.targets.map { it.packageName }.toSet() == requestedPackages }
                ?.let { return@withContext it }
        }

        val report = runCheck(context.applicationContext, requestedPackages)
        cachedResult.set(report)
        report
    }

    private fun isMagiskInstalled(): Boolean {
        val out = AppRestartHelper.runRootCommand("magisk -v 2>/dev/null")
        return !out.isNullOrBlank() && out.contains("MAGISK", ignoreCase = true)
    }

    private fun isDenylistEnforced(): Boolean {
        val out = AppRestartHelper.runRootCommand("magisk --denylist status 2>/dev/null; echo exit:$?")
        return out?.contains("exit:0") == true
    }

    /**
     * Reads all "package|process" entries from Magisk DenyList via root shell.
     */
    private fun getDenylistedPackages(): Map<String, List<String>> {
        val out = AppRestartHelper.runRootCommand("magisk --denylist ls 2>/dev/null") ?: return emptyMap()
        val result = mutableMapOf<String, MutableList<String>>()
        out.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("exit:")) {
                val pkg = trimmed.substringBefore('|').trim()
                val proc = trimmed.substringAfter('|', "").trim()
                if (pkg.isNotEmpty()) {
                    result.getOrPut(pkg) { mutableListOf() }.apply {
                        if (proc.isNotEmpty()) add(proc)
                    }
                }
            }
        }
        return result
    }

    /**
     * Checks LSPosed module logs as fallback if Magisk DenyList command cannot be queried directly.
     */
    private fun getDenylistFromLspLogs(installedTargets: Set<String>): Set<String> {
        val logOut = AppRestartHelper.runRootCommand("cat $LSP_LOG_GLOB 2>/dev/null") ?: return emptySet()
        val found = mutableSetOf<String>()
        logOut.lines().forEach { line ->
            val matcher = DENYLIST_LOG_PATTERN.matcher(line)
            if (matcher.find()) {
                val candidate = matcher.group(1)?.substringBefore(':')?.trim().orEmpty()
                if (candidate in installedTargets) {
                    found.add(candidate)
                }
            }
        }
        return found
    }

    private fun packagesToCheck(targetPackages: Collection<String>, liveScope: Set<String>): Set<String> =
        targetPackages.filterTo(linkedSetOf()) {
            DeclaredWhatsAppTargets.contains(it) && it in liveScope && PACKAGE_NAME_PATTERN.matcher(it).matches()
        }

    private fun runCheck(context: Context, packagesToCheck: Set<String>): DenylistReport {
        if (!isMagiskInstalled()) {
            return DenylistReport(
                state = DenylistState.NOT_ACTIVE,
                isMagiskInstalled = false,
                isDenylistEnforced = false,
                targets = buildTargets(
                    context = context,
                    packagesToCheck = packagesToCheck,
                    blockedPackages = emptySet(),
                    denylistMap = emptyMap(),
                    inactiveState = TargetState.MAGISK_NOT_ACTIVE
                )
            )
        }

        val enforced = isDenylistEnforced()
        val denylistMap = getDenylistedPackages()
        val blockedPackages = linkedSetOf<String>()

        if (denylistMap.isNotEmpty()) {
            for (pkg in packagesToCheck) {
                if (pkg in denylistMap && enforced) {
                    blockedPackages.add(pkg)
                }
            }
        } else {
            // Fall back to log check
            val loggedBlocked = getDenylistFromLspLogs(packagesToCheck)
            if (enforced) {
                blockedPackages.addAll(loggedBlocked)
            }
        }

        val targetList = buildTargets(
            context = context,
            packagesToCheck = packagesToCheck,
            blockedPackages = blockedPackages,
            denylistMap = denylistMap,
            inactiveState = if (enforced) TargetState.NOT_DENYLISTED else TargetState.DENYLIST_NOT_ENFORCED
        )

        val state = when {
            !enforced -> DenylistState.NOT_ACTIVE
            blockedPackages.isNotEmpty() -> DenylistState.BLOCKING_TARGETS
            else -> DenylistState.CLEAN
        }

        return DenylistReport(
            state = state,
            isMagiskInstalled = true,
            isDenylistEnforced = enforced,
            blockedPackages = blockedPackages.toList(),
            targets = targetList
        )
    }

    private fun buildTargets(
        context: Context,
        packagesToCheck: Set<String>,
        blockedPackages: Set<String>,
        denylistMap: Map<String, List<String>>,
        inactiveState: TargetState
    ): List<DenylistedTarget> = packagesToCheck.map { pkg ->
        val blocked = pkg in blockedPackages
        DenylistedTarget(
            packageName = pkg,
            appName = resolveAppLabel(context, pkg),
            isBlocked = blocked,
            state = if (blocked) TargetState.BLOCKED else inactiveState,
            processDetails = denylistMap[pkg]?.joinToString(", ")?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * Removes the specified package from the Magisk DenyList and force-stops it so the
     * zygote unmount is reverted on subsequent launch.
     */
    suspend fun fixPackage(context: Context, packageName: String): Boolean = withContext(Dispatchers.IO) {
        if (!DeclaredWhatsAppTargets.contains(packageName) ||
            !LsposedScopeReader.read(context).contains(packageName) ||
            !PACKAGE_NAME_PATTERN.matcher(packageName).matches()) {
            return@withContext false
        }

        val rmResult = AppRestartHelper.runRootCommand("magisk --denylist rm $packageName 2>/dev/null; echo exit:$?")
        val success = rmResult?.contains("exit:0") == true

        // Force stop package so next process startup is clean
        AppRestartHelper.runRootCommand("am force-stop $packageName")

        // Invalidate cache
        cachedResult.set(null)
        success
    }

    /**
     * Removes all blocked WhatsApp packages from the Magisk DenyList with one tap.
     */
    suspend fun fixAllBlocked(context: Context, blockedPackages: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (blockedPackages.isEmpty()) return@withContext true
        var allSuccess = true
        for (pkg in blockedPackages) {
            val res = fixPackage(context, pkg)
            if (!res) allSuccess = false
        }
        cachedResult.set(null)
        allSuccess
    }

    private fun resolveAppLabel(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            when (packageName) {
                "com.whatsapp" -> "WhatsApp"
                "com.whatsapp.w4b" -> "WhatsApp Business"
                else -> packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
            }
        }
    }

    private val PACKAGE_NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
}
