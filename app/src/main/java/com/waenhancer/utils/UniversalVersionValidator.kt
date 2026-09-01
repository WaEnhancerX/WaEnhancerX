package com.waenhancer.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import java.util.LinkedHashSet
import java.util.regex.Pattern

/**
 * Universal Version Validator for WhatsApp and WhatsApp Business.
 * Maintains a single, universal supported version standard across both apps.
 */
object UniversalVersionValidator {

    const val PACKAGE_WPP = "com.whatsapp"
    const val PACKAGE_BUSINESS = "com.whatsapp.w4b"

    /**
     * Predefined universal base supported versions.
     * Wildcards ending with ".xx" match all builds in that minor version branch.
     */
    @JvmField
    val DEFAULT_UNIVERSAL_VERSIONS: List<String> = listOf(
        "2.26.xx",
        "2.25.xx",
        "2.24.xx"
    )

    private val VERSION_REGEX = Pattern.compile("^\\d+\\.\\d+\\.\\d+\\.(\\d+|xx)$")

    /**
     * Checks whether [versionName] is supported based on system defaults, user custom additions,
     * or bypass flags.
     */
    @JvmStatic
    fun isSupported(
        versionName: String?,
        prefs: SharedPreferences?
    ): Boolean {
        if (versionName.isNullOrBlank()) return false

        // Check if user enabled "Bypass Version Check"
        val bypass = prefs?.getBoolean("bypass_version_check", false) ?: false
        if (bypass) return true

        val cleanVer = versionName.trim()

        // 1. Check default universal system versions
        for (sysVer in DEFAULT_UNIVERSAL_VERSIONS) {
            if (matches(cleanVer, sysVer)) {
                return true
            }
        }

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
     * Checks whether [currentVersion] matches the pattern or wildcard [rule].
     * Examples:
     * - "2.26.18.72" matches "2.26.xx" -> true
     * - "2.26.18.72" matches "2.26.18.72" -> true
     * - "2.25.12.1" matches "2.26.xx" -> false
     */
    @JvmStatic
    fun matches(currentVersion: String, rule: String): Boolean {
        val cleanCurrent = currentVersion.trim()
        val cleanRule = rule.trim()
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
        val clean = newVersion.trim()
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
        val clean = versionToRemove.trim()
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
