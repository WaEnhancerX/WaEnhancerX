package com.waenhancer.theme

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/** Metadata declared in the leading `/* key = value */` comment of a theme's style.css. */
data class ThemeHeader(
    val name: String?,
    val author: String?,
    val version: String?,
    val description: String?
) {
    companion object {
        private val BLOCK = Regex("^\\s*/\\*(.*?)\\*/", RegexOption.DOT_MATCHES_ALL)

        fun parse(css: String): ThemeHeader {
            val values = BLOCK.find(css)?.groupValues?.get(1)
                ?.lineSequence()
                ?.mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null
                    else line.substring(0, separator).trim().lowercase(Locale.ROOT) to
                        line.substring(separator + 1).trim().trim('"', '\'')
                }
                ?.toMap()
                .orEmpty()
            return ThemeHeader(
                name = values["name"]?.takeIf(String::isNotBlank),
                author = values["author"]?.takeIf(String::isNotBlank),
                version = values["version"]?.takeIf(String::isNotBlank),
                description = values["description"]?.takeIf(String::isNotBlank)
            )
        }
    }
}

/** Static checks run on every theme before it can be activated. */
object ThemeCssValidator {
    const val MAX_CSS_BYTES = 256 * 1024

    data class Result(val errors: List<String>, val warnings: List<String>) {
        val valid: Boolean get() = errors.isEmpty()
    }

    private val REMOTE_IMPORT = Regex("@import\\s*(url\\s*\\()?\\s*['\"]?\\s*(https?:)?//", RegexOption.IGNORE_CASE)
    private val REMOTE_URL = Regex("url\\s*\\(\\s*['\"]?\\s*(https?:)?//", RegexOption.IGNORE_CASE)

    fun validate(css: String): Result {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (css.toByteArray(Charsets.UTF_8).size > MAX_CSS_BYTES) errors += "CSS exceeds 256 KB."
        val lower = css.lowercase(Locale.ROOT)
        if ("javascript:" in lower) errors += "javascript: URLs are not allowed."
        if ("expression(" in lower) errors += "CSS expressions are not allowed."
        if (REMOTE_IMPORT.containsMatchIn(css)) errors += "Remote @import rules are not allowed."
        if (REMOTE_URL.containsMatchIn(css)) errors += "Remote url() references are not allowed; bundle the image in the theme."

        var depth = 0
        var inComment = false
        var quote = 0.toChar()
        var i = 0
        while (i < css.length) {
            val c = css[i]
            when {
                inComment -> if (c == '*' && css.getOrNull(i + 1) == '/') { inComment = false; i++ }
                quote != 0.toChar() -> when {
                    c == '\\' -> i++
                    c == quote -> quote = 0.toChar()
                }
                c == '/' && css.getOrNull(i + 1) == '*' -> { inComment = true; i++ }
                c == '"' || c == '\'' -> quote = c
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth < 0) { errors += "Unexpected closing brace."; depth = 0 }
                }
            }
            i++
        }
        if (inComment) errors += "Unterminated comment."
        if (quote != 0.toChar()) errors += "Unterminated string."
        if (depth > 0) errors += "Missing closing brace."
        if (css.isNotBlank() && !css.contains('{')) errors += "No style rules found."
        return Result(errors, warnings)
    }
}

/** Identifier rules shared by the repository and the content provider, so both agree on paths. */
object ThemeIds {
    private val SAFE_ID = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    private val SAFE_ASSET = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$")

    fun isValidId(id: String?): Boolean = id != null && SAFE_ID.matches(id) && !id.contains("..")

    /** Flat asset file names only: no directories, no hidden files, no traversal. */
    fun isValidAssetName(name: String?): Boolean =
        name != null && SAFE_ASSET.matches(name) && !name.contains("..")

    fun fromDisplayName(raw: String): String {
        val slug = raw.lowercase(Locale.ROOT)
            .removeSuffix(".zip")
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.', '_')
            .take(64)
        return slug.ifEmpty { "theme" }
    }
}

/** Extracts an untrusted theme zip into a directory, rejecting anything outside the allowed shape. */
object ThemeArchive {
    const val MAX_ENTRIES = 64
    const val MAX_ASSET_BYTES = 8L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 24L * 1024 * 1024
    const val STYLE_FILE = "style.css"
    private val ASSET_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "ttf", "otf")

    class InvalidThemeException(message: String) : IOException(message)

    /**
     * Writes `style.css` and flat asset files into [target] (which must be empty or new).
     * A single top-level folder wrapping the theme is tolerated, as most zip tools create one.
     */
    fun extract(input: InputStream, target: File): String {
        target.mkdirs()
        var css: String? = null
        var total = 0L
        var entries = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (++entries > MAX_ENTRIES) throw InvalidThemeException("Theme has too many files.")
                val name = entry.name.replace('\\', '/').substringAfterLast('/')
                val path = entry.name.replace('\\', '/')
                if (path.startsWith("/") || path.split('/').any { it == ".." }) {
                    throw InvalidThemeException("Unsafe path in theme: ${entry.name}")
                }
                if (path.count { it == '/' } > 1) continue // ignore nested content (e.g. __MACOSX/x/y)
                if (name.startsWith(".") || path.startsWith("__MACOSX")) continue
                val isStyle = name.equals(STYLE_FILE, ignoreCase = true)
                if (!isStyle) {
                    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (extension !in ASSET_EXTENSIONS || !ThemeIds.isValidAssetName(name)) continue
                }
                val limit = if (isStyle) ThemeCssValidator.MAX_CSS_BYTES.toLong() else MAX_ASSET_BYTES
                val bytes = readLimited(zip, limit)
                total += bytes.size
                if (total > MAX_TOTAL_BYTES) throw InvalidThemeException("Theme is too large.")
                if (isStyle) css = String(bytes, Charsets.UTF_8)
                else File(target, name).writeBytes(bytes)
            }
        }
        val source = css ?: throw InvalidThemeException("Theme has no $STYLE_FILE.")
        val result = ThemeCssValidator.validate(source)
        if (!result.valid) throw InvalidThemeException(result.errors.first())
        File(target, STYLE_FILE).writeText(source, Charsets.UTF_8)
        return source
    }

    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var read: Int
        var size = 0L
        while (input.read(buffer).also { read = it } != -1) {
            size += read
            if (size > limit) throw InvalidThemeException("A theme file is too large.")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
