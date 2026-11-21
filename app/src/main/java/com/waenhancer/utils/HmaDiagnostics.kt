package com.waenhancer.utils

import android.content.Context
import android.os.Process
import com.waenhancer.xposed.utils.AppRestartHelper
import com.waenhancer.xposed.utils.RootCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/** On-demand HMA diagnostics and checked repair for the current manager/profile. */
object HmaDiagnostics {
    private data class RequestKey(
        val applicationUid: Int,
        val modulePackage: String,
        val targets: List<String>,
        val liveScope: Boolean = false
    ) {
        val userId: Int get() = AndroidUserScope.userIdForUid(applicationUid)
    }
    private data class CachedReport(val request: RequestKey, val report: HmaReport)
    private val cachedResult = AtomicReference<CachedReport?>(null)
    private val repairLock = Any()
    private const val REASON_NOT_CONFIGURED = "Not configured in HMA scope"
    private const val REASON_HMA_NOT_ACTIVE = "HMA not active"

    enum class HmaState {
        CHECKING, UNAVAILABLE, NOT_ACTIVE, NOT_CONFIGURED, CLEAN, BLOCKING_TARGETS
    }

    data class TargetIsolationDetail(
        val packageName: String,
        val appName: String,
        val isBlocking: Boolean,
        val reason: String
    )

    data class HmaReport(
        val state: HmaState,
        val targetDetails: List<TargetIsolationDetail> = emptyList(),
        val blockedTargets: List<String> = emptyList(),
        val message: String? = null
    )

    private fun requestKey(context: Context, targetPackages: Collection<String>): RequestKey? = try {
        val uid = context.applicationInfo.uid
        if (uid != Process.myUid()) null else {
            AndroidUserScope.matchingUserId(uid, Process.myUid())
            // Empty scope must remain empty; never expand it to all declared apps.
            RequestKey(uid, context.packageName, targetPackages.toSortedSet().toList())
        }
    } catch (_: Exception) {
        null
    }

    /** Same API used by the current manager; never expose another process user's report. */
    fun cached(): HmaReport? {
        if (!NativeDiagPaths.available) return null
        return cachedResult.get()?.takeIf { it.request.applicationUid == Process.myUid() }?.report
    }

    suspend fun checkHmaStatus(
        context: Context,
        targetPackages: Collection<String>,
        scopeSnapshot: LsposedScopeSnapshot? = null,
        forceRefresh: Boolean = false
    ): HmaReport = withContext(Dispatchers.IO) {
        if (!NativeDiagPaths.available) {
            return@withContext HmaReport(HmaState.NOT_ACTIVE, message = "Diagnostics unavailable on this device architecture")
        }
        val identity = requestKey(context, targetPackages)
            ?: return@withContext HmaReport(HmaState.UNAVAILABLE)
        // A pre-repair check cannot publish after repair invalidates the cache.
        synchronized(repairLock) {
            val scope = scopeSnapshot ?: LsposedScopeReader.read(context)
            val request = identity.copy(
                targets = identity.targets.filter { it in scope.packages },
                liveScope = scope.isLive
            )
            if (!forceRefresh) {
                cachedResult.get()?.takeIf { it.request == request }?.let { return@synchronized it.report }
            }
            val report = runCheck(context, request)
            cachedResult.set(CachedReport(request, report))
            report
        }
    }

