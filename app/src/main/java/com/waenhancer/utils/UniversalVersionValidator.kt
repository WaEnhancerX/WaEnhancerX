package com.waenhancer.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.waenhancer.app.R
import java.util.LinkedHashSet

/**
 * Universal Version Validator for WhatsApp, WhatsApp Business, and all WhatsApp Clones.
 * Dynamically loads universal supported version rules from arrays.xml.
 */
object UniversalVersionValidator {

    private const val MODULE_PACKAGE = "com.waenhancer"

    /**
     * Dynamically retrieves the universal built-in supported versions from arrays.xml.
     */
    @JvmStatic
    fun getBuiltinSupportedVersions(context: Context?): List<String> {
        if (context == null) return emptyList()
        return try {
            val moduleContext = if (context.packageName == MODULE_PACKAGE) {
                context
            } else {
                try {
                    context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
                } catch (_: Throwable) {
                    context
                }
            }
            val resId = moduleContext.resources.getIdentifier("universal_supported_versions", "array", MODULE_PACKAGE)
            if (resId != 0) {
                moduleContext.resources.getStringArray(resId).toList()
            } else {
                context.resources.getStringArray(R.array.universal_supported_versions).toList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Checks whether [versionName] is supported based on arrays.xml defaults, user custom additions,
     * or bypass flags across ALL WhatsApp packages.
     */
    @JvmStatic
    fun isSupported(
        context: Context?,
        versionName: String?,
        prefs: SharedPreferences?
    ): Boolean {
        if (versionName.isNullOrBlank()) return false

        // Check if user enabled "Bypass Version Check"
        val bypass = prefs?.getBoolean("bypass_version_check", false) ?: false
        if (bypass) return true

        val cleanVer = versionName.trim().removePrefix("v")

        // 1. Check dynamically loaded universal system versions from arrays.xml
        val systemVersions = getBuiltinSupportedVersions(context)
        for (sysVer in systemVersions) {
            if (matches(cleanVer, sysVer)) {
                return true
            }
        }

        // 2. Check user-defined custom versions if enabled
        val customizeEnabled = prefs != null && prefs.getBoolean("customize_supported_versions", false)
        if (customizeEnabled) {
            val customVersions = getCustomVersions(prefs)
            for (customVer in customVersions) {
                if (matches(cleanVer, customVer)) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Backward-compatible overload for existing call-sites
     */
    @JvmStatic
    fun isSupported(
        versionName: String?,
        prefs: SharedPreferences?
    ): Boolean {
        return isSupported(null, versionName, prefs)
    }

    /**
     * Checks whether [currentVersion] matches the pattern or wildcard [rule] (e.g. 2.26.30.xx).
     * Examples:
     * - "2.26.30.97" matches "2.26.30.xx" -> true
     * - "2.26.34.2" matches "2.26.34.xx" -> true
     * - "2.26.34.2" matches "2.26.30.xx" -> false
     * - "2.26.30.97" matches "2.26.30.97" -> true
     */
    @JvmStatic
    fun matches(currentVersion: String, rule: String): Boolean {
        val cleanCurrent = currentVersion.trim().removePrefix("v")
        val cleanRule = rule.trim().removePrefix("v")
        if (cleanCurrent.isEmpty() || cleanRule.isEmpty()) return false

        if (cleanCurrent.equals(cleanRule, ignoreCase = true)) {
            return true
        }

        if (cleanRule.endsWith(".xx", ignoreCase = true)) {
            val prefix = cleanRule.substring(0, cleanRule.length - 3) + "."
            return (cleanCurrent + ".").startsWith(prefix)
        }

        if (cleanRule.endsWith(".*")) {
            val prefix = cleanRule.substring(0, cleanRule.length - 2) + "."
            return (cleanCurrent + ".").startsWith(prefix)
        }

        return false
    }

    /**
     * Converts a specific version (e.g. 2.26.36.21) into a branch wildcard (e.g. 2.26.36.xx).
     */
    @JvmStatic
    fun toWildcard(version: String): String {
        val clean = version.trim().removePrefix("v")
        if (clean.endsWith(".xx")) return clean
        val parts = clean.split(".")
        return if (parts.size >= 3) {
            "${parts[0]}.${parts[1]}.${parts[2]}.xx"
        } else if (parts.size == 2) {
            "${parts[0]}.${parts[1]}.xx"
        } else {
            "$clean.xx"
        }
    }

    @JvmStatic
    fun getCustomVersions(prefs: SharedPreferences): Set<String> {
        val set = prefs.getStringSet("custom_supported_versions", null)
        if (set != null && set.isNotEmpty()) {
            return LinkedHashSet(set)
        }
        val str = prefs.getString("custom_supported_versions_str", null)
        if (!str.isNullOrBlank()) {
            return str.split("\n", ",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
        return emptySet()
    }

    @JvmStatic
    fun addCustomVersion(prefs: SharedPreferences, newVersion: String): Boolean {
        val clean = newVersion.trim().removePrefix("v")
        if (clean.isEmpty()) return false
        val currentSet = LinkedHashSet(getCustomVersions(prefs))
        currentSet.add(clean)
        prefs.edit()
            .putBoolean("customize_supported_versions", true)
            .putStringSet("custom_supported_versions", currentSet)
            .putString("custom_supported_versions_str", currentSet.joinToString("\n"))
            .apply()
        return true
    }

    @JvmStatic
    fun removeCustomVersion(prefs: SharedPreferences, versionToRemove: String): Boolean {
        val clean = versionToRemove.trim().removePrefix("v")
        val currentSet = LinkedHashSet(getCustomVersions(prefs))
        val removed = currentSet.remove(clean)
        if (removed) {
            prefs.edit()
                .putStringSet("custom_supported_versions", currentSet)
                .putString("custom_supported_versions_str", currentSet.joinToString("\n"))
                .apply()
        }
        return removed
    }

    @JvmStatic
    fun getInstalledPackageVersion(context: Context, packageName: String): String? {
        return try {
            val pInfo = context.packageManager.getPackageInfo(packageName, 0)
            pInfo.versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
