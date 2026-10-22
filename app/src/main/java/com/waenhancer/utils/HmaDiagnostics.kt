package com.waenhancer.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import com.waenhancer.xposed.utils.AppRestartHelper
import com.waenhancer.xposed.utils.RootCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Diagnostics utility for detecting Hide My Applist (HMA) activity and checking
 * whether any WhatsApp target package has configured rules hiding WAEX.
 *
 * Checks are heavy (requiring root file inspection / SQLite parsing), so results
 * are evaluated on demand or cached during app launch.
 */
object HmaDiagnostics {

    private const val HMA_ZYGISK_PATH = "/data/adb/modules/hma_oss_zygisk"
    private const val LSP_CONFIG_DB = "/data/adb/lspd/config/modules_config.db"

    private data class RequestKey(val applicationUid: Int, val modulePackage: String, val targets: List<String>) {
        val userId: Int get() = AndroidUserScope.userIdForUid(applicationUid)
    }
    private data class CachedReport(val request: RequestKey, val report: HmaReport)
    private val cachedResult = AtomicReference<CachedReport?>(null)

    enum class HmaState {
        CHECKING,
        UNAVAILABLE,
        NOT_ACTIVE,
        NOT_CONFIGURED,
        CLEAN,
        BLOCKING_TARGETS
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
        // Resolve the calling app's identity before entering a root shell. A Context
        // for another package or profile must not select which manager is stopped.
        if (uid != Process.myUid()) null else {
            AndroidUserScope.matchingUserId(uid, Process.myUid())
            val targets = if (targetPackages.isEmpty()) DeclaredWhatsAppTargets.packages() else targetPackages
            RequestKey(uid, context.packageName, targets.toSortedSet().toList())
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Inspects HMA state and verifies if any target package in [targetPackages]
     * is configured to hide WAEX.
     */
    suspend fun checkHmaStatus(
        context: Context,
        targetPackages: Collection<String>,
        forceRefresh: Boolean = false
    ): HmaReport = withContext(Dispatchers.IO) {
        val request = requestKey(context, targetPackages)
            ?: return@withContext HmaReport(HmaState.UNAVAILABLE)
        // A check cannot publish a pre-repair snapshot after repair invalidates it.
        synchronized(repairLock) {
            if (!forceRefresh) {
                cachedResult.get()?.takeIf { it.request == request }?.let { return@synchronized it.report }
            }
            val report = runCheck(context, request)
            cachedResult.set(CachedReport(request, report))
            report
        }
    }

    private fun runCheck(context: Context, request: RequestKey): HmaReport {
        if (!isHmaInstalledAndActive(context, request.userId)) {
            return HmaReport(HmaState.NOT_ACTIVE)
        }

        val allConfigs = readAllHmaConfigs(request.userId)
        if (allConfigs.isEmpty()) {
            return HmaReport(HmaState.NOT_CONFIGURED)
        }

        // Known module package identifiers to check for concealment
        val currentPackage = request.modulePackage
        val packagesToCheck = request.targets

        val details = mutableListOf<TargetIsolationDetail>()
        val blockedList = mutableListOf<String>()

        for (pkg in packagesToCheck) {
            val appLabel = resolveAppLabel(context, pkg)
            var foundInAnyScope = false
            var primaryBlockReason: String? = null

            for ((_, config) in allConfigs) {
                val scope = config.optJSONObject("scope") ?: JSONObject()
                if (scope.optJSONObject(pkg) == null) continue

                foundInAnyScope = true
                val blockReason = HmaConfigPolicy.blockingReason(config, pkg, currentPackage)
                if (blockReason != null) {
                    primaryBlockReason = blockReason
                    break
                }
            }

            if (!foundInAnyScope) {
                details.add(
                    TargetIsolationDetail(
                        packageName = pkg,
                        appName = appLabel,
                        isBlocking = false,
                        reason = "Not configured in HMA scope"
                    )
                )
            } else if (primaryBlockReason != null) {
                blockedList.add(pkg)
                details.add(
                    TargetIsolationDetail(
                        packageName = pkg,
                        appName = appLabel,
                        isBlocking = true,
                        reason = primaryBlockReason
                    )
                )
            } else {
                details.add(
                    TargetIsolationDetail(
                        packageName = pkg,
                        appName = appLabel,
                        isBlocking = false,
                        reason = "Active in HMA, module visible"
                    )
                )
            }
        }

        return if (blockedList.isEmpty()) {
            HmaReport(HmaState.CLEAN, targetDetails = details)
        } else {
            HmaReport(HmaState.BLOCKING_TARGETS, targetDetails = details, blockedTargets = blockedList)
        }
    }

    private fun resolveAppLabel(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            when {
                packageName == "com.whatsapp" -> "WhatsApp"
                packageName == "com.whatsapp.w4b" -> "WhatsApp Business"
                else -> packageName
            }
        }
    }

