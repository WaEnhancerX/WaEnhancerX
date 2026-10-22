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

    enum class Decision(val allowed: Boolean) {
        BUILTIN_RULE(true), CUSTOM_RULE(true), EXPLICIT_BYPASS(true),
        UNSUPPORTED(false), RULES_UNAVAILABLE(false), VERSION_UNAVAILABLE(false),
        PREFERENCES_UNAVAILABLE(false)
    }

    private val STATIC_BUILTIN_RULES = listOf(
        "2.26.42.xx",
        "2.26.41.xx",
        "2.26.40.xx",
        "2.26.39.xx",
        "2.26.38.xx",
        "2.26.37.xx",
        "2.26.36.xx"
    )

    /**
     * Read only the module's version-rule array. Numeric resource IDs are local
     * to a resource table, so a host or unrelated array must never supply rules.
     */
    private fun readBuiltinRules(context: Context?): List<String>? {
        if (context == null) return STATIC_BUILTIN_RULES
        return try {
            val moduleContext = if (context.packageName == MODULE_PACKAGE) {
                context
            } else {
                context.createPackageContext(
                    MODULE_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY
                )
            }
            if (moduleContext.packageName != MODULE_PACKAGE) {
                return STATIC_BUILTIN_RULES
            }
            val resources = moduleContext.resources
            val id = resources.getIdentifier("universal_supported_versions", "array", MODULE_PACKAGE)
                .takeIf { it != 0 } ?: R.array.universal_supported_versions
            val resName = try { resources.getResourceName(id) } catch (_: RuntimeException) { null }
            if (resName != "$MODULE_PACKAGE:array/universal_supported_versions") {
                return STATIC_BUILTIN_RULES
            }
            val rules = resources.getStringArray(id).map { it.trim() }
            val syntax = Regex("[0-9]+(?:\\.[0-9]+)+(?:\\.(?:xx|\\*))?", RegexOption.IGNORE_CASE)
            rules.takeIf { it.isNotEmpty() && it.all(syntax::matches) } ?: STATIC_BUILTIN_RULES
        } catch (_: RuntimeException) {
            STATIC_BUILTIN_RULES
        } catch (_: PackageManager.NameNotFoundException) {
            STATIC_BUILTIN_RULES
        } catch (_: LinkageError) {
            STATIC_BUILTIN_RULES
        }
    }

    @JvmStatic
    fun getBuiltinSupportedVersions(context: Context?): List<String> = readBuiltinRules(context).orEmpty()

    /** A rules decision, not a claim that native methods or every feature were tested. */
    @JvmStatic
    fun evaluate(context: Context?, versionName: String?, prefs: SharedPreferences?): Decision {
        if (versionName.isNullOrBlank()) return Decision.VERSION_UNAVAILABLE
        val cleanVer = versionName.trim().removePrefix("v")
        if (cleanVer.isBlank()) return Decision.VERSION_UNAVAILABLE
        return try {
            if (prefs?.getBoolean("bypass_version_check", false) == true) return Decision.EXPLICIT_BYPASS
            val rules = readBuiltinRules(context)
            if (rules?.any { matches(cleanVer, it) } == true) return Decision.BUILTIN_RULE
            if (prefs?.getBoolean("customize_supported_versions", false) == true &&
                getCustomVersions(prefs).any { matches(cleanVer, it) }) return Decision.CUSTOM_RULE
            if (rules == null) Decision.RULES_UNAVAILABLE else Decision.UNSUPPORTED
        } catch (_: RuntimeException) {
            Decision.PREFERENCES_UNAVAILABLE
        } catch (_: LinkageError) {
            Decision.PREFERENCES_UNAVAILABLE
        }
    }

    @JvmStatic
    fun isSupported(context: Context?, versionName: String?, prefs: SharedPreferences?): Boolean =
        evaluate(context, versionName, prefs).allowed

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
