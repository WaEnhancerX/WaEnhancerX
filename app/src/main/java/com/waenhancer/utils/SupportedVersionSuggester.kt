package com.waenhancer.utils

import java.net.URLEncoder

/**
 * A version the user can move to when their installed build is unsupported.
 *
 * @property version a concrete build (for example `2.26.45.71`) or, when no concrete build is
 * known, the newest built-in wildcard rule (for example `2.26.45.xx`).
 * @property isExact true when [version] is a concrete build, false for the wildcard fallback.
 */
data class VersionSuggestion(val version: String, val isExact: Boolean)

/** Picks the newest version that satisfies the module's built-in supported-version rules. */
object SupportedVersionSuggester {

    const val PACKAGE_WHATSAPP = "com.whatsapp"
    const val PACKAGE_BUSINESS = "com.whatsapp.w4b"

    /** Only the official WhatsApp packages get a suggestion; clones do not. */
    @JvmStatic
    fun isSuggestionTarget(packageName: String): Boolean =
        packageName == PACKAGE_WHATSAPP || packageName == PACKAGE_BUSINESS

    /** Numeric, segment-by-segment comparison. Non-numeric segments count as 0. */
    @JvmStatic
    fun compareVersions(a: String, b: String): Int {
        val left = segments(a)
        val right = segments(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    /**
     * The newest [candidates] entry matching any of [builtinRules], or null when none match.
     * Custom user rules are deliberately not part of this: they are not verified versions.
     */
    @JvmStatic
    fun pickLatestSupported(candidates: Collection<String>, builtinRules: List<String>): String? =
        candidates
            .map { it.trim().removePrefix("v") }
            .filter { candidate ->
                CONCRETE_VERSION.matches(candidate) &&
                    builtinRules.any { UniversalVersionValidator.matches(candidate, it) }
            }
            .maxWithOrNull { a, b -> compareVersions(a, b) }

    /** The newest built-in rule, used when no concrete build is available. */
    @JvmStatic
    fun newestBuiltinRule(builtinRules: List<String>): String? =
        builtinRules
            .map { it.trim().removePrefix("v") }
            .filter { it.isNotEmpty() }
            .maxWithOrNull { a, b -> compareVersions(ruleBase(a), ruleBase(b)) }

    /** A concrete build when one is known, otherwise the newest built-in rule, otherwise null. */
    @JvmStatic
    fun suggest(candidates: Collection<String>, builtinRules: List<String>): VersionSuggestion? {
        pickLatestSupported(candidates, builtinRules)?.let { return VersionSuggestion(it, true) }
        return newestBuiltinRule(builtinRules)?.let { VersionSuggestion(it, false) }
    }

    /** APKMirror search page for [version], opened in the user's browser. */
    @JvmStatic
    fun downloadSearchUrl(packageName: String, version: String): String {
        val name = if (packageName == PACKAGE_BUSINESS) "whatsapp business" else "whatsapp"
        val base = ruleBase(version.trim().removePrefix("v"))
        val query = URLEncoder.encode("$name $base", "UTF-8")
        return "https://www.apkmirror.com/?post_type=app_release&searchtype=apk&s=$query"
    }

    private val CONCRETE_VERSION = Regex("[0-9]+(?:\\.[0-9]+)+")

    private fun ruleBase(rule: String): String =
        rule.removeSuffix(".xx").removeSuffix(".XX").removeSuffix(".*")

    private fun segments(version: String): List<Int> =
        version.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
}