    private val KNOWN_HMA_PACKAGES = setOf(
        "com.tsng.hidemyapplist",
        "org.frknkrc44.hma_oss",
        "com.google.android.hmal"
    )
    private val repairLock = Any()

    private fun isHmaInstalledAndActive(context: Context, userId: Int): Boolean {
        // 1. Zygisk module directory check
        val zygiskCheck = AppRestartHelper.runRootCommand(
            "[ -d $HMA_ZYGISK_PATH ] && [ ! -f $HMA_ZYGISK_PATH/disable ] && echo active; " +
                    "ls -d /data/adb/modules/*hma* 2>/dev/null; true"
        )
        if (zygiskCheck?.contains("active") == true || zygiskCheck?.contains("hma") == true) {
            return true
        }

        // 2. LSPosed module database check
        val dbCheck = AppRestartHelper.runRootCommand("[ -f $LSP_CONFIG_DB ] && echo exists")
        if (dbCheck?.contains("exists") == true) {
            val cacheDir = context.cacheDir
            val cacheDb = File(cacheDir, "hma_lsposed_inspect.db")
            val walDb = File(cacheDir, "hma_lsposed_inspect.db-wal")
            val shmDb = File(cacheDir, "hma_lsposed_inspect.db-shm")

            listOf(cacheDb, walDb, shmDb).forEach { if (it.exists()) it.delete() }

            val copyCmd = "cp $LSP_CONFIG_DB ${cacheDb.absolutePath} 2>/dev/null; " +
                    "cp $LSP_CONFIG_DB-wal ${walDb.absolutePath} 2>/dev/null; " +
                    "cp $LSP_CONFIG_DB-shm ${shmDb.absolutePath} 2>/dev/null; " +
                    "chmod 666 ${cacheDb.absolutePath} ${walDb.absolutePath} ${shmDb.absolutePath} 2>/dev/null; true"
            AppRestartHelper.runRootCommand(copyCmd)

            if (cacheDb.exists()) {
                val found = isHmaEnabledInLspDb(cacheDb)
                listOf(cacheDb, walDb, shmDb).forEach { if (it.exists()) it.delete() }
                if (found) return true
            }
        }

        // 3. Installed package manager check dynamically for any HMA / HMAL packages
        val installedHmaPackages = getInstalledHmaPackages(context, userId)
        if (installedHmaPackages.isNotEmpty()) {
            return true
        }

        // 4. Check if any HMA config file exists directly
        val configPaths = findAllHmaConfigPaths(userId)
        return configPaths.isNotEmpty()
    }

