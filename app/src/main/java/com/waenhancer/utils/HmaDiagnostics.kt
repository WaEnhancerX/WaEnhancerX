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
        val allConfigPaths = findAllHmaConfigPaths()
        if (allConfigPaths.isEmpty()) {
            return if (!isHmaInstalledAndActive(context)) {
                HmaReport(HmaState.NOT_ACTIVE)
            } else {
                HmaReport(HmaState.NOT_CONFIGURED)
            }
        }

        val allConfigs = readAllHmaConfigs(allConfigPaths)
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

    private val KNOWN_CONFIG_PATHS = listOf(
        "/data/adb/hma/config.json",
        "/data/misc/hide_my_applist/config.json",
        "/data/misc/hide_my_applist_oss/config.json",
        "/data/system/hide_my_applist/config.json",
        "/data/system/users/0/hide_my_applist/config.json",
        "/data/data/org.frknkrc44.hma_oss/files/config.json",
        "/data/data/com.tsng.hidemyapplist/files/config.json",
        "/data/data/com.google.android.hmal/files/config.json",
        "/data/user/0/org.frknkrc44.hma_oss/files/config.json",
        "/data/user/0/com.tsng.hidemyapplist/files/config.json",
        "/data/user/0/com.google.android.hmal/files/config.json"
    )

    private fun isHmaInstalledAndActive(context: Context): Boolean {
        // 1. In-process Package Manager check (0 root overhead, 0ms)
        for (pkg in KNOWN_HMA_PACKAGES) {
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                return true
            } catch (_: Exception) {}
        }

        // 2. Fast combined filesystem / module check (1 single root command, ~5ms)
        val checkCmd = "[ -d $HMA_ZYGISK_PATH ] && [ ! -f $HMA_ZYGISK_PATH/disable ] && echo active; " +
                "ls -d /data/adb/modules/*hma* 2>/dev/null; " +
                "ls -d /data/data/org.frknkrc44.hma_oss /data/data/com.tsng.hidemyapplist /data/data/com.google.android.hmal 2>/dev/null; " +
                "ls /data/adb/hma/config.json /data/misc/hide_my_applist*/config.json 2>/dev/null; true"
        val quickCheck = AppRestartHelper.runRootCommand(checkCmd)
        if (!quickCheck.isNullOrBlank()) {
            return true
        }

        // 3. LSPosed module database check (only if LSP config exists)
        val lspCheck = AppRestartHelper.runRootCommand(
            "[ -f $LSP_CONFIG_DB ] && (grep -E 'hidemyapp|hma' $LSP_CONFIG_DB 2>/dev/null || sqlite3 $LSP_CONFIG_DB \"SELECT 1 FROM modules WHERE (module_pkg_name LIKE '%hidemyapp%' OR module_pkg_name LIKE '%hma%') AND enabled = 1 LIMIT 1\" 2>/dev/null); true"
        )
        if (!lspCheck.isNullOrBlank()) {
            return true
        }

        return false
    }

    /**
     * Dynamically discovers all installed HMA / HMAL packages on the system.
     */
    private fun getInstalledHmaPackages(context: Context): List<String> {
        val packages = mutableSetOf<String>()
        val pm = context.packageManager
        for (pkg in KNOWN_HMA_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                packages.add(pkg)
            } catch (_: Exception) {}
        }
        if (packages.isNotEmpty()) {
            return packages.toList()
        }

        // Fast fallback: query only known HMA packages directly via pm path (no full pm dump)
        val quickCheck = AppRestartHelper.runRootCommand(
            KNOWN_HMA_PACKAGES.joinToString("; ") { "pm path $it 2>/dev/null" }
        )
        if (!quickCheck.isNullOrBlank()) {
            quickCheck.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("package:")) {
                    KNOWN_HMA_PACKAGES.forEach { known ->
                        if (trimmed.contains(known)) packages.add(known)
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
    private fun readAllHmaConfigs(paths: List<String> = findAllHmaConfigPaths()): List<Pair<String, JSONObject>> {
        val configs = mutableListOf<Pair<String, JSONObject>>()
        for (path in paths) {
            val out = AppRestartHelper.runRootCommand("cat ${shellQuote(path)} 2>/dev/null")
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
        val checkScript = KNOWN_CONFIG_PATHS.joinToString(" ") { shellQuote(it) }
        val out = AppRestartHelper.runRootCommand(
            "ls $checkScript 2>/dev/null; ls /data/misc/hide_my_applist*/config.json 2>/dev/null; true"
        )
        val directMatches = out?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() && it.endsWith("config.json") }
            ?.distinct()
            .orEmpty()

        if (directMatches.isNotEmpty()) {
            return directMatches
        }

        // Fast fallback: shallow search ONLY in /data/misc and /data/adb (maxdepth 3)
        // Never traverse /data/data or /data/user/0 which scans all apps on device!
        val shallowSearch = AppRestartHelper.runRootCommand(
            "find /data/misc /data/adb -maxdepth 3 -name 'config.json' 2>/dev/null; true"
        )
        return shallowSearch?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() && it.endsWith("config.json") && (it.contains("hma") || it.contains("hide_my_applist")) }
            ?.distinct()
            .orEmpty()
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
        val installedHmas = getInstalledHmaPackages(context).ifEmpty { KNOWN_HMA_PACKAGES }
        val stopCmd = installedHmas.joinToString("; ") { "am force-stop $it" }
        AppRestartHelper.runRootCommand(stopCmd)
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
