package com.waenhancer.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.waenhancer.xposed.utils.AppRestartHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import android.util.Base64

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
    private const val HMA_CONFIG_GLOB = "/data/misc/hide_my_applist*/config.json"
    private const val HMA_ALT_CONFIG = "/data/adb/hma/config.json"

    private val cachedResult = AtomicReference<HmaReport?>(null)

    enum class HmaState {
        CHECKING,
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

    fun cached(): HmaReport? = cachedResult.get()

    /**
     * Inspects HMA state and verifies if any target package in [targetPackages]
     * is configured to hide WAEX.
     */
    suspend fun checkHmaStatus(
        context: Context,
        targetPackages: Collection<String>,
        scopeSnapshot: LsposedScopeSnapshot? = null,
        forceRefresh: Boolean = false
    ): HmaReport = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cachedResult.get()?.let { return@withContext it }
        }

        val report = runCheck(context.applicationContext, targetPackages, scopeSnapshot)
        cachedResult.set(report)
        report
    }

    private fun runCheck(context: Context, targetPackages: Collection<String>, scopeSnapshot: LsposedScopeSnapshot?): HmaReport {
        if (!isHmaInstalledAndActive(context)) {
            return HmaReport(HmaState.NOT_ACTIVE)
        }

        val allConfigs = readAllHmaConfigs()
        if (allConfigs.isEmpty()) {
            return HmaReport(HmaState.NOT_CONFIGURED)
        }

        // Known module package identifiers to check for concealment
        val currentPackage = context.packageName
        val moduleSignatures = setOf(currentPackage, "com.waenhancer", "com.waenhancer.debug")

        // Empty means no live scoped targets. Never fall back to every installed/declared app:
        // scope is the security boundary for this diagnostic.
        val liveScope = (scopeSnapshot ?: LsposedScopeReader.read(context)).packages
        val packagesToCheck = targetPackages.filterTo(linkedSetOf()) {
            it in DeclaredWhatsAppTargets.packages() && it in liveScope
        }

        val details = mutableListOf<TargetIsolationDetail>()
        val blockedList = mutableListOf<String>()

        for (pkg in packagesToCheck) {
            val appLabel = resolveAppLabel(context, pkg)
            var foundInAnyScope = false
            var primaryBlockReason: String? = null

            for ((_, config) in allConfigs) {
                val templates = config.optJSONObject("templates") ?: JSONObject()
                val scope = config.optJSONObject("scope") ?: JSONObject()
                val scopeObj = scope.optJSONObject(pkg) ?: continue

                foundInAnyScope = true
                val blockReason = checkScopeBlockReason(scopeObj, templates, moduleSignatures)
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

    private fun isHmaInstalledAndActive(context: Context): Boolean {
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
        val installedHmaPackages = getInstalledHmaPackages(context)
        if (installedHmaPackages.isNotEmpty()) {
            return true
        }

        // 4. Check if any HMA config file exists directly
        val configPaths = findAllHmaConfigPaths()
        return configPaths.isNotEmpty()
    }

    /**
     * Dynamically discovers all installed HMA / HMAL packages on the system.
     */
    private fun getInstalledHmaPackages(context: Context): List<String> {
        val packages = mutableSetOf<String>()
        try {
            val pm = context.packageManager
            val installed = pm.getInstalledPackages(0).map { it.packageName }
            for (pkg in installed) {
                val lower = pkg.lowercase()
                if (lower.contains("hidemyapp") || lower.contains("hma") || lower.endsWith(".hmal")) {
                    packages.add(pkg)
                }
            }
        } catch (_: Exception) {}

        // Also query via root package manager in case package visibility is restricted
        val pmOut = AppRestartHelper.runRootCommand("pm list packages")
        if (!pmOut.isNullOrBlank()) {
            pmOut.lines().forEach { raw ->
                val line = raw.trim()
                if (line.startsWith("package:")) {
                    val p = line.removePrefix("package:").trim()
                    val lower = p.lowercase()
                    if (lower.contains("hidemyapp") || lower.contains("hma") || lower.endsWith(".hmal")) {
                        packages.add(p)
                    }
                }
            }
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
     * Reads all available HMA / HMAL configurations across the device.
     */
    private fun readAllHmaConfigs(): List<Pair<String, JSONObject>> {
        val paths = findAllHmaConfigPaths()
        val configs = mutableListOf<Pair<String, JSONObject>>()
        for (path in paths) {
            val out = AppRestartHelper.runRootCommand("cat $path 2>/dev/null")
            if (!out.isNullOrBlank() && out.trimStart().startsWith("{")) {
                try {
                    configs.add(path to JSONObject(out))
                } catch (_: Exception) {}
            }
        }
        return configs
    }

    /**
     * Finds all valid HMA / HMAL configuration paths across system directories,
     * zygisk data directories, and app-internal private files.
     */
    private fun findAllHmaConfigPaths(): List<String> {
        val findCmd = "find /data/misc /data/system /data/adb /data/user/0 /data/data -name 'config.json' " +
                "\\( -path '*hide_my_applist*' -o -path '*hma*' \\) 2>/dev/null; true"
        val out = AppRestartHelper.runRootCommand(findCmd) ?: return emptyList()
        return out.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.endsWith("config.json") }
            .distinct()
    }

    /**
     * Fixes HMA isolation settings for the specified [targetPackage] by removing
     * any blocking rules across all found HMA config files (such as 'xposed' preset,
     * module in blacklist extra list/template, or adding module to whitelist extra list/template
     * when whitelist mode is enabled).
     */
    suspend fun fixTargetIsolation(context: Context, targetPackage: String): Boolean = withContext(Dispatchers.IO) {
        if (!DeclaredWhatsAppTargets.contains(targetPackage) ||
            !LsposedScopeReader.read(context).contains(targetPackage)) return@withContext false
        // Force-stop HMA and any related processes BEFORE reading/modifying configuration
        forceStopHmaApps(context)

        val configPaths = findAllHmaConfigPaths()
        if (configPaths.isEmpty()) return@withContext false

        val moduleSignatures = setOf(
            context.packageName,
            "com.waenhancer",
            "com.waenhancer.debug"
        )

        var anySuccess = false

        for (configPath in configPaths) {
            val currentJsonStr = AppRestartHelper.runRootCommand("cat $configPath 2>/dev/null") ?: continue
            if (!currentJsonStr.trimStart().startsWith("{")) continue

            try {
                val root = JSONObject(currentJsonStr)
                val scope = root.optJSONObject("scope") ?: continue
                val targetScope = scope.optJSONObject(targetPackage) ?: continue

                val useWhitelist = targetScope.optBoolean("useWhitelist", false)

                if (useWhitelist) {
                    // Whitelist mode: To make module visible, module package MUST be in extraAppList
                    val extraAppList = targetScope.optJSONArray("extraAppList") ?: JSONArray().also {
                        targetScope.put("extraAppList", it)
                    }
                    val currentApps = (0 until extraAppList.length()).map { extraAppList.optString(it) }.toMutableSet()
                    currentApps.add(context.packageName)
                    val newArr = JSONArray()
                    currentApps.forEach { newArr.put(it) }
                    targetScope.put("extraAppList", newArr)

                    // Also remove from extraOppositeAppList if present
                    targetScope.optJSONArray("extraOppositeAppList")?.let { arr ->
                        val filtered = JSONArray()
                        for (i in 0 until arr.length()) {
                            val v = arr.optString(i)
                            if (v !in moduleSignatures) filtered.put(v)
                        }
                        targetScope.put("extraOppositeAppList", filtered)
                    }
                } else {
                    // Blacklist mode: remove module package from extraAppList
                    targetScope.optJSONArray("extraAppList")?.let { arr ->
                        val filtered = JSONArray()
                        for (i in 0 until arr.length()) {
                            val v = arr.optString(i)
                            if (v !in moduleSignatures) filtered.put(v)
                        }
                        targetScope.put("extraAppList", filtered)
                    }

                    // Remove "xposed" preset from applyPresets
                    targetScope.optJSONArray("applyPresets")?.let { arr ->
                        val filtered = JSONArray()
                        for (i in 0 until arr.length()) {
                            val v = arr.optString(i)
                            if (!v.equals("xposed", ignoreCase = true)) filtered.put(v)
                        }
                        targetScope.put("applyPresets", filtered)
                    }
                }

                // Remove module from any blacklist templates
                val templates = root.optJSONObject("templates")
                if (templates != null) {
                    val keys = templates.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val template = templates.optJSONObject(key) ?: continue
                        val isWhitelist = template.optBoolean("isWhitelist", false)
                        if (!isWhitelist) {
                            template.optJSONArray("appList")?.let { arr ->
                                val filtered = JSONArray()
                                for (i in 0 until arr.length()) {
                                    val v = arr.optString(i)
                                    if (v !in moduleSignatures) filtered.put(v)
                                }
                                template.put("appList", filtered)
                            }
                        }
                    }
                }

                // Keep a recoverable copy before replacing HMA's live configuration.
                val quotedConfig = shellQuote(configPath)
                val backupConfig = "$configPath.waex.bak"
                val quotedBackup = shellQuote(backupConfig)
                val backupResult = AppRestartHelper.runRootCommand(
                    "cp -p $quotedConfig $quotedBackup 2>/dev/null && " +
                            "(restorecon $quotedBackup 2>/dev/null || true) && " +
                            "echo WAEX_HMA_BACKUP_OK"
                )
                if (backupResult?.contains("WAEX_HMA_BACKUP_OK") != true) continue

                // Write modified JSON using a temporary file, preserving owner and SELinux context.
                val b64 = Base64.encodeToString(
                    root.toString().toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                val tmpConfig = "$configPath.tmp"
                val quotedTmp = shellQuote(tmpConfig)

                val writeScript = "OWNER=\$(stat -c '%u:%g' $quotedConfig 2>/dev/null); " +
                        "echo '$b64' | base64 -d > $quotedTmp && " +
                        "([ -z \"\$OWNER\" ] || chown \$OWNER $quotedTmp 2>/dev/null) && " +
                        "chmod 600 $quotedTmp 2>/dev/null && " +
                        "(restorecon $quotedTmp 2>/dev/null || true) && " +
                        "mv -f $quotedTmp $quotedConfig && " +
                        "([ -z \"\$OWNER\" ] || chown \$OWNER $quotedConfig 2>/dev/null) && " +
                        "chmod 600 $quotedConfig 2>/dev/null && " +
                        "(restorecon $quotedConfig 2>/dev/null || true) && " +
                        "echo WAEX_HMA_WRITE_OK"

                val writeResult = AppRestartHelper.runRootCommand(writeScript)
                val verifiedJson = if (writeResult?.contains("WAEX_HMA_WRITE_OK") == true) {
                    AppRestartHelper.runRootCommand("cat $quotedConfig 2>/dev/null")
                } else null
                val verified = try {
                    val parsed = JSONObject(verifiedJson ?: "")
                    val verifiedScope = parsed.optJSONObject("scope")?.optJSONObject(targetPackage)
                    val verifiedTemplates = parsed.optJSONObject("templates") ?: JSONObject()
                    verifiedScope != null && checkScopeBlockReason(
                        verifiedScope, verifiedTemplates, moduleSignatures
                    ) == null
                } catch (_: Exception) {
                    false
                }

                if (verified) {
                    anySuccess = true
                } else {
                    // A failed/partial write must never strand the user with a broken HMA config.
                    AppRestartHelper.runRootCommand(
                        "cp -p $quotedBackup $quotedConfig 2>/dev/null && " +
                                "restorecon $quotedConfig 2>/dev/null; true"
                    )
                }
            } catch (_: Exception) {}
        }

        // Force-stop again after updating config to ensure clean state
        forceStopHmaApps(context)

        // Clear cached diagnostic result so next check reflects new state
        cachedResult.set(null)
        anySuccess
    }

    /**
     * Force-stops all HMA and related app instances and processes.
     */
    fun forceStopHmaApps(context: Context) {
        val installedHmas = getInstalledHmaPackages(context)
        for (pkg in installedHmas) {
            AppRestartHelper.runRootCommand("am force-stop $pkg")
        }
        // Also fallback to any packages directly found by pm
        val pmOut = AppRestartHelper.runRootCommand("pm list packages")
        if (!pmOut.isNullOrBlank()) {
            pmOut.lines().forEach { raw ->
                val line = raw.trim()
                if (line.startsWith("package:")) {
                    val p = line.removePrefix("package:").trim()
                    val lower = p.lowercase()
                    if (lower.contains("hma") || lower.contains("hidemyapp") || lower.endsWith(".hmal")) {
                        AppRestartHelper.runRootCommand("am force-stop $p")
                    }
                }
            }
        }
    }

    private fun checkScopeBlockReason(
        scopeObj: JSONObject,
        templates: JSONObject,
        moduleSignatures: Set<String>
    ): String? {
        val useWhitelist = scopeObj.optBoolean("useWhitelist", false)
        val extraAppList = scopeObj.optJSONArray("extraAppList")?.toStringSet() ?: emptySet()
        val extraOppositeAppList = scopeObj.optJSONArray("extraOppositeAppList")?.toStringSet() ?: emptySet()

        if (useWhitelist) {
            // Whitelist mode: if module is in opposite list, it is hidden/blocked
            if (extraOppositeAppList.any { it in moduleSignatures }) {
                return "Hidden via Whitelist mode (in invisible apps list)"
            }

            // Check if any applied whitelist template allows the module
            val appliedTemplates = scopeObj.optJSONArray("applyTemplates")?.toStringSet() ?: emptySet()
            var allowedByTemplate = false
            for (templateName in appliedTemplates) {
                val template = templates.optJSONObject(templateName) ?: continue
                val isTemplateWhitelist = template.optBoolean("isWhitelist", false)
                val templateApps = template.optJSONArray("appList")?.toStringSet() ?: emptySet()
                if (isTemplateWhitelist && templateApps.any { it in moduleSignatures }) {
                    allowedByTemplate = true
                    break
                }
            }

            // In Whitelist mode, if the module is NOT explicitly allowed (neither in extraAppList nor in a whitelist template), it is hidden!
            val allowedInExtra = extraAppList.any { it in moduleSignatures }
            if (!allowedInExtra && !allowedByTemplate) {
                return "Hidden via Whitelist mode (module not included in visible apps)"
            }
        } else {
            // Blacklist mode: if module is in extraAppList, it is hidden/blocked
            if (extraAppList.any { it in moduleSignatures }) {
                return "Hidden via Blacklist mode (in additional apps to hide)"
            }

            // Check applied blacklist templates
            val appliedTemplates = scopeObj.optJSONArray("applyTemplates")?.toStringSet() ?: emptySet()
            for (templateName in appliedTemplates) {
                val template = templates.optJSONObject(templateName) ?: continue
                val isTemplateWhitelist = template.optBoolean("isWhitelist", false)
                val templateApps = template.optJSONArray("appList")?.toStringSet() ?: emptySet()

                if (!isTemplateWhitelist && templateApps.any { it in moduleSignatures }) {
                    return "Hidden by blacklist template \"$templateName\""
                }
            }

            // Check presets (e.g. "xposed" preset hides all Xposed modules / manager)
            val appliedPresets = scopeObj.optJSONArray("applyPresets")?.toStringSet() ?: emptySet()
            if (appliedPresets.any { it.equals("xposed", ignoreCase = true) }) {
                return "Hidden by HMA \"xposed\" preset"
            }
        }

        return null
    }

    private fun JSONArray.toStringSet(): Set<String> {
        val set = mutableSetOf<String>()
        for (i in 0 until length()) {
            optString(i, null)?.let { set.add(it) }
        }
        return set
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