    private fun runCheck(context: Context, request: RequestKey): HmaReport {
        val packagesToCheck = request.targets
        if (packagesToCheck.isEmpty()) {
            return HmaReport(HmaState.CLEAN, message = "No requested targets in the current LSPosed scope")
        }
        fun inactiveReport(state: HmaState, reason: String) = HmaReport(
            state,
            targetDetails = packagesToCheck.map {
                TargetIsolationDetail(it, resolveAppLabel(context, it), false, reason)
            }
        )
        val paths = findAllHmaConfigPaths(request.userId)
        if (paths.isEmpty()) {
            return if (!isHmaInstalledAndActive(context)) {
                inactiveReport(HmaState.NOT_ACTIVE, REASON_HMA_NOT_ACTIVE)
            } else {
                inactiveReport(HmaState.NOT_CONFIGURED, REASON_NOT_CONFIGURED)
            }
        }
        val configs = readAllHmaConfigs(paths)
        if (configs.size != paths.size) {
            return HmaReport(HmaState.UNAVAILABLE, message = "HMA configuration could not be read completely")
        }
        val details = mutableListOf<TargetIsolationDetail>()
        val blocked = mutableListOf<String>()
        for (pkg in packagesToCheck) {
            var configured = false
            var reason: String? = null
            for ((_, config) in configs) {
                if (config.optJSONObject("scope")?.optJSONObject(pkg) == null) continue
                configured = true
                reason = HmaConfigPolicy.blockingReason(config, pkg, request.modulePackage)
                if (reason != null) break
            }
            if (reason != null) blocked.add(pkg)
            details.add(TargetIsolationDetail(
                pkg, resolveAppLabel(context, pkg), reason != null,
                reason ?: if (configured) "Active in HMA, module visible" else REASON_NOT_CONFIGURED
            ))
        }
        return HmaReport(
            if (blocked.isEmpty()) HmaState.CLEAN else HmaState.BLOCKING_TARGETS,
            targetDetails = details, blockedTargets = blocked
        )
    }

    private fun resolveAppLabel(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName
    }

    // Retain the current JNI source of path/package facts. All public entry
    // points guard availability before any native value is accessed.
    private val knownHmaPackages: Set<String> by lazy { NativeDiagPaths.knownHmaPackages().toSet() }
    private val knownConfigPaths: List<String> by lazy { NativeDiagPaths.knownHmaConfigPaths().toList() }

