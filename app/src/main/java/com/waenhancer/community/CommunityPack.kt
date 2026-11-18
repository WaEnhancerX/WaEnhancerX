package com.waenhancer.community

import java.net.URI

/** A downloadable data package, never executable code or a hook configuration. */
enum class CommunityPackType(val wireValue: String) {
    THEME("theme"),
    WALLPAPER_STATIC("wallpaper_static"),
    WALLPAPER_ANIMATED("wallpaper_animated");

    companion object {
        fun fromWire(value: String): CommunityPackType? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class CommunitySort(val wireValue: String) {
    NEWEST("newest"), OLDEST("oldest"), POPULAR("popular"), RATING("rating")
}

data class CommunityPack(
    val id: String,
    val type: CommunityPackType,
    val title: String,
    val description: String,
    val targetPackages: List<String>,
    val author: String,
    val license: String,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String,
    val createdAt: String,
    val downloads: Long,
    val ratingAverage: Double,
    val ratingCount: Long
)

data class CommunityPage(val items: List<CommunityPack>, val nextPageToken: String?)

/** Independent, deterministic contract checks shared by discovery and installation. */
object CommunityPackPolicy {
    const val MAX_PAGE_SIZE = 50
    const val MAX_RESPONSE_BYTES = 512 * 1024
    const val MAX_THEME_BYTES = 256L * 1024
    const val MAX_STATIC_BYTES = 16L * 1024 * 1024
    const val MAX_ANIMATED_BYTES = 16L * 1024 * 1024

    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val HASH = Regex("^[0-9a-f]{64}$")
    private val SUPPORTED_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    private val SUPPORTED_LICENSES = setOf("CC0-1.0", "CC-BY-4.0", "CC-BY-SA-4.0")
    private val ISO_TIME = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?Z$")

    fun allowedMediaTypes(type: CommunityPackType): Set<String> = when (type) {
        CommunityPackType.THEME -> setOf("application/vnd.waex.theme+json")
        CommunityPackType.WALLPAPER_STATIC -> setOf("image/png", "image/jpeg", "image/webp")
        CommunityPackType.WALLPAPER_ANIMATED -> setOf("image/gif", "video/mp4")
    }

    fun maxContentBytes(type: CommunityPackType): Long = when (type) {
        CommunityPackType.THEME -> MAX_THEME_BYTES
        CommunityPackType.WALLPAPER_STATIC -> MAX_STATIC_BYTES
        CommunityPackType.WALLPAPER_ANIMATED -> MAX_ANIMATED_BYTES
    }

    /** Returns violations instead of throwing, so the UI can explain rejected community input. */
    fun violations(pack: CommunityPack): List<String> = buildList {
        if (!UUID.matches(pack.id)) add("id")
        if (pack.title.isBlank() || !withinCodePoints(pack.title, 80) || pack.title.any(Char::isISOControl) || '<' in pack.title || '>' in pack.title) add("title")
        if (!withinCodePoints(pack.description, 500) || pack.description.any(Char::isISOControl) || '<' in pack.description || '>' in pack.description) add("description")
        if (pack.author.isBlank() || !withinCodePoints(pack.author, 60) || pack.author.any(Char::isISOControl) || '<' in pack.author || '>' in pack.author) add("author")
        if (pack.license !in SUPPORTED_LICENSES) add("license")
        if (pack.targetPackages.isEmpty() || pack.targetPackages.size > SUPPORTED_PACKAGES.size ||
            pack.targetPackages.any { it !in SUPPORTED_PACKAGES } ||
            pack.targetPackages.distinct().size != pack.targetPackages.size) add("target_packages")
        if (pack.mediaType !in allowedMediaTypes(pack.type)) add("media_type")
        if (pack.sizeBytes !in 1..maxContentBytes(pack.type)) add("size_bytes")
        if (!HASH.matches(pack.sha256)) add("sha256")
        if (!ISO_TIME.matches(pack.createdAt)) add("created_at")
        if (pack.downloads < 0) add("downloads")
        if (pack.ratingCount < 0 || !pack.ratingAverage.isFinite() || pack.ratingAverage !in 0.0..5.0 ||
            (pack.ratingCount == 0L && pack.ratingAverage != 0.0)) add("ratings")
    }

    // Python's backend `len(str)` measures Unicode code points, not UTF-16 code units.
    private fun withinCodePoints(value: String, max: Int): Boolean = value.codePointCount(0, value.length) <= max

    /** Public catalog endpoints must use TLS, without credentials, query or URL fragments. */
    fun trustedApiRoot(raw: String): URI? = runCatching {
        val uri = URI(raw.trim()).normalize()
        uri.takeIf {
            it.scheme.equals("https", ignoreCase = true) &&
                !it.host.isNullOrBlank() && it.userInfo == null && it.query == null && it.fragment == null &&
                (it.path.isNullOrEmpty() || it.path == "/") &&
                it.port in -1..65535
        }
    }.getOrNull()

    fun validCursor(token: String?): Boolean = token == null ||
        (token.isNotEmpty() && token.length <= 1024 && token.all { it.code in 33..126 })
}