    /**
     * Discovers exact HMA / HMAL managers installed for the calling app's user.
     */
    private fun getInstalledHmaPackages(context: Context, userId: Int): List<String> {
        val packages = linkedSetOf<String>()
        val pm = context.packageManager
        for (pkg in KNOWN_HMA_PACKAGES) {
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                if (AndroidUserScope.userIdForUid(info.uid) == userId) packages.add(pkg)
            } catch (_: Exception) {}
        }
        // Package visibility can hide a known manager from the app. Never guess
        // an unrelated package from a substring before issuing a force-stop.
        AppRestartHelper.runRootCommand("pm list packages --user $userId")?.lineSequence()?.forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("package:")) return@forEach
            val pkg = line.removePrefix("package:")
            if (HmaConfigPolicy.isManagerPackage(pkg)) packages.add(pkg)
        }
        return packages.toList()
    }

    private fun isHmaEnabledInLspDb(dbFile: File): Boolean {
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = db.rawQuery(
                "SELECT 1 FROM modules WHERE (module_pkg_name LIKE '%hidemyapp%' OR module_pkg_name LIKE '%hma%') AND enabled = 1 LIMIT 1",
                null
            )
            val exists = cursor.moveToFirst()
            cursor.close()
            exists
        } catch (_: Exception) {
            false
        } finally {
            db?.close()
        }
    }

    /**
     * Reads a previously discovered, canonical HMA / HMAL configuration candidate.
     */
    private fun readConfigSnapshot(path: String): HmaConfigFile.Snapshot? {
        if (!HmaConfigFile.isConfigPath(path)) return null
        val output = RootCommandRunner.runBounded(
            HmaConfigFile.readCommand(path), 4_000L, HmaConfigFile.MAX_COMMAND_OUTPUT
        )
        return HmaConfigFile.parseSnapshot(path, output)
    }

    private fun readAllHmaConfigs(userId: Int): List<Pair<String, JSONObject>> {
        val configs = mutableListOf<Pair<String, JSONObject>>()
        for (path in findAllHmaConfigPaths(userId)) {
            val snapshot = readConfigSnapshot(path) ?: continue
            try {
                configs.add(path to JSONObject(snapshot.json))
            } catch (_: Exception) {}
        }
        return configs
    }

    /**
     * Global HMA service stores use package-name rules shared across users. Private
     * manager files are only candidates for this user; finding one does not prove
     * that the running service imported or currently enforces those rules.
     */
    private fun findAllHmaConfigPaths(userId: Int): List<String> {
        val privateCandidates = KNOWN_HMA_PACKAGES.joinToString(" ") {
            HmaConfigFile.quote("/data/user/$userId/$it/files/config.json")
        }
        // Direct service paths plus exact manager files avoid a recursive scan of
        // unrelated apps and never ask the root namespace to resolve /data/data.
        val findCmd = "for waex_path in /data/misc/hide_my_applist*/config.json " +
                "/data/system/hide_my_applist*/config.json $privateCandidates; do " +
                "[ -f \"\$waex_path\" ] && readlink -f \"\$waex_path\"; done 2>/dev/null; true"
        val out = AppRestartHelper.runRootCommand(findCmd) ?: return emptyList()
        return out.lines()
            .map { it.trim() }
            .filter { HmaConfigFile.isConfigPathForUser(it, userId) }
            .distinct()
    }

    /**
     * Updates only the selected declared target. A successful file replacement
     * is not evidence that the HMA service reloaded it or that hooks injected.
     */
    suspend fun fixTargetIsolation(context: Context, targetPackage: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!DeclaredWhatsAppTargets.contains(targetPackage)) return@withContext false
            val request = requestKey(context, listOf(targetPackage)) ?: return@withContext false
            synchronized(repairLock) {
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
                            // Validate the replacement size before creating a recovery copy.
                            val write = HmaConfigFile.writeCommand(snapshot, json)
                            val backup = AppRestartHelper.runRootCommand(
                                HmaConfigFile.backupCommand(snapshot)
                            )
                            if (backup != HmaConfigFile.BACKUP_ACK) {
                                failed = true
                                continue
                            }
                            val result = AppRestartHelper.runRootCommand(write)
                            if (result != HmaConfigFile.WRITE_ACK ||
                                readConfigSnapshot(path)?.json != json) {
                                failed = true
                                // The repair remains unsuccessful even if rollback succeeds.
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
        val result = AppRestartHelper.runRootCommand(
            HmaConfigFile.restoreCommand(snapshot, attemptedJson)
        )
        return result == HmaConfigFile.RESTORE_ACK &&
            readConfigSnapshot(snapshot.path)?.digest == snapshot.digest
    }

    /** Force-stop only known manager packages for this app's user, once per pass. */
    fun forceStopHmaApps(context: Context) {
        val request = requestKey(context, emptyList()) ?: return
        forceStopHmaApps(context, request.userId)
    }

    private fun forceStopHmaApps(context: Context, userId: Int) {
        for (pkg in getInstalledHmaPackages(context, userId)) {
            AppRestartHelper.runRootCommand("am force-stop --user $userId " + HmaConfigFile.quote(pkg))
        }
    }

}