    private fun isHmaInstalledAndActive(context: Context): Boolean {
        for (pkg in knownHmaPackages) {
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                return true
            } catch (_: Exception) {}
        }
        if (!AppRestartHelper.runRootCommand(NativeDiagPaths.hmaActiveCheckCommand()).isNullOrBlank()) return true
        return !AppRestartHelper.runRootCommand(NativeDiagPaths.lspHmaCheckCommand()).isNullOrBlank()
    }

    /** Package visibility cannot redirect a stop to another app or another user. */
    private fun getInstalledHmaPackages(context: Context, userId: Int): List<String> {
        val packages = linkedSetOf<String>()
        for (pkg in knownHmaPackages) {
            if (!HmaConfigPolicy.isManagerPackage(pkg)) continue
            try {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                if (AndroidUserScope.userIdForUid(info.uid) == userId) packages.add(pkg)
            } catch (_: Exception) {}
        }
        AppRestartHelper.runRootCommand("pm list packages --user $userId")?.lineSequence()?.forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("package:")) return@forEach
            val pkg = line.removePrefix("package:")
            if (pkg in knownHmaPackages && HmaConfigPolicy.isManagerPackage(pkg)) packages.add(pkg)
        }
        return packages.toList()
    }

    private fun readConfigSnapshot(path: String): HmaConfigFile.Snapshot? {
        if (!HmaConfigFile.isConfigPath(path)) return null
        val output = RootCommandRunner.runBounded(
            HmaConfigFile.readCommand(path), 4_000L, HmaConfigFile.MAX_COMMAND_OUTPUT
        )
        return HmaConfigFile.parseSnapshot(path, output)
    }

    private fun readAllHmaConfigs(paths: List<String>): List<Pair<String, JSONObject>> =
        paths.mapNotNull { path ->
            val snapshot = readConfigSnapshot(path) ?: return@mapNotNull null
            try { path to JSONObject(snapshot.json) } catch (_: Exception) { null }
        }

    /**
     * Keep the current native candidates and glob, but map private candidates to
     * this user. Canonical outputs are checked again before any read or repair.
     * Global service rules are shared; a private candidate is not proof of reload.
     */
    private fun findAllHmaConfigPaths(userId: Int): List<String> {
        val scoped = knownConfigPaths.map { path ->
            when {
                path.startsWith("/data/data/") -> path.replaceFirst("/data/data/", "/data/user/$userId/")
                path.startsWith("/data/user/0/") -> path.replaceFirst("/data/user/0/", "/data/user/$userId/")
                path.startsWith("/data/user_de/0/") -> path.replaceFirst("/data/user_de/0/", "/data/user_de/$userId/")
                path.startsWith("/data/system/users/0/") -> path.replaceFirst("/data/system/users/0/", "/data/system/users/$userId/")
                else -> path
            }
        }.filter { HmaConfigFile.isConfigPathForUser(it, userId) }.distinct()
        val candidates = scoped.joinToString(" ") { HmaConfigFile.quote(it) }
        val command = "for waex_path in $candidates \$(" + NativeDiagPaths.hmaConfigGlobCommand() + "); do " +
            "[ -f \"\$waex_path\" ] && readlink -f \"\$waex_path\"; done 2>/dev/null; true"
        val output = AppRestartHelper.runRootCommand(command) ?: return emptyList()
        return output.lineSequence().map { it.trim() }
            .filter { HmaConfigFile.isConfigPathForUser(it, userId) }.distinct().toList()
    }

    /** Checked replacement of one declared, live-scoped target only. */
    suspend fun fixTargetIsolation(context: Context, targetPackage: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!NativeDiagPaths.available || !DeclaredWhatsAppTargets.contains(targetPackage)) return@withContext false
            val request = requestKey(context, listOf(targetPackage)) ?: return@withContext false
            synchronized(repairLock) {
                val scope = LsposedScopeReader.read(context)
                if (!scope.isLive || !scope.contains(targetPackage)) return@synchronized false
                var changed = false
                var failed = false
                try {
                    forceStopHmaApps(context, request.userId)
                    for (path in findAllHmaConfigPaths(request.userId)) {
                        val snapshot = readConfigSnapshot(path)
                        if (snapshot == null) {
                            failed = true
                            continue
                        }
                        try {
                            val updated = HmaConfigPolicy.repair(
                                JSONObject(snapshot.json), targetPackage, request.modulePackage
                            ) ?: continue
                            val json = updated.toString()
                            val write = HmaConfigFile.writeCommand(snapshot, json)
                            val backup = AppRestartHelper.runRootCommand(HmaConfigFile.backupCommand(snapshot))
                            if (backup != HmaConfigFile.BACKUP_ACK) {
                                failed = true
                                continue
                            }
                            val result = AppRestartHelper.runRootCommand(write)
                            if (result != HmaConfigFile.WRITE_ACK || readConfigSnapshot(path)?.json != json) {
                                failed = true
                                restoreConfigSnapshot(snapshot, json)
                            } else {
                                changed = true
                            }
                        } catch (_: Exception) {
                            failed = true
                        }
                    }
                    changed && !failed
                } finally {
                    forceStopHmaApps(context, request.userId)
                    cachedResult.set(null)
                }
            }
        }

    private fun restoreConfigSnapshot(snapshot: HmaConfigFile.Snapshot, attemptedJson: String): Boolean {
        val result = AppRestartHelper.runRootCommand(HmaConfigFile.restoreCommand(snapshot, attemptedJson))
        return result == HmaConfigFile.RESTORE_ACK && readConfigSnapshot(snapshot.path)?.digest == snapshot.digest
    }

    /** Stop only verified known manager packages in the initiating manager's user. */
    fun forceStopHmaApps(context: Context) {
        if (!NativeDiagPaths.available) return
        val request = requestKey(context, emptyList()) ?: return
        forceStopHmaApps(context, request.userId)
    }

    private fun forceStopHmaApps(context: Context, userId: Int) {
        for (pkg in getInstalledHmaPackages(context, userId)) {
            AppRestartHelper.runRootCommand("am force-stop --user $userId " + HmaConfigFile.quote(pkg))
        }
    }
}
