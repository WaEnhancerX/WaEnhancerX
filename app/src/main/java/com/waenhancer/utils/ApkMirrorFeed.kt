package com.waenhancer.utils

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * One WhatsApp build listed in an APKMirror feed. [link] is its APKMirror release page and
 * [publishedAt] the feed's publish time in epoch milliseconds, or null when it is missing.
 */
data class ApkMirrorRelease(val version: String, val link: String, val publishedAt: Long? = null)

/**
 * Parses APKMirror's per-app RSS feed and narrows it to the builds WAEX supports.
 *
 * The feed text comes from a third-party page, so everything read from it is validated: only
 * https links on apkmirror.com are kept, and each app only accepts its own entries (WhatsApp
 * Business titles never reach the WhatsApp list and the other way round).
 */
object ApkMirrorFeed {

    private val ITEM = Regex("<item\\b[^>]*>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
    private val TITLE = Regex("<title\\b[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
    private val LINK = Regex("<link\\b[^>]*>(.*?)</link>", RegexOption.DOT_MATCHES_ALL)
    private val VERSION = Regex("(?<![0-9.])[0-9]+(?:\\.[0-9]+){2,3}(?![0-9.])")
    private val PUB_DATE = Regex("<pubDate\\b[^>]*>(.*?)</pubDate>", RegexOption.DOT_MATCHES_ALL)
    private val PRERELEASE = Regex("\\b(beta|alpha)\\b", RegexOption.IGNORE_CASE)

    /** Feed address for an official WhatsApp package, or null for any other package. */
    @JvmStatic
    fun feedUrl(packageName: String): String? =
        SupportedVersionSuggester.downloadPageUrl(packageName)?.let { it + "feed/" }

    /** Only https links on apkmirror.com may be opened from feed data. */
    @JvmStatic
    fun isTrustedLink(link: String): Boolean =
        link.startsWith("https://www.apkmirror.com/") || link.startsWith("https://apkmirror.com/")

    /**
     * Stable (non-beta) builds in [feedXml] that belong to [packageName]. One entry per version,
     * in feed order. Entries without a recognisable version or with an untrusted link are dropped.
     */
    @JvmStatic
    fun parse(feedXml: String, packageName: String): List<ApkMirrorRelease> {
        val wantsBusiness = packageName == SupportedVersionSuggester.PACKAGE_BUSINESS
        val seen = HashSet<String>()
        val result = ArrayList<ApkMirrorRelease>()
        ITEM.findAll(feedXml).forEach { item ->
            val body = item.groupValues[1]
            val title = TITLE.find(body)?.groupValues?.get(1)?.let(::text).orEmpty()
            val link = LINK.find(body)?.groupValues?.get(1)?.let(::text).orEmpty()
            val isBusiness = title.contains("business", ignoreCase = true)
            val version = VERSION.findAll(title).lastOrNull()?.value
            if (version != null &&
                isBusiness == wantsBusiness &&
                !PRERELEASE.containsMatchIn(title) &&
                isTrustedLink(link) &&
                seen.add(version)
            ) {
                val published = PUB_DATE.find(body)?.groupValues?.get(1)?.let(::text)?.let(::parseDate)
                result += ApkMirrorRelease(version, link, published)
            }
        }
        return result
    }

    /** Merges the pages of one feed (duplicates collapse by version, first link wins). */
    @JvmStatic
    fun parseAll(pages: List<String>, packageName: String): List<ApkMirrorRelease> =
        pages.flatMap { parse(it, packageName) }.distinctBy { it.version }

    /** Releases matching any of [builtinRules], newest first. */
    @JvmStatic
    fun filterSupported(releases: List<ApkMirrorRelease>, builtinRules: List<String>): List<ApkMirrorRelease> =
        releases
            .filter { release -> builtinRules.any { UniversalVersionValidator.matches(release.version, it) } }
            .sortedWith { a, b -> SupportedVersionSuggester.compareVersions(b.version, a.version) }

    /** RSS dates look like `Mon, 16 Nov 2026 14:03:12 +0000`; null when unreadable. */
    @JvmStatic
    fun parseDate(raw: String): Long? = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).apply { isLenient = false }
            .parse(raw.trim())?.time
    }.getOrNull()

    /** Strips CDATA wrappers and decodes the XML entities a serialised feed uses. */
    private fun text(raw: String): String {
        val trimmed = raw.trim()
        val unwrapped = if (trimmed.startsWith("<![CDATA[") && trimmed.endsWith("]]>")) {
            trimmed.removePrefix("<![CDATA[").removeSuffix("]]>")
        } else {
            trimmed
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
        }
        return unwrapped.trim()
    }
}
