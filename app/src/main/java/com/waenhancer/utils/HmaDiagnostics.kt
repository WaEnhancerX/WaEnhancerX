package com.waenhancer.utils

import android.content.Context
import com.waenhancer.xposed.utils.AppRestartHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

    private const val REASON_NOT_CONFIGURED = "Not configured in HMA scope"
    private const val REASON_HMA_NOT_ACTIVE = "HMA not active"

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
        if (!NativeDiagPaths.available) {
            return@withContext HmaReport(HmaState.NOT_ACTIVE, message = "Diagnostics unavailable on this device architecture")
        }
        if (!forceRefresh) {
            cachedResult.get()?.let { return@withContext it }
        }

        val report = runCheck(context.applicationContext, targetPackages, scopeSnapshot)
        cachedResult.set(report)
        report
    }

    private fun runCheck(context: Context, targetPackages: Collection<String>, scopeSnapshot: LsposedScopeSnapshot?): HmaReport {
        // Empty means no live scoped targets. Never fall back to every installed/declared app:
        // scope is the security boundary for this diagnostic.
        val liveScope = (scopeSnapshot ?: LsposedScopeReader.read(context)).packages
        val packagesToCheck = targetPackages.filterTo(linkedSetOf()) { it in liveScope }

        fun inactiveReport(state: HmaState, reason: String) = HmaReport(
            state,
            targetDetails = packagesToCheck.map {
                TargetIsolationDetail(it, resolveAppLabel(context, it), isBlocking = false, reason = reason)
            }
        )

        val allConfigPaths = findAllHmaConfigPaths(context)
        if (allConfigPaths.isEmpty()) {
            return if (!isHmaInstalledAndActive(context)) {
                inactiveReport(HmaState.NOT_ACTIVE, REASON_HMA_NOT_ACTIVE)
            } else {
                inactiveReport(HmaState.NOT_CONFIGURED, REASON_NOT_CONFIGURED)
            }
        }

        val allConfigs = readAllHmaConfigs(context, allConfigPaths)
        if (allConfigs.isEmpty()) {
            return inactiveReport(HmaState.NOT_CONFIGURED, REASON_NOT_CONFIGURED)
        }

        // Known module package identifiers to check for concealment
        val currentPackage = context.packageName
        val moduleSignatures = setOf(currentPackage, "com.waenhancer", "com.waenhancer.debug")

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
                        reason = REASON_NOT_CONFIGURED
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
            packageName
        }
    }

    private val KNOWN_HMA_PACKAGES: Set<String> by lazy { NativeDiagPaths.knownHmaPackages().toSet() }

    private val KNOWN_CONFIG_PATHS: List<String> by lazy { NativeDiagPaths.knownHmaConfigPaths().toList() }

    private fun isHmaInstalledAndActive(context: Context): Boolean {
        // 1. In-process Package Manager check (0 root overhead, 0ms)
        for (pkg in KNOWN_HMA_PACKAGES) {
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                return true
            } catch (_: Exception) {}
        }

        // 2. Fast combined filesystem / module check (1 single root command, ~5ms)
        val quickCheck = AppRestartHelper.runRootCommand(NativeDiagPaths.hmaActiveCheckCommand())
        if (!quickCheck.isNullOrBlank()) {
            return true
        }

        // 3. LSPosed module database check (only if LSP config exists)
        val lspCheck = AppRestartHelper.runRootCommand(NativeDiagPaths.lspHmaCheckCommand())
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

    private fun isModuleMatch(target: String, signatures: Set<String>): Boolean {
        val clean = target.trim()
        return signatures.any { it.equals(clean, ignoreCase = true) }
    }

    private fun isXposedPreset(preset: String): Boolean {
        val clean = preset.trim().lowercase()
        return clean == "xposed" || clean.contains("xposed") || clean.contains("lsposed") || clean == "modules" || clean == "xposed_modules" || clean == "lsposed_modules"
    }

    /**
     * Reads all available HMA / HMAL configurations across the device.
     */
    private fun readAllHmaConfigs(context: Context, paths: List<String> = findAllHmaConfigPaths(context)): List<Pair<String, JSONObject>> {
        val configs = mutableListOf<Pair<String, JSONObject>>()
        for (path in paths) {
            val tmpCopy = File(context.cacheDir, "hma_read_${System.nanoTime()}.json")
            var content: String? = null
            try {
                val quotedSrc = shellQuote(path)
                val quotedDst = shellQuote(tmpCopy.absolutePath)
                val copyCmd = "cp -f $quotedSrc $quotedDst 2>/dev/null && chmod 644 $quotedDst 2>/dev/null"
                AppRestartHelper.runRootCommand(copyCmd)
                if (tmpCopy.exists() && tmpCopy.length() > 0L) {
                    content = tmpCopy.readText()
                }
            } catch (_: Exception) {
            } finally {
                tmpCopy.delete()
            }
            if (content.isNullOrBlank()) {
                content = AppRestartHelper.runRootCommand("cat ${shellQuote(path)} 2>/dev/null")
            }
            if (!content.isNullOrBlank() && content.trimStart().startsWith("{")) {
                try {
                    configs.add(path to JSONObject(content))
                } catch (_: Exception) {}
            }
        }
        return configs
    }

    /**
     * Finds all valid HMA / HMAL configuration paths across system directories,
     * zygisk data directories, and app-internal private files.
     */
    private fun findAllHmaConfigPaths(context: Context? = null): List<String> {
        val dynamicPaths = mutableSetOf<String>()
        dynamicPaths.addAll(KNOWN_CONFIG_PATHS)
        if (context != null) {
            val installedHmas = getInstalledHmaPackages(context)
            for (pkg in installedHmas) {
                dynamicPaths.add("/data/data/$pkg/files/config.json")
                dynamicPaths.add("/data/data/$pkg/files/temp_config.json")
                dynamicPaths.add("/data/user/0/$pkg/files/config.json")
                dynamicPaths.add("/data/user/0/$pkg/files/temp_config.json")
                dynamicPaths.add("/data/user_de/0/$pkg/files/config.json")
                dynamicPaths.add("/data/user_de/0/$pkg/files/temp_config.json")
            }
        }
        val checkScript = dynamicPaths.joinToString(" ") { shellQuote(it) }
        val out = AppRestartHelper.runRootCommand(
            "ls $checkScript 2>/dev/null; " + NativeDiagPaths.hmaConfigGlobCommand()
        )
        val directMatches = out?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() && (it.endsWith("config.json") || it.endsWith(".json")) }
            ?.distinct()
            .orEmpty()

        if (directMatches.isNotEmpty()) {
            return directMatches
        }

        // Fast fallback: shallow search in /data/misc, /data/adb, and /data/system
        val shallowSearch = AppRestartHelper.runRootCommand(NativeDiagPaths.hmaShallowSearchCommand())
        return shallowSearch?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() && (it.contains("hma") || it.contains("hide_my_applist")) }
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
        if (!NativeDiagPaths.available) return@withContext false
        if (!LsposedScopeReader.read(context).contains(targetPackage)) return@withContext false
        // Force-stop HMA and any related processes BEFORE reading/modifying configuration
        forceStopHmaApps(context)

        val configPaths = findAllHmaConfigPaths(context)
        if (configPaths.isEmpty()) return@withContext false

        val moduleSignatures = setOf(
            context.packageName,
            "com.waenhancer",
            "com.waenhancer.debug"
        )

        var anySuccess = false

        for (configPath in configPaths) {
            val tmpCopy = File(context.cacheDir, "hma_fix_read_${System.nanoTime()}.json")
            val currentJsonStr = try {
                val quotedSrc = shellQuote(configPath)
                val quotedDst = shellQuote(tmpCopy.absolutePath)
                AppRestartHelper.runRootCommand("cp -f $quotedSrc $quotedDst 2>/dev/null && chmod 644 $quotedDst 2>/dev/null")
                if (tmpCopy.exists() && tmpCopy.length() > 0L) tmpCopy.readText() else {
                    AppRestartHelper.runRootCommand("cat $quotedSrc 2>/dev/null")
                }
            } catch (_: Exception) {
                null
            } finally {
                tmpCopy.delete()
            } ?: continue
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
                            if (!isModuleMatch(v, moduleSignatures)) filtered.put(v)
                        }
                        targetScope.put("extraOppositeAppList", filtered)
                    }
                } else {
                    // Blacklist mode: remove module package from extraAppList
                    targetScope.optJSONArray("extraAppList")?.let { arr ->
                        val filtered = JSONArray()
                        for (i in 0 until arr.length()) {
                            val v = arr.optString(i)
                            if (!isModuleMatch(v, moduleSignatures)) filtered.put(v)
                        }
                        targetScope.put("extraAppList", filtered)
                    }

                    // Remove xposed presets from applyPresets
                    targetScope.optJSONArray("applyPresets")?.let { arr ->
                        val filtered = JSONArray()
                        for (i in 0 until arr.length()) {
                            val v = arr.optString(i)
                            if (!isXposedPreset(v)) filtered.put(v)
                        }
                        targetScope.put("applyPresets", filtered)
                    }
                }

                // Remove module and xposed presets from any blacklist templates
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
                                    if (!isModuleMatch(v, moduleSignatures)) filtered.put(v)
                                }
                                template.put("appList", filtered)
                            }
                            template.optJSONArray("applyPresets")?.let { arr ->
                                val filtered = JSONArray()
                                for (i in 0 until arr.length()) {
                                    val v = arr.optString(i)
                                    if (!isXposedPreset(v)) filtered.put(v)
                                }
                                template.put("applyPresets", filtered)
                            }
                            template.optJSONArray("presets")?.let { arr ->
                                val filtered = JSONArray()
                                for (i in 0 until arr.length()) {
                                    val v = arr.optString(i)
                                    if (!isXposedPreset(v)) filtered.put(v)
                                }
                                template.put("presets", filtered)
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

                // Write modified JSON using a temporary file in cacheDir
                val localTmpFile = File(context.cacheDir, "hma_fix_write_${System.nanoTime()}.json")
                localTmpFile.writeText(root.toString())
                val quotedLocalTmp = shellQuote(localTmpFile.absolutePath)

                val tmpConfig = "$configPath.tmp"
                val quotedTmp = shellQuote(tmpConfig)

                val writeScript = "OWNER=\$(stat -c '%u:%g' $quotedConfig 2>/dev/null); " +
                        "cp -f $quotedLocalTmp $quotedTmp 2>/dev/null && " +
                        "([ -z \"\$OWNER\" ] || chown \$OWNER $quotedTmp 2>/dev/null) && " +
                        "chmod 600 $quotedTmp 2>/dev/null && " +
                        "(restorecon $quotedTmp 2>/dev/null || true) && " +
                        "mv -f $quotedTmp $quotedConfig && " +
                        "([ -z \"\$OWNER\" ] || chown \$OWNER $quotedConfig 2>/dev/null) && " +
                        "chmod 600 $quotedConfig 2>/dev/null && " +
                        "(restorecon $quotedConfig 2>/dev/null || true) && " +
                        "echo WAEX_HMA_WRITE_OK"

                val writeResult = AppRestartHelper.runRootCommand(writeScript)
                localTmpFile.delete()

                val verifiedCopy = File(context.cacheDir, "hma_verify_${System.nanoTime()}.json")
                val verifiedJson = try {
                    if (writeResult?.contains("WAEX_HMA_WRITE_OK") == true) {
                        AppRestartHelper.runRootCommand("cp -f $quotedConfig ${shellQuote(verifiedCopy.absolutePath)} 2>/dev/null && chmod 644 ${shellQuote(verifiedCopy.absolutePath)} 2>/dev/null")
                        if (verifiedCopy.exists() && verifiedCopy.length() > 0L) verifiedCopy.readText() else {
                            AppRestartHelper.runRootCommand("cat $quotedConfig 2>/dev/null")
                        }
                    } else null
                } catch (_: Exception) {
                    null
                } finally {
                    verifiedCopy.delete()
                }

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
        if (!NativeDiagPaths.available) return
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
            if (extraOppositeAppList.any { isModuleMatch(it, moduleSignatures) }) {
                return "Hidden via Whitelist mode (in invisible apps list)"
            }

            // Check if any applied whitelist template allows the module
            val appliedTemplates = scopeObj.optJSONArray("applyTemplates")?.toStringSet() ?: emptySet()
            var allowedByTemplate = false
            for (templateName in appliedTemplates) {
                val template = templates.optJSONObject(templateName) ?: continue
                val isTemplateWhitelist = template.optBoolean("isWhitelist", false)
                val templateApps = template.optJSONArray("appList")?.toStringSet() ?: emptySet()
                if (isTemplateWhitelist && templateApps.any { isModuleMatch(it, moduleSignatures) }) {
                    allowedByTemplate = true
                    break
                }
            }

            // In Whitelist mode, if the module is NOT explicitly allowed (neither in extraAppList nor in a whitelist template), it is hidden!
            val allowedInExtra = extraAppList.any { isModuleMatch(it, moduleSignatures) }
            if (!allowedInExtra && !allowedByTemplate) {
                return "Hidden via Whitelist mode (module not included in visible apps)"
            }
        } else {
            // Blacklist mode: if module is in extraAppList, it is hidden/blocked
            if (extraAppList.any { isModuleMatch(it, moduleSignatures) }) {
                return "Hidden via Blacklist mode (in additional apps to hide)"
            }

            // Check applied blacklist templates
            val appliedTemplates = scopeObj.optJSONArray("applyTemplates")?.toStringSet() ?: emptySet()
            for (templateName in appliedTemplates) {
                val template = templates.optJSONObject(templateName) ?: continue
                val isTemplateWhitelist = template.optBoolean("isWhitelist", false)
                if (!isTemplateWhitelist) {
                    val templateApps = template.optJSONArray("appList")?.toStringSet() ?: emptySet()
                    if (templateApps.any { isModuleMatch(it, moduleSignatures) }) {
                        return "Hidden by blacklist template \"$templateName\""
                    }

                    // Check presets applied within this template
                    val templatePresets = (template.optJSONArray("applyPresets") ?: template.optJSONArray("presets"))?.toStringSet() ?: emptySet()
                    val presetMatch = templatePresets.firstOrNull { isXposedPreset(it) }
                    if (presetMatch != null) {
                        return "Hidden by template \"$templateName\" via preset \"$presetMatch\""
                    }
                }
            }

            // Check presets applied directly to the target scope
            val appliedPresets = scopeObj.optJSONArray("applyPresets")?.toStringSet() ?: emptySet()
            val presetMatch = appliedPresets.firstOrNull { isXposedPreset(it) }
            if (presetMatch != null) {
                return "Hidden by HMA preset \"$presetMatch\""
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
